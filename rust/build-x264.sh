#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-or-later
# Build static libx264 for host + 4 Android ABIs (8-bit+10-bit combined lib).
# Source: https://code.videolan.org/videolan/x264.git (commit pinned below).
# Output: rust/x264/<abi>/{include,lib}/ (libx264.a + headers + x264.pc).
# Requires: Android NDK (ANDROID_NDK_HOME or $ANDROID_HOME/ndk/<ver>), git.
set -euo pipefail

X264_COMMIT="0480cb0"
SRC_DIR="${X264_SRC:-/tmp/x264}"
OUT="$(cd "$(dirname "$0")" && pwd)/x264"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-/opt/android-sdk}/ndk/27.2.12479018}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TC/sysroot"
API=26

if [ ! -d "$SRC_DIR" ]; then
  echo "Cloning x264..."
  git clone https://code.videolan.org/videolan/x264.git "$SRC_DIR"
fi
(cd "$SRC_DIR" && git fetch --depth 1 origin "$X264_COMMIT" 2>/dev/null || true)

build_one() {
  local abi=$1 host=$2 triple=$3 extra_cflags=$4 extra_cfg=$5
  echo "=== building x264 for $abi ==="
  local prefix=$OUT/$abi
  local builddir
  builddir=$(mktemp -d)
  cp -r "$SRC_DIR" "$builddir/src"
  pushd "$builddir/src" >/dev/null
  export PATH=$TC/bin:$PATH
  export CC=$TC/bin/${triple}${API}-clang
  export AS=$CC
  export AR=$TC/bin/llvm-ar
  export RANLIB=$TC/bin/llvm-ranlib
  export STRIP=$TC/bin/llvm-strip
  export STRINGS=$TC/bin/llvm-strings
  ./configure --prefix=$prefix \
    --host=$host \
    --sysroot=$SYSROOT \
    --cross-prefix=$TC/bin/${triple}- \
    --enable-static --enable-pic \
    --disable-cli --disable-opencl --disable-avs --disable-swscale \
    --disable-lavf --disable-ffms --disable-gpac --disable-lsmash \
    --extra-cflags="$extra_cflags" \
    $extra_cfg
  make -j$(nproc)
  make install
  popd >/dev/null
  rm -rf "$builddir"
  ls -la $prefix/lib/libx264.a
}

build_host() {
  echo "=== building x264 for host ==="
  local prefix=$OUT/host
  local builddir
  builddir=$(mktemp -d)
  cp -r "$SRC_DIR" "$builddir/src"
  pushd "$builddir/src" >/dev/null
  ./configure --prefix=$prefix \
    --enable-static --enable-pic \
    --disable-cli --disable-opencl --disable-avs --disable-swscale \
    --disable-lavf --disable-ffms --disable-gpac --disable-lsmash \
    --disable-asm
  make -j$(nproc)
  make install
  popd >/dev/null
  rm -rf "$builddir"
  ls -la $prefix/lib/libx264.a
}

mkdir -p $OUT
case "${1:-all}" in
  host) build_host ;;
  android)
    build_one arm64-v8a   aarch64-linux aarch64-linux-android "-fPIC -DANDROID" ""
    build_one armeabi-v7a arm-linux     armv7a-linux-androideabi "-fPIC -DANDROID -march=armv7-a -mfloat-abi=softfp" "--disable-asm"
    build_one x86_64      x86_64-linux  x86_64-linux-android "-fPIC -DANDROID" "--disable-asm"
    build_one x86         i686-linux    i686-linux-android "-fPIC -DANDROID" "--disable-asm" ;;
  all)
    build_host
    build_one arm64-v8a   aarch64-linux aarch64-linux-android "-fPIC -DANDROID" ""
    build_one armeabi-v7a arm-linux     armv7a-linux-androideabi "-fPIC -DANDROID -march=armv7-a -mfloat-abi=softfp" "--disable-asm"
    build_one x86_64      x86_64-linux  x86_64-linux-android "-fPIC -DANDROID" "--disable-asm"
    build_one x86         i686-linux    i686-linux-android "-fPIC -DANDROID" "--disable-asm" ;;
esac
echo "$X264_COMMIT" > $OUT/SOURCE_VERSION.txt
cp "$SRC_DIR/COPYING" $OUT/COPYING 2>/dev/null || true
echo "done."
