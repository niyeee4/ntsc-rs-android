/* SPDX-License-Identifier: GPL-2.0-or-later */
//! Android JNI bridge for ntsc-rs.
//!
//! This crate exposes the **entire** ntsc-rs image-processing core
//! (every setting of [`NtscEffect`] and [`EasyMode`]) to Kotlin/Java via JNI.
//!
//! Pixel contract: RGBA bytes (`Rgbx` + `u8`), row-major, no padding,
//! exactly `width * height * 4` bytes. This matches Android
//! `Bitmap.Config.ARGB_8888` bytes after an R/B-independent transfer
//! (the Kotlin side converts ARGB ints <-> RGBA bytes explicitly, so there
//! is no endianness ambiguity).
//!
//! Settings contract: JSON strings in the exact same format as the desktop
//! `ntsc-rs-standalone` app (`SettingsList::to_json_string` /
//! `from_json_generic`), so presets are interchangeable with desktop.
//!
//! Core engine: vendored from https://github.com/ntsc-rs/ntsc-rs
//! (`rust/vendor/ntscrs`), license MIT OR ISC OR Apache-2.0 (see
//! `rust/vendor/LICENSE-*`). The GUI layer (eframe + GStreamer) is desktop
//! only and is replaced on Android by Kotlin (CameraX/MediaCodec/MediaMuxer
//! + Jetpack Compose); the filter itself is byte-for-byte the same code.

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jfloat, jint, jlong};
use jni::JNIEnv;
use ntsc_rs::settings::easy::EasyMode;
use ntsc_rs::settings::{SettingKind, Settings, SettingsList};
use ntsc_rs::yiq_fielding::Rgbx;
use ntsc_rs::{Context, NtscEffect};
use std::fmt::Write as _;
use std::panic::{catch_unwind, AssertUnwindSafe};

/// Error codes returned by [`process_rgba_inner`].
const ERR_OK: jint = 0;
const ERR_BAD_JSON: jint = -1;
const ERR_BAD_PIXELS: jint = -2;
const ERR_BAD_DIMS: jint = -3;
const ERR_PANIC: jint = -4;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn jstring_to_rust(env: &mut JNIEnv, s: &JString) -> Result<String, jni::errors::Error> {
    env.get_string(s).map(|j| j.into())
}

fn escape_json_into(dst: &mut String, s: &str) {
    for c in s.chars() {
        match c {
            '"' => dst.push_str("\\\""),
            '\\' => dst.push_str("\\\\"),
            '\n' => dst.push_str("\\n"),
            '\r' => dst.push_str("\\r"),
            '\t' => dst.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                let _ = write!(dst, "\\u{:04x}", c as u32);
            }
            c => dst.push(c),
        }
    }
}

fn opt_str(dst: &mut String, s: Option<&'static str>) {
    match s {
        Some(v) => {
            dst.push('"');
            escape_json_into(dst, v);
            dst.push('"');
        }
        None => dst.push_str("null"),
    }
}

fn append_descriptors<T: Settings>(dst: &mut String, descriptors: &[ntsc_rs::settings::SettingDescriptor<T>]) {
    dst.push('[');
    let mut first = true;
    for d in descriptors {
        if !first {
            dst.push(',');
        }
        first = false;
        dst.push_str("{\"name\":\"");
        escape_json_into(dst, d.id.name);
        let _ = write!(dst, "\",\"id\":{},\"label\":\"", d.id.id);
        escape_json_into(dst, d.label);
        dst.push_str("\",\"description\":");
        opt_str(dst, d.description);
        dst.push_str(",\"kind\":\"");
        match &d.kind {
            SettingKind::Enumeration { options } => {
                dst.push_str("enum\",\"options\":[");
                for (i, o) in options.iter().enumerate() {
                    if i > 0 {
                        dst.push(',');
                    }
                    dst.push_str("{\"label\":\"");
                    escape_json_into(dst, o.label);
                    dst.push_str("\",\"description\":");
                    opt_str(dst, o.description);
                    let _ = write!(dst, ",\"index\":{}}}", o.index);
                }
                dst.push(']');
            }
            SettingKind::Percentage { logarithmic } => {
                let _ = write!(dst, "percentage\",\"logarithmic\":{logarithmic}");
            }
            SettingKind::IntRange { range } => {
                let _ = write!(
                    dst,
                    "int\",\"min\":{},\"max\":{}",
                    range.start(),
                    range.end()
                );
            }
            SettingKind::FloatRange { range, logarithmic } => {
                let _ = write!(
                    dst,
                    "float\",\"min\":{},\"max\":{},\"logarithmic\":{logarithmic}",
                    range.start(),
                    range.end()
                );
            }
            SettingKind::Boolean => {
                dst.push_str("bool\"");
            }
            SettingKind::Group { children } => {
                dst.push_str("group\",\"children\":");
                append_descriptors(dst, children);
            }
        }
        dst.push('}');
    }
    dst.push(']');
}

fn descriptors_json(easy: bool) -> String {
    let mut out = String::with_capacity(16 * 1024);
    if easy {
        let list = SettingsList::<EasyMode>::new();
        append_descriptors(&mut out, &list.setting_descriptors);
    } else {
        let list = SettingsList::<NtscEffect>::new();
        append_descriptors(&mut out, &list.setting_descriptors);
    }
    out
}

fn default_settings_json(easy: bool) -> Result<String, String> {
    if easy {
        SettingsList::<EasyMode>::new()
            .to_json_string(&EasyMode::default())
            .map_err(|e| e.to_string())
    } else {
        SettingsList::<NtscEffect>::new()
            .to_json_string(&NtscEffect::default())
            .map_err(|e| e.to_string())
    }
}

pub(crate) fn process_rgba_inner(
    ctx: &Context,
    width: usize,
    height: usize,
    frame_num: usize,
    scale: [f32; 2],
    settings_json: &str,
    easy: bool,
    pixels: &mut [u8],
) -> jint {
    if width == 0 || height == 0 {
        return ERR_BAD_DIMS;
    }
    if pixels.len() != width.checked_mul(height).unwrap_or(0).saturating_mul(4) {
        return ERR_BAD_PIXELS;
    }
    let effect: NtscEffect = if easy {
        match SettingsList::<EasyMode>::new().from_json_generic(settings_json) {
            Ok(ez) => NtscEffect::from(&ez),
            Err(_) => return ERR_BAD_JSON,
        }
    } else {
        match SettingsList::<NtscEffect>::new().from_json_generic(settings_json) {
            Ok(e) => e,
            Err(_) => return ERR_BAD_JSON,
        }
    };
    let r = catch_unwind(AssertUnwindSafe(|| {
        effect.apply_effect_to_buffer::<Rgbx, u8>(
            ctx,
            (width, height),
            pixels,
            frame_num,
            scale,
        );
    }));
    if r.is_err() {
        return ERR_PANIC;
    }
    ERR_OK
}

// ---------------------------------------------------------------------------
// JNI entry points (class: com.ntscrs.android.NtscBridge)
// ---------------------------------------------------------------------------

/// Create a long-lived filter [`Context`]. Returns an opaque handle.
#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_NtscBridge_nativeCreate(
    _env: JNIEnv<'_>,
    _cls: JClass<'_>,
) -> jlong {
    let ctx = Box::new(Context::new());
    Box::into_raw(ctx) as jlong
}

/// Destroy a [`Context`] created by `nativeCreate`.
#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_NtscBridge_nativeDestroy(
    _env: JNIEnv<'_>,
    _cls: JClass<'_>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Context));
        }
    }
}

/// Default settings JSON (`easy` selects EasyMode vs full NtscEffect).
#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_NtscBridge_nativeDefaultSettingsJson<'local>(
    env: JNIEnv<'local>,
    _cls: JClass<'local>,
    easy: jboolean,
) -> JString<'local> {
    let json = default_settings_json(easy != 0).unwrap_or_else(|e| e);
    env.new_string(json).unwrap_or_else(|_| {
        env.new_string(String::new()).expect("OOM creating empty string")
    })
}

/// Convert EasyMode JSON to full NtscEffect JSON (same mapping as desktop).
#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_NtscBridge_nativeEasyToFull<'local>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    easy_json: JString<'local>,
) -> JString<'local> {
    let out = (|| -> Result<String, String> {
        let s = jstring_to_rust(&mut env, &easy_json).map_err(|e| e.to_string())?;
        let ez = SettingsList::<EasyMode>::new()
            .from_json_generic(&s)
            .map_err(|e| e.to_string())?;
        let full = NtscEffect::from(&ez);
        SettingsList::<NtscEffect>::new()
            .to_json_string(&full)
            .map_err(|e| e.to_string())
    })();
    let json = out.unwrap_or_default();
    env.new_string(json).unwrap_or_else(|_| {
        env.new_string(String::new()).expect("OOM creating empty string")
    })
}

/// Full setting-descriptor tree as JSON for building the settings UI
/// dynamically (covers 100% of settings, both modes).
#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_NtscBridge_nativeDescriptorsJson<'local>(
    env: JNIEnv<'local>,
    _cls: JClass<'local>,
    easy: jboolean,
) -> JString<'local> {
    let json = descriptors_json(easy != 0);
    env.new_string(json).unwrap_or_else(|_| {
        env.new_string(String::new()).expect("OOM creating empty string")
    })
}

/// Process an RGBA frame in place. Returns 0 on success, negative on error.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_NtscBridge_nativeProcessRgba<'local>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    handle: jlong,
    width: jint,
    height: jint,
    frame_num: jint,
    scale_x: jfloat,
    scale_y: jfloat,
    settings_json: JString<'local>,
    easy: jboolean,
    pixels: JByteArray<'local>,
) -> jint {
    if handle == 0 {
        return ERR_PANIC;
    }
    let ctx: &Context = unsafe { &*(handle as *const Context) };
    let settings = match jstring_to_rust(&mut env, &settings_json) {
        Ok(s) => s,
        Err(_) => return ERR_BAD_JSON,
    };
    let mut buf: Vec<u8> = match env.convert_byte_array(&pixels) {
        Ok(b) => b,
        Err(_) => return ERR_BAD_PIXELS,
    };
    let code = process_rgba_inner(
        ctx,
        width as usize,
        height as usize,
        frame_num as usize,
        [scale_x, scale_y],
        &settings,
        easy != 0,
        &mut buf,
    );
    if code == ERR_OK {
        if env.set_byte_array_region(&pixels, 0, bytemuck_cast(&buf)).is_err() {
            return ERR_PANIC;
        }
    }
    code
}

/// Engine version string.
#[no_mangle]
pub extern "system" fn Java_com_ntscrs_android_NtscBridge_nativeVersion<'local>(
    env: JNIEnv<'local>,
    _cls: JClass<'local>,
) -> JString<'local> {
    env.new_string(env!("CARGO_PKG_VERSION"))
        .unwrap_or_else(|_| env.new_string(String::new()).expect("OOM"))
}

fn bytemuck_cast(buf: &[u8]) -> &[i8] {
    // jbyte is i8; same width, bitwise copy.
    unsafe { std::slice::from_raw_parts(buf.as_ptr() as *const i8, buf.len()) }
}

mod x264;
mod ffbridge;

pub(crate) use ffbridge::{RenderError as _, RenderParams as _};

#[cfg(test)]
mod tests {
    use super::*;

    fn gradient_rgba(w: usize, h: usize) -> Vec<u8> {
        let mut v = Vec::with_capacity(w * h * 4);
        for y in 0..h {
            for x in 0..w {
                v.push((x * 255 / w.max(1)) as u8);
                v.push((y * 255 / h.max(1)) as u8);
                v.push(128u8);
                v.push(255u8);
            }
        }
        v
    }

    #[test]
    fn default_json_round_trips_full() {
        let json = default_settings_json(false).expect("default full json");
        assert!(json.contains("\"version\":1"));
        let effect = SettingsList::<NtscEffect>::new()
            .from_json_generic(&json)
            .expect("parse back");
        assert_eq!(effect, NtscEffect::default());
    }

    #[test]
    fn default_json_round_trips_easy_and_converts() {
        let json = default_settings_json(true).expect("default easy json");
        let ez = SettingsList::<EasyMode>::new()
            .from_json_generic(&json)
            .expect("parse back");
        assert_eq!(ez, EasyMode::default());
        let full = NtscEffect::from(&ez);
        // Must serialize as valid full settings too.
        let s = SettingsList::<NtscEffect>::new()
            .to_json_string(&full)
            .expect("serialize converted");
        SettingsList::<NtscEffect>::new()
            .from_json_generic(&s)
            .expect("parse converted");
    }

    #[test]
    fn descriptors_cover_all_settings() {
        for easy in [false, true] {
            let d = descriptors_json(easy);
            assert!(d.starts_with('['), "must be JSON array");
            // Spot-check flagship settings exist in both modes.
            assert!(d.contains("random_seed"), "easy={easy}");
            assert!(d.contains("use_field"), "easy={easy}");
            if !easy {
                // Full mode must expose all ~62 setting IDs incl. deep groups.
                for key in [
                    "head_switching_height",
                    "tracking_noise_height",
                    "ringing_frequency",
                    "vhs_tape_speed",
                    "chroma_demodulation",
                    "bandwidth_scale",
                    "scale_with_video_size",
                    "composite_noise",
                    "luma_noise_detail",
                ] {
                    assert!(d.contains(key), "missing {key}");
                }
            }
        }
    }

    #[test]
    fn process_rgba_changes_pixels_full_and_easy() {
        let ctx = Context::new();
        for easy in [false, true] {
            let json = default_settings_json(easy).unwrap();
            let (w, h) = (64usize, 48usize);
            let mut px = gradient_rgba(w, h);
            let before = px.clone();
            let code = process_rgba_inner(&ctx, w, h, 0, [1.0, 1.0], &json, easy, &mut px);
            assert_eq!(code, ERR_OK, "easy={easy}");
            assert_eq!(px.len(), before.len());
            assert_ne!(px, before, "effect must alter pixels (easy={easy})");
            // Alpha channel must survive.
            for chunk in px.chunks_exact(4) {
                assert_eq!(chunk[3], 255);
            }
        }
    }

    #[test]
    fn process_rgba_rejects_garbage() {
        let ctx = Context::new();
        let json = default_settings_json(false).unwrap();
        let mut px = gradient_rgba(8, 8);
        assert_eq!(
            process_rgba_inner(&ctx, 0, 8, 0, [1.0, 1.0], &json, false, &mut px),
            ERR_BAD_DIMS
        );
        assert_eq!(
            process_rgba_inner(&ctx, 8, 8, 0, [1.0, 1.0], &json, false, &mut px[..10]),
            ERR_BAD_PIXELS
        );
        assert_eq!(
            process_rgba_inner(&ctx, 8, 8, 0, [1.0, 1.0], "not json", false, &mut px),
            ERR_BAD_JSON
        );
    }
}
