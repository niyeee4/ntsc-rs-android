#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-or-later
# Rebuild libntscrs_android.so for all Android ABIs and copy into jniLibs.
# Requires: rustup targets aarch64/armv7/x86_64/i686-linux-android,
#           Android NDK (ANDROID_NDK_HOME or $ANDROID_HOME/ndk/<ver>), cargo-ndk.
set -euo pipefail
cd "$(dirname "$0")"

export PATH="$HOME/.cargo/bin:$PATH"
: "${ANDROID_HOME:=/opt/android-sdk}"
: "${ANDROID_NDK_HOME:=$ANDROID_HOME/ndk/27.2.12479018}"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME" ANDROID_NDK_HOME

# Host sanity: engine unit tests (same code the phone runs).
cargo test -p ntscrs-android

for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android; do
  cargo ndk --target "$target" --platform 26 -- build --release -p ntscrs-android
done

copy() { cp -f "target/$1/release/libntscrs_android.so" "../app/src/main/jniLibs/$2/libntscrs_android.so"; }
copy aarch64-linux-android arm64-v8a
copy armv7-linux-androideabi armeabi-v7a
copy x86_64-linux-android x86_64
copy i686-linux-android x86
echo "jniLibs updated."
