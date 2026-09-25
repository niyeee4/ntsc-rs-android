/* SPDX-License-Identifier: GPL-2.0-or-later */
//! Safe wrapper over the x264 C shim (x264enc_shim.c), plus JNI entry points
//! for `com.ntscrs.android.X264Encoder`.
//!
//! Encoding mirrors the desktop app's x264enc setup: single-pass
//! constant-quantizer (`quantizer = 50 - quality`), preset names
//! veryslow..ultrafast for speed 0..8.

use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use std::os::raw::{c_int, c_uchar, c_void};

extern "C" {
    fn x264enc_open(
        width: c_int,
        height: c_int,
        fps_num: c_int,
        fps_den: c_int,
        quality: c_int,
        speed: c_int,
        use_444: c_int,
        interlaced: c_int,
        tff: c_int,
    ) -> *mut c_void;
    fn x264enc_headers(
        handle: *mut c_void,
        out: *mut c_uchar,
        cap: c_int,
        len: *mut c_int,
    ) -> c_int;
    fn x264enc_encode(
        handle: *mut c_void,
        yuv: *const c_uchar,
        pts: i64,
        out: *mut c_uchar,
        cap: c_int,
        out_pts: *mut i64,
        out_dts: *mut i64,
    ) -> c_int;
    fn x264enc_delayed(handle: *mut c_void) -> c_int;
    fn x264enc_close(handle: *mut c_void);
}

#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_X264Encoder_nativeX264Open(
    _env: JNIEnv<'_>,
    _cls: JClass<'_>,
    width: jint,
    height: jint,
    fps_num: jint,
    fps_den: jint,
    quality: jint,
    speed: jint,
    use_444: jboolean,
    interlaced: jboolean,
    tff: jboolean,
) -> jlong {
    let h = unsafe {
        x264enc_open(
            width,
            height,
            fps_num,
            fps_den,
            quality,
            speed,
            if use_444 != 0 { 1 } else { 0 },
            if interlaced != 0 { 1 } else { 0 },
            if tff != 0 { 1 } else { 0 },
        )
    };
    h as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_X264Encoder_nativeX264Headers<'local>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    handle: jlong,
) -> JByteArray<'local> {
    let mut out = vec![0u8; 8192];
    let mut len: c_int = 0;
    let rc = unsafe {
        x264enc_headers(
            handle as *mut c_void,
            out.as_mut_ptr(),
            out.len() as c_int,
            &mut len,
        )
    };
    if rc != 0 || len <= 0 {
        return env.byte_array_from_slice(&[]).expect("OOM");
    }
    out.truncate(len as usize);
    env.byte_array_from_slice(&out).expect("OOM")
}

#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_X264Encoder_nativeX264Encode<'local>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    handle: jlong,
    yuv: JByteArray<'local>,
    pts: jlong,
    is_flush: jboolean,
) -> JByteArray<'local> {
    // Empty array = no output yet (encoder buffering). Otherwise the first
    // 16 bytes are pts + dts (i64 LE, encoder timebase units), then annex-B.
    let input: Option<(Vec<u8>, i64)> = if is_flush != 0 {
        None
    } else {
        match env.convert_byte_array(&yuv) {
            Ok(b) => Some((b, pts as i64)),
            Err(_) => return env.byte_array_from_slice(&[]).expect("OOM"),
        }
    };
    // Output cap: 4x raw frame size is plenty even at QP 0.
    let cap = input
        .as_ref()
        .map(|(b, _)| b.len() * 8 / 3 + 65536)
        .unwrap_or(1 << 20);
    let mut out = vec![0u8; cap];
    let mut o_pts: i64 = 0;
    let mut o_dts: i64 = 0;
    let n = unsafe {
        x264enc_encode(
            handle as *mut c_void,
            input
                .as_ref()
                .map(|(b, _)| b.as_ptr())
                .unwrap_or(std::ptr::null()) as *const c_uchar,
            input.as_ref().map(|(_, p)| *p).unwrap_or(0),
            out.as_mut_ptr(),
            cap as c_int,
            &mut o_pts,
            &mut o_dts,
        )
    };
    if n <= 0 {
        return env.byte_array_from_slice(&[]).expect("OOM");
    }
    let mut framed = Vec::with_capacity(16 + n as usize);
    framed.extend_from_slice(&o_pts.to_le_bytes());
    framed.extend_from_slice(&o_dts.to_le_bytes());
    framed.extend_from_slice(&out[..n as usize]);
    env.byte_array_from_slice(&framed).expect("OOM")
}

#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_X264Encoder_nativeX264Delayed(
    _env: JNIEnv<'_>,
    _cls: JClass<'_>,
    handle: jlong,
) -> jint {
    unsafe { x264enc_delayed(handle as *mut c_void) }
}

#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_X264Encoder_nativeX264Close(
    _env: JNIEnv<'_>,
    _cls: JClass<'_>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { x264enc_close(handle as *mut c_void) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn x264_roundtrip_i420() {
        let (w, h) = (160, 96);
        let hdl = unsafe { x264enc_open(w, h, 30, 1, 27, 8, 0, 0, 1) };
        assert!(!hdl.is_null(), "x264 open failed");
        let mut hdr = vec![0u8; 8192];
        let mut hlen: c_int = 0;
        assert_eq!(
            unsafe { x264enc_headers(hdl, hdr.as_mut_ptr(), hdr.len() as c_int, &mut hlen) },
            0
        );
        assert!(hlen > 0, "empty headers");
        hdr.truncate(hlen as usize);
        // Annex-B SPS starts with 0x00 0x00 0x00 0x01 0x67.
        assert_eq!(&hdr[0..5], &[0, 0, 0, 1, 0x67]);

        // Grey frame.
        let mut yuv = vec![0x80u8; (w * h * 3 / 2) as usize];
        yuv[..(w * h) as usize].fill(0x80);
        let mut out = vec![0u8; 1 << 20];
        let mut pts = 0i64;
        let mut dts = 0i64;
        let mut got = 0;
        for i in 0..10i64 {
            let n = unsafe {
                x264enc_encode(
                    hdl,
                    yuv.as_ptr(),
                    i,
                    out.as_mut_ptr(),
                    out.len() as c_int,
                    &mut pts,
                    &mut dts,
                )
            };
            assert!(n >= 0, "encode error");
            if n > 0 {
                got += 1;
            }
        }
        // Flush.
        while unsafe { x264enc_delayed(hdl) } > 0 {
            let n = unsafe {
                x264enc_encode(
                    hdl,
                    std::ptr::null(),
                    0,
                    out.as_mut_ptr(),
                    out.len() as c_int,
                    &mut pts,
                    &mut dts,
                )
            };
            assert!(n >= 0, "flush error");
            if n > 0 {
                got += 1;
            } else {
                break;
            }
        }
        assert!(got >= 10, "expected 10 frames, got {got}");
        unsafe { x264enc_close(hdl) };
    }
}
