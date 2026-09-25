#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-or-later
# Build static FFmpeg 7.1.2 (+libx264) for host + 4 Android ABIs.
# Output: rust/ffmpeg/<abi>/{include,lib}/ (libav*.a).
# Requires: Android NDK (ANDROID_NDK_HOME or $ANDROID_HOME/ndk/<ver>),
#           libx264 prebuilt via ../build-x264.sh, curl, pkg-config.
set -euo pipefail
FFVER="7.1.2"
SRC_DIR="${FFMPEG_SRC:-/tmp/ffsrc/ffmpeg-$FFVER}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/ffmpeg"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-/opt/android-sdk}/ndk/27.2.12479018}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TC/sysroot"
X264="$ROOT/x264"
API=26

if [ ! -f "$SRC_DIR/configure" ]; then
  echo "Downloading FFmpeg $FFVER..."
  mkdir -p "$(dirname "$SRC_DIR")"
  curl -sSL -o /tmp/ffmpeg-$FFVER.tar.xz https://ffmpeg.org/releases/ffmpeg-$FFVER.tar.xz
  tar -xf /tmp/ffmpeg-$FFVER.tar.xz -C "$(dirname "$SRC_DIR")"
fi

common="--disable-programs --disable-doc --disable-debug \
  --disable-avdevice --disable-swresample --disable-postproc --disable-avfilter \
  --disable-everything \
  --enable-avformat --enable-avcodec --enable-avutil --enable-swscale \
  --enable-demuxer=mov,mpegts,avi,mpegvideo \
  --enable-muxer=mp4 \
  --enable-decoder=h264,hevc,vp9,av1,mpeg4,aac \
  --enable-encoder=aac,libx264 \
  --enable-parser=h264,hevc,vp9,av1,mpeg4video,aac \
  --enable-protocol=file \
  --enable-libx264 --enable-gpl --enable-static --disable-shared --enable-pic"

build_host() {
  echo "=== ffmpeg host ==="
  rm -rf /tmp/ff-host && cp -r $SRC_DIR /tmp/ff-host
  pushd /tmp/ff-host >/dev/null
  export PKG_CONFIG_PATH=$X264/host/lib/pkgconfig
  ./configure --prefix=$OUT/host $common \
    --cc="gcc" --disable-x86asm \
    --extra-cflags="-O2 -fPIC -I$X264/host/include" \
    --extra-ldflags="-L$X264/host/lib"
  make -j$(nproc)
  make install
  popd >/dev/null
  rm -rf /tmp/ff-host
}

build_android() {
  local abi=$1 arch=$2 cpu=$3 triple=$4 asmflag=$5
  echo "=== ffmpeg $abi ==="
  rm -rf /tmp/ff-$abi && cp -r $SRC_DIR /tmp/ff-$abi
  pushd /tmp/ff-$abi >/dev/null
  export PATH=$TC/bin:$PATH
  export PKG_CONFIG_PATH=$X264/$abi/lib/pkgconfig
  # Force static-only pkg-config results for x264 (avoid -lpthread/-lm dup issues is fine, they're needed anyway)
  ./configure --prefix=$OUT/$abi $common \
    --target-os=android --arch=$arch --cpu=$cpu --enable-cross-compile \
    --sysroot=$SYSROOT --cross-prefix=$TC/bin/${triple}- \
    --cc=$TC/bin/${triple}${API}-clang \
    --as=$TC/bin/${triple}${API}-clang \
    --ar=$TC/bin/llvm-ar --ranlib=$TC/bin/llvm-ranlib \
    --nm=$TC/bin/llvm-nm --strip=$TC/bin/llvm-strip \
    --pkg-config=pkg-config \
    $asmflag \
    --extra-cflags="-O2 -fPIC -DANDROID" \
    --extra-ldflags=""
  make -j$(nproc)
  make install
  popd >/dev/null
  rm -rf /tmp/ff-$abi
  ls $OUT/$abi/lib/libavformat.a $OUT/$abi/lib/libavcodec.a
}

mkdir -p $OUT
case "${1:-all}" in
  host) build_host ;;
  android)
    build_android arm64-v8a   aarch64 armv8-a   aarch64-linux-android ""
    build_android armeabi-v7a arm     armv7-a   armv7a-linux-androideabi "--disable-asm"
    build_android x86_64      x86_64  x86-64    x86_64-linux-android "--disable-x86asm"
    build_android x86         x86     i686      i686-linux-android "--disable-asm" ;;
  all)
    build_host
    build_android arm64-v8a   aarch64 armv8-a   aarch64-linux-android ""
    build_android armeabi-v7a arm     armv7-a   armv7a-linux-androideabi "--disable-asm"
    build_android x86_64      x86_64  x86-64    x86_64-linux-android "--disable-x86asm"
    build_android x86         x86     i686      i686-linux-android "--disable-asm" ;;
esac
