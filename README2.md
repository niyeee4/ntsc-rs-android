# ntsc-rs for Android

An Android port of [ntsc-rs](https://github.com/ntsc-rs/ntsc-rs) — the NTSC/VHS
video-artifact effect — with the same engine, all settings (Easy + Advanced),
presets, image export (PNG/JPEG) and H.264 video rendering.

## How it works

- **Effect engine, 100% unchanged**: `rust/vendor/ntscrs/` is the upstream
  `ntsc-rs` image-processing crate, used as-is (every NTSC/VHS pass, every
  setting). Only the desktop GUI (eframe + GStreamer) is replaced:
  - Settings UI → Jetpack Compose (`app/src/main/…`), generated dynamically
    from the engine's own setting descriptors
  - Video decode → FFmpeg (software decode everywhere, so every device
    behaves identically)
  - Video encode → **libx264** (same encoder + same mapping as desktop:
    constant-quantizer `QP = 50 − quality`, presets veryslow…ultrafast)
  - Mux → FFmpeg MP4 (`avcC` is validated; audio is copied from the source)
  - All native media code lives in `rust/ntscrs-android` (`ffshim.c`,
    `ffbridge.rs`) behind a small JNI surface (`Ffmpeg`, `NtscBridge`)
- Preset files are byte-compatible with desktop ntsc-rs-standalone.

## Building from source

Requirements: JDK 17+, Android SDK (platform 34, build-tools 34, NDK r27),
Rust with the `aarch64/armv7/x86_64/i686-linux-android` targets, `cargo-ndk`,
`git`, `curl`, `pkg-config`.

```bash
# 1. Static libs (downloads pinned upstream sources automatically)
./rust/build-x264.sh android   # libx264 for 4 ABIs (-> rust/x264/<abi>)
./rust/build-ffmpeg.sh android # FFmpeg+libx264 for 4 ABIs (-> rust/ffmpeg/<abi>)

# 2. JNI libs (also runs the Rust unit tests, incl. a full engine+x264
#    roundtrip plus an end-to-end render when a fixture video is present)
./rust/build-android.sh

# 3. APK (reads $ANDROID_HOME or local.properties for the SDK location)
./gradlew :app:assembleDebug      # debug-signed, installs immediately
./gradlew :app:assembleRelease    # unsigned release; sign it with YOUR key:
# apksigner sign --ks <your>.keystore --out app-release-signed.apk \
#   app/build/outputs/apk/release/app-release-unsigned.apk
```

`cargo test -p ntscrs-android` runs the engine/ffmpeg unit tests on the host
(requires `./rust/build-x264.sh host && ./rust/build-ffmpeg.sh host` first).

## License map

- **New code in this repo** (Android app, JNI bridge, C shims, build scripts):
  **GPL-2.0-or-later** — see [LICENSE](LICENSE).
- **Effect engine** (`rust/vendor/ntscrs`): upstream code, unchanged, under
  **MIT OR ISC OR Apache-2.0** — see LICENSE-MIT, LICENSE-ISC,
  LICENSE-APACHE-2.0. (Upstream's desktop GUI, GPL-3.0, is *not* included.)
- **libx264** (`rust/x264`, built from code.videolan.org, commit `0480cb0`):
  **GPL-2.0** — see rust/x264/COPYING.
- **FFmpeg 7.1.2** (`rust/ffmpeg`, built with `--enable-gpl --enable-libx264`):
  **GPL-2.0+** — see rust/ffmpeg/COPYING.GPLv2, rust/ffmpeg/SOURCE_VERSION.txt.

Because of libx264/FFmpeg, binaries built from this repo are GPL-2.0+ works.
The `rust/build-*.sh` scripts fetch the exact pinned upstream sources, so the
complete corresponding source is this repository plus those scripts.
