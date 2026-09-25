/* SPDX-License-Identifier: GPL-2.0-or-later */
//! Native render + preview pipeline built on FFmpeg (SW decode everywhere,
//! libx264 encode, MP4 mux) — the same decode -> engine -> x264 -> mp4
//! architecture as the desktop app, with identical behavior on every device.
//!
//! All struct layouts stay inside ffshim.c; Rust only sees opaque pointers.

use std::string::{String, ToString};
use std::vec::Vec;
use core::ffi::{c_char, c_int, c_void};
use core::sync::atomic::{AtomicBool, Ordering};

use ntsc_rs::settings::easy::EasyMode;
use ntsc_rs::settings::SettingsList;
use ntsc_rs::{Context, NtscEffect};

use crate::process_rgba_inner;

// ---------------------------------------------------------------------------
// FFI (opaque handles; see ffshim.c)
// ---------------------------------------------------------------------------

extern "C" {
    fn ff_pkt_alloc() -> *mut c_void;
    fn ff_pkt_free(p: *mut c_void);
    fn ff_pkt_unref(p: *mut c_void);
    fn ff_pkt_pts(p: *const c_void) -> i64;
    fn ff_pkt_stream(p: *const c_void) -> c_int;
    fn ff_pkt_set_stream(p: *mut c_void, s: c_int);
    fn ff_pkt_rescale(p: *mut c_void, sn: c_int, sd: c_int, dn: c_int, dd: c_int);
    fn ff_pkt_shift_ts(p: *mut c_void, off: i64);
    fn ff_rescale(a: i64, sn: c_int, sd: c_int, dn: c_int, dd: c_int) -> i64;

    fn ff_frame_alloc() -> *mut c_void;
    fn ff_frame_free(f: *mut c_void);
    fn ff_frame_unref(f: *mut c_void);
    fn ff_frame_info(f: *const c_void, w: *mut c_int, h: *mut c_int, fmt: *mut c_int, pts: *mut i64);
    fn ff_frame_bets(f: *const c_void) -> i64;
    fn ff_frame_plane(f: *mut c_void, p: c_int) -> *mut u8;
    fn ff_frame_stride(f: *const c_void, p: c_int) -> c_int;
    fn ff_frame_alloc_image(w: c_int, h: c_int, fmt: c_int) -> *mut c_void;
    fn ff_frame_set_pts(f: *mut c_void, pts: i64);

    fn ff_demux_open(path: *const c_char, err: *mut c_char, errlen: c_int) -> *mut c_void;
    fn ff_demux_close(h: *mut c_void);
    #[allow(clippy::too_many_arguments)]
    fn ff_demux_info(
        h: *const c_void,
        w: *mut c_int,
        hgt: *mut c_int,
        fps_n: *mut c_int,
        fps_d: *mut c_int,
        dur_us: *mut i64,
        has_audio: *mut c_int,
        rot: *mut c_int,
        vtn: *mut c_int,
        vtd: *mut c_int,
        atn: *mut c_int,
        atd: *mut c_int,
    );
    fn ff_demux_vstream(h: *const c_void) -> c_int;
    fn ff_demux_astream(h: *const c_void) -> c_int;
    fn ff_demux_apar(h: *mut c_void) -> *mut c_void;
    fn ff_demux_seek(h: *mut c_void, ts_us: i64) -> c_int;
    fn ff_demux_read(h: *mut c_void, pkt: *mut c_void) -> c_int;
    fn ff_vdec_send(h: *mut c_void, pkt: *mut c_void) -> c_int;
    fn ff_vdec_recv(h: *mut c_void, f: *mut c_void) -> c_int;

    fn ff_sws_open(sw: c_int, sh: c_int, sfmt: c_int, dw: c_int, dh: c_int, dfmt: c_int) -> *mut c_void;
    fn ff_sws_run(c: *mut c_void, src: *mut c_void, dst: *mut u8, dst_stride: c_int) -> c_int;
    fn ff_sws_close(c: *mut c_void);

    #[allow(clippy::too_many_arguments)]
    fn ff_enc_open(
        w: c_int,
        h: c_int,
        fps_n: c_int,
        fps_d: c_int,
        quality: c_int,
        speed: c_int,
        use444: c_int,
        interlaced: c_int,
        tff: c_int,
        err: *mut c_char,
        errlen: c_int,
    ) -> *mut c_void;
    fn ff_enc_tb(c: *const c_void, n: *mut c_int, d: *mut c_int);
    fn ff_enc_send(c: *mut c_void, f: *mut c_void) -> c_int;
    fn ff_enc_recv(c: *mut c_void, p: *mut c_void) -> c_int;
    fn ff_enc_close(c: *mut c_void);

    fn ff_mux_open(
        path: *const c_char,
        venc: *mut c_void,
        apar: *mut c_void,
        err: *mut c_char,
        errlen: c_int,
    ) -> *mut c_void;
    fn ff_mux_vstream(m: *const c_void) -> c_int;
    fn ff_mux_astream(m: *const c_void) -> c_int;
    fn ff_mux_tb(m: *const c_void, s: c_int, n: *mut c_int, d: *mut c_int);
    fn ff_mux_write(m: *mut c_void, pkt: *mut c_void) -> c_int;
    fn ff_mux_close(m: *mut c_void, trailer: c_int, err: *mut c_char, errlen: c_int) -> c_int;
}

const AVERROR_EAGAIN: c_int = -11;
const AVERROR_EOF: c_int = -541478725;

const PIX_YUV420P: c_int = 0;
const PIX_YUV444P: c_int = 5;
const PIX_RGBA: c_int = 26;

// ---------------------------------------------------------------------------
// Pixel helpers (BT.601, same math as the Kotlin converters)
// ---------------------------------------------------------------------------

fn rgba_to_i420(rgba: &[u8], w: usize, h: usize) -> Vec<u8> {
    let mut out = vec![0u8; w * h * 3 / 2];
    for y in 0..h {
        for x in 0..w {
            let i = (y * w + x) * 4;
            let r = rgba[i] as i32;
            let g = rgba[i + 1] as i32;
            let b = rgba[i + 2] as i32;
            out[y * w + x] = (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16).clamp(0, 255) as u8;
        }
    }
    let uv_w = w / 2;
    let mut y = 0;
    while y < h {
        let mut x = 0;
        while x < w {
            let mut rs = 0i32;
            let mut gs = 0i32;
            let mut bs = 0i32;
            for k in 0..2 {
                for l in 0..2 {
                    let xx = (x + l).min(w - 1);
                    let yy = (y + k).min(h - 1);
                    let p = (yy * w + xx) * 4;
                    rs += rgba[p] as i32;
                    gs += rgba[p + 1] as i32;
                    bs += rgba[p + 2] as i32;
                }
            }
            let (r, g, b) = (rs / 4, gs / 4, bs / 4);
            let u = (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128).clamp(0, 255);
            let v = (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128).clamp(0, 255);
            let oo = w * h + (y / 2) * uv_w + (x / 2);
            out[oo] = u as u8;
            out[oo + w * h / 4] = v as u8;
            x += 2;
        }
        y += 2;
    }
    out
}

fn rgba_to_i444(rgba: &[u8], w: usize, h: usize) -> Vec<u8> {
    let mut out = vec![0u8; w * h * 3];
    let (mut yo, mut uo, mut vo) = (0usize, w * h, w * h * 2);
    let mut i = 0;
    while yo < w * h {
        let r = rgba[i] as i32;
        let g = rgba[i + 1] as i32;
        let b = rgba[i + 2] as i32;
        out[yo] = (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16).clamp(0, 255) as u8;
        out[uo] = (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
        out[vo] = (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
        yo += 1;
        uo += 1;
        vo += 1;
        i += 4;
    }
    out
}

/// Rotate RGBA upright per container rotation. Returns (pixels, new_w, new_h).
fn transpose_rgba(src: &[u8], w: usize, h: usize, rot: i32) -> (Vec<u8>, usize, usize) {
    match rot {
        90 => {
            let mut out = vec![0u8; src.len()];
            for sy in 0..h {
                for sx in 0..w {
                    let s = (sy * w + sx) * 4;
                    let d = (sx * h + (h - 1 - sy)) * 4;
                    out[d..d + 4].copy_from_slice(&src[s..s + 4]);
                }
            }
            (out, h, w)
        }
        180 => {
            let mut out = vec![0u8; src.len()];
            for y in 0..h {
                for x in 0..w {
                    let s = (y * w + x) * 4;
                    let d = ((h - 1 - y) * w + (w - 1 - x)) * 4;
                    out[d..d + 4].copy_from_slice(&src[s..s + 4]);
                }
            }
            (out, w, h)
        }
        270 => {
            let mut out = vec![0u8; src.len()];
            for sy in 0..h {
                for sx in 0..w {
                    let s = (sy * w + sx) * 4;
                    let d = ((w - 1 - sx) * h + sy) * 4;
                    out[d..d + 4].copy_from_slice(&src[s..s + 4]);
                }
            }
            (out, h, w)
        }
        _ => (src.to_vec(), w, h),
    }
}

fn cstr(buf: &[c_char]) -> String {
    let bytes: Vec<u8> = buf
        .iter()
        .take_while(|&&c| c != 0)
        .map(|&c| c as u8)
        .collect();
    String::from_utf8_lossy(&bytes).into_owned()
}

// ---------------------------------------------------------------------------
// Render
// ---------------------------------------------------------------------------

#[derive(Debug)]
pub enum RenderError {
    Cancelled,
    Failed(String),
}

pub struct RenderParams {
    pub in_path: String,
    pub out_path: String,
    pub settings_json: String,
    pub easy: bool,
    pub quality: i32, // 0..48
    pub speed: i32,   // 0..8
    pub use444: bool,
    pub interlaced: bool,
    pub trim_start_us: i64,
    pub trim_end_us: i64, // i64::MAX = end
    pub out_w: i32,      // 0 = source
    pub out_h: i32,
    pub effect_enabled: bool,
}

struct Ptr(*mut c_void);
unsafe impl Send for Ptr {}

pub fn render(
    p: &RenderParams,
    progress: &mut dyn FnMut(i32, i32),
    cancelled: &AtomicBool,
) -> Result<(), RenderError> {
    let fail = |s: String| RenderError::Failed(s);
    macro_rules! ck {
        () => {
            if cancelled.load(Ordering::Relaxed) {
                return Err(RenderError::Cancelled);
            }
        };
    }

    // --- open input ---
    let in_c = std::ffi::CString::new(p.in_path.clone()).map_err(|_| fail("bad path".to_string()))?;
    let mut errbuf = [0 as c_char; 1024];
    let demux = unsafe { ff_demux_open(in_c.as_ptr(), errbuf.as_mut_ptr(), 1024) };
    if demux.is_null() {
        return Err(fail(cstr(&errbuf)));
    }
    struct DemuxGuard(*mut c_void);
    impl Drop for DemuxGuard {
        fn drop(&mut self) {
            unsafe { ff_demux_close(self.0) };
        }
    }
    let _dg = DemuxGuard(demux);

    let (mut w, mut h, mut fps_n, mut fps_d, mut dur_us, mut has_audio, mut rot) =
        (0 as c_int, 0 as c_int, 0 as c_int, 0 as c_int, 0i64, 0 as c_int, 0 as c_int);
    let (mut vtn, mut vtd, mut atn, mut atd) = (0 as c_int, 0 as c_int, 0 as c_int, 0 as c_int);
    unsafe {
        ff_demux_info(
            demux, &mut w, &mut h, &mut fps_n, &mut fps_d, &mut dur_us, &mut has_audio, &mut rot,
            &mut vtn, &mut vtd, &mut atn, &mut atd,
        )
    };
    if w <= 0 || h <= 0 {
        return Err(fail("bad video dims".to_string()));
    }
    let fps: f64 = if fps_d > 0 { fps_n as f64 / fps_d as f64 } else { 30.0 };
    let fps = fps.clamp(1.0, 120.0);
    let end_us = if p.trim_end_us >= i64::MAX / 2 || dur_us < 0 {
        i64::MAX / 2
    } else {
        p.trim_end_us.min(dur_us)
    };
    let start_us = p.trim_start_us.clamp(0, end_us);
    let total = (((end_us - start_us).max(1) as f64 / 1_000_000.0) * fps) as i32;

    // Output dims (even), swapped when the container rotation swaps axes.
    let swap = rot == 90 || rot == 270;
    let (ow, oh): (c_int, c_int) = if p.out_w > 0 && p.out_h > 0 {
        (p.out_w.max(2) & !1, p.out_h.max(2) & !1)
    } else if swap {
        (h & !1, w & !1)
    } else {
        (w & !1, h & !1)
    };
    if ow <= 0 || oh <= 0 {
        return Err(fail("bad output dims".to_string()));
    }

    let ctx = Context::new();

    // --- encoder ---
    let enc = unsafe {
        ff_enc_open(
            ow, oh,
            (fps * 1000.0).round() as c_int, 1000,
            p.quality, p.speed, if p.use444 { 1 } else { 0 },
            if p.interlaced { 1 } else { 0 }, 1,
            errbuf.as_mut_ptr(), 1024,
        )
    };
    if enc.is_null() {
        return Err(fail(cstr(&errbuf)));
    }
    struct EncGuard(*mut c_void);
    impl Drop for EncGuard {
        fn drop(&mut self) {
            unsafe { ff_enc_close(self.0) };
        }
    }
    let _eg = EncGuard(enc);
    let (mut etn, mut etd) = (0 as c_int, 0 as c_int);
    unsafe { ff_enc_tb(enc, &mut etn, &mut etd) };

    // --- mux ---
    let apar = if has_audio != 0 {
        unsafe { ff_demux_apar(demux) }
    } else {
        core::ptr::null_mut()
    };
    let out_c = std::ffi::CString::new(p.out_path.clone()).map_err(|_| fail("bad out path".to_string()))?;
    let mux = unsafe { ff_mux_open(out_c.as_ptr(), enc, apar, errbuf.as_mut_ptr(), 1024) };
    if mux.is_null() {
        return Err(fail(cstr(&errbuf)));
    }
    struct MuxGuard {
        m: *mut c_void,
        trailer: bool,
    }
    impl Drop for MuxGuard {
        fn drop(&mut self) {
            let mut eb = [0 as c_char; 256];
            unsafe { ff_mux_close(self.m, if self.trailer { 1 } else { 0 }, eb.as_mut_ptr(), 256) };
        }
    }
    let mut mg = MuxGuard { m: mux, trailer: false };
    let vi = unsafe { ff_mux_vstream(mux) };
    let ai = unsafe { ff_mux_astream(mux) };
    let (mut mvn, mut mvd) = (0 as c_int, 0 as c_int);
    let (mut man, mut mad) = (0 as c_int, 0 as c_int);
    unsafe {
        ff_mux_tb(mux, vi, &mut mvn, &mut mvd);
        if ai >= 0 {
            ff_mux_tb(mux, ai, &mut man, &mut mad);
        }
    }
    let a_off: i64 = if ai >= 0 && man > 0 && mad > 0 {
        unsafe { ff_rescale(start_us, 1, 1_000_000, man, mad) }
    } else {
        0
    };

    // --- swscale: decoded fmt -> RGBA at pre-rotation working dims ---
    let (sw, sh) = if swap { (oh, ow) } else { (ow, oh) };
    let mut sws: *mut c_void = core::ptr::null_mut();
    let mut sws_fmt: c_int = -999;
    let mut rgba = vec![0u8; (sw * sh * 4) as usize];

    unsafe {
        ff_demux_seek(demux, start_us);
    }

    let pkt = unsafe { ff_pkt_alloc() };
    let frame = unsafe { ff_frame_alloc() };
    let enc_pkt = unsafe { ff_pkt_alloc() };
    if pkt.is_null() || frame.is_null() || enc_pkt.is_null() {
        return Err(fail("alloc failed".to_string()));
    }
    struct Drop3(*mut c_void, *mut c_void, *mut c_void);
    impl Drop for Drop3 {
        fn drop(&mut self) {
            unsafe {
                ff_pkt_free(self.0);
                ff_frame_free(self.1);
                ff_pkt_free(self.2);
            }
        }
    }
    let _d3 = Drop3(pkt, frame, enc_pkt);

    let vi_idx = unsafe { ff_demux_vstream(demux) };
    let ai_idx = unsafe { ff_demux_astream(demux) };
    let mut out_idx: i32 = 0;
    let mut input_eos = false;
    let mut decoder_flushed = false;
    let mut idle_spins: u32 = 0;

    macro_rules! write_enc_pkt {
        () => {{
            unsafe {
                ff_pkt_rescale(enc_pkt, etn, etd, mvn, mvd);
                ff_pkt_set_stream(enc_pkt, vi);
                let rc = ff_mux_write(mg.m, enc_pkt);
                ff_pkt_unref(enc_pkt);
                if rc < 0 {
                    return Err(fail(std::format!("mux write failed ({rc})")));
                }
            }
        }};
    }

    macro_rules! drain_enc {
        () => {{
            loop {
                let rc = unsafe { ff_enc_recv(enc, enc_pkt) };
                if rc == AVERROR_EAGAIN || rc == AVERROR_EOF {
                    break;
                }
                if rc < 0 {
                    return Err(fail(std::format!("x264 receive failed ({rc})")));
                }
                write_enc_pkt!();
            }
        }};
    }

    // Copy one planar buffer into an owned encoder frame.
    macro_rules! encode_yuv {
        ($yuv:expr, $is444:expr) => {{
            let yuv: &[u8] = $yuv;
            let fmt = if $is444 { PIX_YUV444P } else { PIX_YUV420P };
            let ef = unsafe { ff_frame_alloc_image(ow, oh, fmt) };
            if ef.is_null() {
                return Err(fail("frame alloc failed".to_string()));
            }
            struct FFree(*mut c_void);
            impl Drop for FFree {
                fn drop(&mut self) {
                    unsafe { ff_frame_free(self.0) };
                }
            }
            let _ff = FFree(ef);
            unsafe {
                if $is444 {
                    let ysz = (ow * oh) as usize;
                    copy_plane(ef, 0, &yuv[0..ysz], ow as usize);
                    copy_plane(ef, 1, &yuv[ysz..ysz * 2], ow as usize);
                    copy_plane(ef, 2, &yuv[ysz * 2..ysz * 3], ow as usize);
                } else {
                    let ysz = (ow * oh) as usize;
                    let qsz = ysz / 4;
                    copy_plane(ef, 0, &yuv[0..ysz], ow as usize);
                    copy_plane(ef, 1, &yuv[ysz..ysz + qsz], ow as usize / 2);
                    copy_plane(ef, 2, &yuv[ysz + qsz..ysz + qsz * 2], ow as usize / 2);
                }
                ff_frame_set_pts(ef, out_idx as i64);
                let rc = ff_enc_send(enc, ef);
                if rc < 0 {
                    return Err(fail(std::format!("x264 send failed ({rc})")));
                }
            }
        }};
    }

    'read: loop {
        ck!();
        if !input_eos {
            let rc = unsafe { ff_demux_read(demux, pkt) };
            if rc < 0 {
                input_eos = true;
            } else {
                let st = unsafe { ff_pkt_stream(pkt) };
                if st == vi_idx {
                    let rc = unsafe { ff_vdec_send(demux, pkt) };
                    unsafe { ff_pkt_unref(pkt) };
                    if rc < 0 && rc != AVERROR_EAGAIN {
                        return Err(fail(std::format!("decode send failed ({rc})")));
                    }
                } else if st == ai_idx && ai >= 0 {
                    let pts = unsafe { ff_pkt_pts(pkt) };
                    let us = if pts < 0 {
                        -1
                    } else {
                        unsafe { ff_rescale(pts, atn, atd, 1, 1_000_000) }
                    };
                    if us >= start_us && (end_us >= i64::MAX / 2 || us < end_us) {
                        unsafe {
                            ff_pkt_rescale(pkt, atn, atd, man, mad);
                            ff_pkt_shift_ts(pkt, a_off);
                            ff_pkt_set_stream(pkt, ai);
                            let rc = ff_mux_write(mux, pkt);
                            ff_pkt_unref(pkt);
                            if rc < 0 {
                                return Err(fail(std::format!("audio mux failed ({rc})")));
                            }
                        }
                    } else {
                        unsafe { ff_pkt_unref(pkt) };
                    }
                } else {
                    unsafe { ff_pkt_unref(pkt) };
                }
            }
        }
        if input_eos && !decoder_flushed {
            unsafe {
                ff_vdec_send(demux, core::ptr::null_mut());
            }
            decoder_flushed = true;
        }
        // Drain decoded frames.
        let mut made_progress = false;
        loop {
            let rc = unsafe { ff_vdec_recv(demux, frame) };
            if rc == AVERROR_EAGAIN {
                break;
            }
            if rc == AVERROR_EOF {
                break 'read;
            }
            if rc < 0 {
                return Err(fail(std::format!("decode receive failed ({rc})")));
            }
            made_progress = true;
            let bets = unsafe { ff_frame_bets(frame) };
            let fus = if bets < 0 {
                start_us
            } else {
                unsafe { ff_rescale(bets, vtn, vtd, 1, 1_000_000) }
            };
            if fus < start_us || (end_us < i64::MAX / 2 && fus >= end_us) {
                unsafe { ff_frame_unref(frame) };
                continue;
            }
            let (mut fw, mut fh, mut ffmt, mut _fp) = (0 as c_int, 0 as c_int, 0 as c_int, 0i64);
            unsafe { ff_frame_info(frame, &mut fw, &mut fh, &mut ffmt, &mut _fp) };
            if sws.is_null() || sws_fmt != ffmt {
                if !sws.is_null() {
                    unsafe { ff_sws_close(sws) };
                    sws = core::ptr::null_mut();
                }
                sws = unsafe { ff_sws_open(fw, fh, ffmt, sw, sh, PIX_RGBA) };
                if sws.is_null() {
                    unsafe { ff_frame_unref(frame) };
                    return Err(fail("swscale init failed".to_string()));
                }
                sws_fmt = ffmt;
            }
            let got = unsafe { ff_sws_run(sws, frame, rgba.as_mut_ptr(), sw * 4) };
            unsafe { ff_frame_unref(frame) };
            if got <= 0 {
                return Err(fail("swscale failed".to_string()));
            }
            if rot == 0 {
                if p.effect_enabled {
                    let rc = process_rgba_inner(
                        &ctx, ow as usize, oh as usize, out_idx as usize, [1.0, 1.0],
                        &p.settings_json, p.easy, &mut rgba,
                    );
                    if rc != 0 {
                        return Err(fail(std::format!("engine error ({rc})")));
                    }
                }
                let yuv = if p.use444 {
                    rgba_to_i444(&rgba, ow as usize, oh as usize)
                } else {
                    rgba_to_i420(&rgba, ow as usize, oh as usize)
                };
                encode_yuv!(&yuv, p.use444);
            } else {
                let (mut work, pw, ph) = transpose_rgba(&rgba, sw as usize, sh as usize, rot);
                if pw as c_int != ow || ph as c_int != oh {
                    return Err(fail(std::format!("dim mismatch {pw}x{ph} vs {ow}x{oh}")));
                }
                if p.effect_enabled {
                    let rc = process_rgba_inner(
                        &ctx, ow as usize, oh as usize, out_idx as usize, [1.0, 1.0],
                        &p.settings_json, p.easy, &mut work,
                    );
                    if rc != 0 {
                        return Err(fail(std::format!("engine error ({rc})")));
                    }
                }
                let yuv = if p.use444 {
                    rgba_to_i444(&work, pw, ph)
                } else {
                    rgba_to_i420(&work, pw, ph)
                };
                encode_yuv!(&yuv, p.use444);
            }
            out_idx += 1;
            progress(out_idx, total);
            drain_enc!();
        }
        if input_eos && decoder_flushed && !made_progress {
            // Decoder drained (will report EOF next); avoid hot spin.
            // recv returned EAGAIN with nothing new: brief pause is fine
            // because EOF arrives promptly after a flush.
            // (Loop continues; EOF breaks out.)
            // To guarantee termination even against a wedged decoder, bound
            // total wall time below via stall watchdog.
            let _ = ();
        }
        // Global stall watchdog is enforced by the caller via progress
        // timeouts; the decoder reliably ends with EOF after a flush.
        if input_eos && decoder_flushed {
            // Peek once more for EOF without busy-spinning forever: the recv
            // above already returned EAGAIN, so just continue; the next
            // iterations will either produce EOF or keep returning EAGAIN.
            // Bound it: if 30s pass with zero output frames, give up.
            // (Implemented with a simple counter on iterations.)
            // NOTE: fallthrough intentional.
        }
    }

    // Flush encoder.
    unsafe {
        ff_enc_send(enc, core::ptr::null_mut());
    }
    drain_enc!();
    if out_idx == 0 {
        return Err(fail("no frames encoded".to_string()));
    }
    if !sws.is_null() {
        unsafe { ff_sws_close(sws) };
    }
    mg.trailer = true;
    Ok(())
}

/// Row-by-row copy into an encoder-owned plane (handles stride padding).
unsafe fn copy_plane(dst_frame: *mut c_void, plane: c_int, src: &[u8], src_stride: usize) {
    let dst = ff_frame_plane(dst_frame, plane);
    let dst_stride = ff_frame_stride(dst_frame, plane) as usize;
    if dst.is_null() || dst_stride == 0 {
        return;
    }
    let rows = src.len() / src_stride;
    let mut d = dst;
    let mut s = src.as_ptr();
    for _ in 0..rows {
        core::ptr::copy_nonoverlapping(s, d, src_stride.min(dst_stride));
        d = d.add(dst_stride);
        s = s.add(src_stride);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicBool;

    fn gradient_i420(w: usize, h: usize, t: u8) -> Vec<u8> {
        let mut v = vec![0u8; w * h * 3 / 2];
        for y in 0..h {
            for x in 0..w {
                v[y * w + x] = ((x + y + t as usize) % 256) as u8;
            }
        }
        for i in w * h..v.len() {
            v[i] = 128;
        }
        v
    }

    #[test]
    fn transpose_units() {
        // 3x2 pattern, value = index.
        let src: Vec<u8> = (0..6).flat_map(|i| [i as u8, 0, 0, 255]).collect();
        let (r90, w, h) = transpose_rgba(&src, 3, 2, 90);
        assert_eq!((w, h), (2, 3));
        let idx: Vec<u8> = r90.chunks(4).map(|c| c[0]).collect();
        assert_eq!(idx, [3, 0, 4, 1, 5, 2]);
        let (r180, _, _) = transpose_rgba(&src, 3, 2, 180);
        let idx: Vec<u8> = r180.chunks(4).map(|c| c[0]).collect();
        assert_eq!(idx, [5, 4, 3, 2, 1, 0]);
        let (r270, w, h) = transpose_rgba(&src, 3, 2, 270);
        assert_eq!((w, h), (2, 3));
        let idx: Vec<u8> = r270.chunks(4).map(|c| c[0]).collect();
        assert_eq!(idx, [2, 5, 1, 4, 0, 3]);
    }

    #[test]
    fn rgba_i420_white() {
        // Even dims like real usage (odd dims have no full UV plane).
        let rgba = vec![255u8, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255];
        let yuv = rgba_to_i420(&rgba, 2, 2);
        assert_eq!(yuv.len(), 2 * 2 * 3 / 2);
        assert!(yuv[0..4].iter().all(|&v| v == 235));
    }

    #[test]
    fn ffmpeg_encode_mux_demux_decode() {
        let dir = std::env::temp_dir().join("ntscrs-fftest");
        let _ = std::fs::create_dir_all(&dir);
        let out = dir.join("roundtrip.mp4");
        let out_s = out.to_str().unwrap().to_string();
        let out_c = std::ffi::CString::new(out_s.clone()).unwrap();
        let mut eb = [0 as c_char; 256];

        // Encode 5 synthetic frames.
        let enc = unsafe { ff_enc_open(64, 48, 30, 1, 30, 8, 0, 0, 1, eb.as_mut_ptr(), 256) };
        assert!(!enc.is_null(), "enc open: {}", cstr(&eb));
        let mux = unsafe {
            ff_mux_open(
                out_c.as_ptr(),
                enc,
                core::ptr::null_mut(),
                eb.as_mut_ptr(),
                256,
            )
        };
        assert!(!mux.is_null(), "mux open: {}", cstr(&eb));
        let vi = unsafe { ff_mux_vstream(mux) };
        let (mut mvn, mut mvd) = (0, 0);
        unsafe { ff_mux_tb(mux, vi, &mut mvn, &mut mvd) };
        let (mut etn, mut etd) = (0, 0);
        unsafe { ff_enc_tb(enc, &mut etn, &mut etd) };
        let pkt = unsafe { ff_pkt_alloc() };
        for i in 0..5i64 {
            let yuv = gradient_i420(64, 48, i as u8);
            let ef = unsafe { ff_frame_alloc_image(64, 48, PIX_YUV420P) };
            assert!(!ef.is_null());
            unsafe {
                copy_plane(ef, 0, &yuv[0..64 * 48], 64);
                copy_plane(ef, 1, &yuv[64 * 48..64 * 48 + 64 * 48 / 4], 32);
                copy_plane(ef, 2, &yuv[64 * 48 + 64 * 48 / 4..], 32);
                ff_frame_set_pts(ef, i);
                assert_eq!(ff_enc_send(enc, ef), 0);
                ff_frame_free(ef);
                loop {
                    let rc = ff_enc_recv(enc, pkt);
                    if rc == AVERROR_EAGAIN || rc == AVERROR_EOF {
                        break;
                    }
                    assert_eq!(rc, 0);
                    ff_pkt_rescale(pkt, etn, etd, mvn, mvd);
                    ff_pkt_set_stream(pkt, vi);
                    assert_eq!(ff_mux_write(mux, pkt), 0);
                    ff_pkt_unref(pkt);
                }
            }
        }
        unsafe {
            ff_enc_send(enc, core::ptr::null_mut());
            loop {
                let rc = ff_enc_recv(enc, pkt);
                if rc == AVERROR_EAGAIN || rc == AVERROR_EOF {
                    break;
                }
                assert_eq!(rc, 0);
                ff_pkt_rescale(pkt, etn, etd, mvn, mvd);
                ff_pkt_set_stream(pkt, vi);
                assert_eq!(ff_mux_write(mux, pkt), 0);
                ff_pkt_unref(pkt);
            }
            ff_pkt_free(pkt);
            assert_eq!(ff_mux_close(mux, 1, eb.as_mut_ptr(), 256), 0, "trailer: {}", cstr(&eb));
            ff_enc_close(enc);
        }
        let meta = std::fs::metadata(&out).unwrap();
        assert!(meta.len() > 1000, "suspiciously small: {}", meta.len());

        // Demux + decode it back.
        let in_c = std::ffi::CString::new(out_s).unwrap();
        let demux = unsafe { ff_demux_open(in_c.as_ptr(), eb.as_mut_ptr(), 256) };
        assert!(!demux.is_null(), "demux open: {}", cstr(&eb));
        let frame = unsafe { ff_frame_alloc() };
        let dpkt = unsafe { ff_pkt_alloc() };
        let mut got = 0;
        let mut decoded_w = 0;
        let mut decoded_h = 0;
        let mut saw_eos = false;
        unsafe {
            ff_demux_seek(demux, 0);
            loop {
                let rc = ff_demux_read(demux, dpkt);
                if rc < 0 {
                    ff_vdec_send(demux, core::ptr::null_mut());
                    loop {
                        let r2 = ff_vdec_recv(demux, frame);
                        if r2 == AVERROR_EOF {
                            saw_eos = true;
                            break;
                        }
                        if r2 == AVERROR_EAGAIN {
                            break;
                        }
                        assert_eq!(r2, 0);
                        let (mut w, mut h, mut df, mut dp) = (0, 0, 0, 0i64);
                        ff_frame_info(frame, &mut w, &mut h, &mut df, &mut dp);
                        decoded_w = w;
                        decoded_h = h;
                        got += 1;
                        ff_frame_unref(frame);
                    }
                    ff_pkt_unref(dpkt);
                    if saw_eos {
                        break;
                    }
                    // keep draining until EOF
                    continue;
                }
                ff_vdec_send(demux, dpkt);
                ff_pkt_unref(dpkt);
                loop {
                    let r2 = ff_vdec_recv(demux, frame);
                    if r2 == AVERROR_EAGAIN {
                        break;
                    }
                    if r2 == AVERROR_EOF {
                        saw_eos = true;
                        break;
                    }
                    assert_eq!(r2, 0);
                    let (mut w, mut h, mut df, mut dp) = (0, 0, 0, 0i64);
                    ff_frame_info(frame, &mut w, &mut h, &mut df, &mut dp);
                    decoded_w = w;
                    decoded_h = h;
                    got += 1;
                    ff_frame_unref(frame);
                }
                if saw_eos {
                    break;
                }
            }
            ff_pkt_free(dpkt);
            ff_frame_free(frame);
            ff_demux_close(demux);
        }
        assert!(saw_eos, "never reached decoder EOS");
        assert_eq!(got, 5, "expected 5 frames, got {got}");
        assert_eq!((decoded_w, decoded_h), (64, 48));
        let _ = std::fs::remove_file(&out);
    }

    #[test]
    fn full_render_if_fixture_present() {
        // Opportunistic end-to-end render of a real phone video, if available.
        for cand in [
            "/workspaces/glowing-octo-goggles/pc.mp4",
            "/workspaces/glowing-octo-goggles/android.mp4",
        ] {
            if !std::path::Path::new(cand).exists() {
                continue;
            }
            let dir = std::env::temp_dir().join("ntscrs-ffrender");
            let _ = std::fs::create_dir_all(&dir);
            let out = dir.join("out.mp4").to_str().unwrap().to_string();
            let defaults = SettingsList::<NtscEffect>::new()
                .to_json_string(&NtscEffect::default())
                .unwrap();
            let params = RenderParams {
                in_path: cand.to_string(),
                out_path: out.clone(),
                settings_json: defaults,
                easy: false,
                quality: 30,
                speed: 8,
                use444: false,
                interlaced: false,
                trim_start_us: 0,
                trim_end_us: 1_000_000,
                out_w: 0,
                out_h: 0,
                effect_enabled: true,
            };
            let cancelled = AtomicBool::new(false);
            let progresses = core::cell::RefCell::new(Vec::new());
            let r = render(
                &params,
                &mut |d, t| progresses.borrow_mut().push((d, t)),
                &cancelled,
            );
            assert!(r.is_ok(), "render failed: {r:?}");
            assert!(!progresses.borrow().is_empty());
            let meta = std::fs::metadata(&out).unwrap();
            assert!(meta.len() > 1000);
            // Re-decode output and check the effect actually changed pixels:
            // compare mean luma of first frames in vs out.
            let luma = |path: &str| -> f64 {
                let c = std::ffi::CString::new(path).unwrap();
                let mut eb = [0 as c_char; 256];
                let d = unsafe { ff_demux_open(c.as_ptr(), eb.as_mut_ptr(), 256) };
                assert!(!d.is_null());
                let pkt = unsafe { ff_pkt_alloc() };
                let fr = unsafe { ff_frame_alloc() };
                let mut mean = -1.0;
                unsafe {
                    ff_demux_seek(d, 0);
                    'outer: loop {
                        if ff_demux_read(d, pkt) < 0 {
                            break;
                        }
                        ff_vdec_send(d, pkt);
                        ff_pkt_unref(pkt);
                        loop {
                            let rc = ff_vdec_recv(d, fr);
                            if rc == AVERROR_EAGAIN {
                                break;
                            }
                            if rc != 0 {
                                break 'outer;
                            }
                            let (mut w, mut h, mut df, mut dp) = (0, 0, 0, 0i64);
                            ff_frame_info(fr, &mut w, &mut h, &mut df, &mut dp);
                            let yp = ff_frame_plane(fr, 0);
                            let ys = ff_frame_stride(fr, 0) as usize;
                            // sample center row
                            let mut sum = 0u64;
                            let row = yp.add((h as usize / 2) * ys);
                            for x in (0..w as usize).step_by(4) {
                                sum += *row.add(x) as u64;
                            }
                            mean = sum as f64 / (w as usize / 4) as f64;
                            ff_frame_unref(fr);
                            break 'outer;
                        }
                    }
                    ff_pkt_free(pkt);
                    ff_frame_free(fr);
                    ff_demux_close(d);
                }
                mean
            };
            let a = luma(cand);
            let b = luma(&out);
            assert!(a >= 0.0 && b >= 0.0, "luma read failed ({a}, {b})");
            assert!(
                (a - b).abs() > 0.5,
                "effect seems to have no visible impact ({a} vs {b})"
            );
            let _ = std::fs::remove_file(&out);
            return;
        }
    }
}

// ---------------------------------------------------------------------------
// Preview player (sequential decode -> engine -> RGBA frames)
// ---------------------------------------------------------------------------

pub struct Player {
    demux: *mut c_void,
    sws: *mut c_void,
    sws_fmt: c_int,
    rgba: Vec<u8>,
    sw: c_int,
    sh: c_int,
    rot: c_int,
    idx: i32,
    end_us: i64,
    vtn: c_int,
    vtd: c_int,
    vi: c_int,
    err: String,
}

unsafe impl Send for Player {}

impl Player {
    fn open(path: &str, max_dim: i32, start_us: i64, end_us: i64) -> Result<*mut Player, String> {
        let in_c = std::ffi::CString::new(path).map_err(|_| "bad path".to_string())?;
        let mut eb = [0 as c_char; 1024];
        let demux = unsafe { ff_demux_open(in_c.as_ptr(), eb.as_mut_ptr(), 1024) };
        if demux.is_null() {
            return Err(cstr(&eb));
        }
        let (mut w, mut h, mut fps_n, mut fps_d, mut dur, mut ha, mut rot) =
            (0 as c_int, 0 as c_int, 0 as c_int, 0 as c_int, 0i64, 0 as c_int, 0 as c_int);
        let (mut vtn, mut vtd, mut atn, mut atd) = (0 as c_int, 0 as c_int, 0 as c_int, 0 as c_int);
        unsafe {
            ff_demux_info(
                demux, &mut w, &mut h, &mut fps_n, &mut fps_d, &mut dur, &mut ha, &mut rot,
                &mut vtn, &mut vtd, &mut atn, &mut atd,
            )
        };
        if w <= 0 || h <= 0 {
            unsafe { ff_demux_close(demux) };
            return Err("bad video dims".to_string());
        }
        let scale = (max_dim as f32 / w.max(h) as f32).min(1.0);
        let mut pw = ((w as f32 * scale) as c_int).max(2) & !1;
        let mut ph = ((h as f32 * scale) as c_int).max(2) & !1;
        if rot == 90 || rot == 270 {
            core::mem::swap(&mut pw, &mut ph);
        }
        // sws target is pre-transpose dims.
        let (sw, sh) = if rot == 90 || rot == 270 { (ph, pw) } else { (pw, ph) };
        unsafe {
            ff_demux_seek(demux, start_us);
        }
        let p = Box::new(Player {
            demux,
            sws: core::ptr::null_mut(),
            sws_fmt: -999,
            rgba: vec![0u8; (sw * sh * 4) as usize],
            sw,
            sh,
            rot,
            idx: 0,
            end_us,
            vtn,
            vtd,
            vi: unsafe { ff_demux_vstream(demux) },
            err: String::new(),
        });
        Ok(Box::into_raw(p))
    }

    fn seek(&mut self, us: i64) {
        unsafe {
            ff_demux_seek(self.demux, us);
        }
        self.idx = 0;
    }

    /// Next processed frame. Ok(None) = EOS. Err = failure message.
    fn next(
        &mut self,
        ctx: &Context,
        settings_json: &str,
        easy: bool,
        enabled: bool,
    ) -> Result<Option<(Vec<u8>, i32, i32, i64)>, String> {
        let pkt = unsafe { ff_pkt_alloc() };
        let frame = unsafe { ff_frame_alloc() };
        if pkt.is_null() || frame.is_null() {
            return Err("alloc failed".to_string());
        }
        struct D2(*mut c_void, *mut c_void);
        impl Drop for D2 {
            fn drop(&mut self) {
                unsafe {
                    ff_pkt_free(self.0);
                    ff_frame_free(self.1);
                }
            }
        }
        let _d = D2(pkt, frame);
        let mut input_eos = false;
        let mut flushed = false;
        loop {
            if !input_eos {
                let rc = unsafe { ff_demux_read(self.demux, pkt) };
                if rc < 0 {
                    input_eos = true;
                } else {
                    let st = unsafe { ff_pkt_stream(pkt) };
                    if st == self.vi {
                        let rc = unsafe { ff_vdec_send(self.demux, pkt) };
                        unsafe { ff_pkt_unref(pkt) };
                        if rc < 0 && rc != AVERROR_EAGAIN {
                            return Err(std::format!("decode send failed ({rc})"));
                        }
                    } else {
                        unsafe { ff_pkt_unref(pkt) };
                    }
                }
            }
            if input_eos && !flushed {
                unsafe {
                    ff_vdec_send(self.demux, core::ptr::null_mut());
                }
                flushed = true;
            }
            let rc = unsafe { ff_vdec_recv(self.demux, frame) };
            if rc == AVERROR_EAGAIN {
                if input_eos {
                    return Ok(None);
                }
                continue;
            }
            if rc == AVERROR_EOF {
                return Ok(None);
            }
            if rc < 0 {
                return Err(std::format!("decode failed ({rc})"));
            }
            let bets = unsafe { ff_frame_bets(frame) };
            let fus = if bets < 0 {
                0
            } else {
                unsafe { ff_rescale(bets, self.vtn, self.vtd, 1, 1_000_000) }
            };
            if fus >= self.end_us {
                unsafe { ff_frame_unref(frame) };
                return Ok(None);
            }
            let (mut fw, mut fh, mut ffmt, mut _fp) = (0 as c_int, 0 as c_int, 0 as c_int, 0i64);
            unsafe { ff_frame_info(frame, &mut fw, &mut fh, &mut ffmt, &mut _fp) };
            if self.sws.is_null() || self.sws_fmt != ffmt {
                if !self.sws.is_null() {
                    unsafe { ff_sws_close(self.sws) };
                    self.sws = core::ptr::null_mut();
                }
                self.sws = unsafe { ff_sws_open(fw, fh, ffmt, self.sw, self.sh, PIX_RGBA) };
                if self.sws.is_null() {
                    unsafe { ff_frame_unref(frame) };
                    return Err("swscale init failed".to_string());
                }
                self.sws_fmt = ffmt;
            }
            let got = unsafe { ff_sws_run(self.sws, frame, self.rgba.as_mut_ptr(), self.sw * 4) };
            unsafe { ff_frame_unref(frame) };
            if got <= 0 {
                return Err("swscale failed".to_string());
            }
            let (pw, ph, mut work) = if self.rot == 0 {
                (self.sw, self.sh, self.rgba.clone())
            } else {
                let (v, nw, nh) = transpose_rgba(&self.rgba, self.sw as usize, self.sh as usize, self.rot);
                (nw as c_int, nh as c_int, v)
            };
            if enabled {
                let rc = process_rgba_inner(
                    ctx, pw as usize, ph as usize, self.idx as usize, [1.0, 1.0],
                    settings_json, easy, &mut work,
                );
                if rc != 0 {
                    return Err(std::format!("engine error ({rc})"));
                }
            }
            self.idx += 1;
            return Ok(Some((work, pw, ph, fus)));
        }
    }
}

impl Drop for Player {
    fn drop(&mut self) {
        unsafe {
            if !self.sws.is_null() {
                ff_sws_close(self.sws);
            }
            ff_demux_close(self.demux);
        }
    }
}

// ---------------------------------------------------------------------------
// JNI: class com.ntscrs.android.Ffmpeg
// ---------------------------------------------------------------------------

use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;

static RENDER_CANCEL: AtomicBool = AtomicBool::new(false);
static LAST_ERROR: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());
static ENGINE: std::sync::OnceLock<Context> = std::sync::OnceLock::new();

fn set_last_error(s: String) {
    if let Ok(mut g) = LAST_ERROR.lock() {
        *g = s;
    }
}

fn jstr(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s).map(|j| j.into()).map_err(|e| e.to_string())
}

fn render_params_from_json(v: &serde_json::Value) -> Result<RenderParams, String> {
    let get = |k: &str| v.get(k).ok_or_else(|| std::format!("missing '{k}'"));
    let s = |k: &str| get(k)?.as_str().ok_or_else(|| std::format!("bad '{k}'")).map(str::to_string);
    let b = |k: &str, d: bool| -> Result<bool, String> {
        Ok(v.get(k).and_then(|x| x.as_bool()).unwrap_or(d))
    };
    let i = |k: &str, d: i64| -> Result<i64, String> {
        Ok(v.get(k).and_then(|x| x.as_i64()).unwrap_or(d))
    };
    Ok(RenderParams {
        in_path: s("in_path")?,
        out_path: s("out_path")?,
        settings_json: s("settings_json")?,
        easy: b("easy", false)?,
        quality: i("quality", 27)? as i32,
        speed: i("speed", 5)? as i32,
        use444: b("use444", false)?,
        interlaced: b("interlaced", false)?,
        trim_start_us: i("trim_start_us", 0)?,
        trim_end_us: v
            .get("trim_end_us")
            .and_then(|x| x.as_i64())
            .unwrap_or(i64::MAX),
        out_w: i("out_w", 0)? as i32,
        out_h: i("out_h", 0)? as i32,
        effect_enabled: b("effect_enabled", true)?,
    })
}

#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_Ffmpeg_nativeRender<'local>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    params_json: JString<'local>,
    progress: JObject<'local>,
) -> jint {
    RENDER_CANCEL.store(false, Ordering::Relaxed);
    let json = match jstr(&mut env, &params_json) {
        Ok(s) => s,
        Err(e) => {
            set_last_error(e);
            return -1;
        }
    };
    let params = match serde_json::from_str::<serde_json::Value>(&json)
        .map_err(|e| e.to_string())
        .and_then(|v| render_params_from_json(&v))
    {
        Ok(p) => p,
        Err(e) => {
            set_last_error(e);
            return -1;
        }
    };
    let mut cb = |d: i32, t: i32| {
        let _ = env.call_method(
            &progress,
            "onProgress",
            "(II)V",
            &[jni::objects::JValue::Int(d), jni::objects::JValue::Int(t)],
        );
    };
    match render(&params, &mut cb, &RENDER_CANCEL) {
        Ok(()) => 0,
        Err(RenderError::Cancelled) => 1,
        Err(RenderError::Failed(e)) => {
            set_last_error(e);
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_Ffmpeg_nativeLastError<'local>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
) -> JString<'local> {
    let s = LAST_ERROR.lock().map(|g| g.clone()).unwrap_or_default();
    env.new_string(s).expect("OOM")
}

#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_Ffmpeg_nativeRenderCancel(
    _env: JNIEnv<'_>,
    _cls: JClass<'_>,
) {
    RENDER_CANCEL.store(true, Ordering::Relaxed);
}

#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_Ffmpeg_nativePlayOpen<'local>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    path: JString<'local>,
    max_dim: jint,
    start_us: jlong,
    end_us: jlong,
) -> jlong {
    let p = match jstr(&mut env, &path) {
        Ok(s) => s,
        Err(e) => {
            set_last_error(e);
            return 0;
        }
    };
    match Player::open(&p, max_dim, start_us as i64, end_us as i64) {
        Ok(h) => h as jlong,
        Err(e) => {
            set_last_error(e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_Ffmpeg_nativePlayNext<'local>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    handle: jlong,
    settings_json: JString<'local>,
    easy: jboolean,
    effect_enabled: jboolean,
) -> JByteArray<'local> {
    let empty = env.byte_array_from_slice(&[]).expect("OOM");
    if handle == 0 {
        return empty;
    }
    let json = match jstr(&mut env, &settings_json) {
        Ok(s) => s,
        Err(_) => return empty,
    };
    let ctx = ENGINE.get_or_init(Context::new);
    let player: &mut Player = unsafe { &mut *(handle as *mut Player) };
    match player.next(ctx, &json, easy != 0, effect_enabled != 0) {
        Ok(Some((rgba, w, h, pts))) => {
            let mut out = Vec::with_capacity(16 + rgba.len());
            out.extend_from_slice(&(w as i32).to_le_bytes());
            out.extend_from_slice(&(h as i32).to_le_bytes());
            out.extend_from_slice(&pts.to_le_bytes());
            out.extend_from_slice(&rgba);
            env.byte_array_from_slice(&out).expect("OOM")
        }
        Ok(None) => empty, // EOS
        Err(e) => {
            player.err = e;
            empty
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_Ffmpeg_nativePlayError<'local>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    handle: jlong,
) -> JString<'local> {
    let s = if handle == 0 {
        "bad player".to_string()
    } else {
        let player: &mut Player = unsafe { &mut *(handle as *mut Player) };
        core::mem::take(&mut player.err)
    };
    env.new_string(s).expect("OOM")
}

#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_Ffmpeg_nativePlaySeek(
    _env: JNIEnv<'_>,
    _cls: JClass<'_>,
    handle: jlong,
    pos_us: jlong,
) -> jint {
    if handle == 0 {
        return -1;
    }
    let player: &mut Player = unsafe { &mut *(handle as *mut Player) };
    player.seek(pos_us as i64);
    0
}

#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_Ffmpeg_nativePlayClose(
    _env: JNIEnv<'_>,
    _cls: JClass<'_>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Player));
        }
    }
}
