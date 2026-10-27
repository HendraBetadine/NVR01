#!/usr/bin/env bash
# Build ffmpeg minimal (khusus RTSP -> MKV copy) untuk Android 4.4 ARMv7.
# Hasil: app/src/main/jniLibs/armeabi-v7a/libffmpeg.so
#
# Butuh: Linux (Ubuntu), git, make, pkg-config, dan Android NDK r25c
#   (NDK r26 ke atas tidak mendukung Android 4.4 / API 19 lagi).
#
# Pakai:  ANDROID_NDK_HOME=/path/ke/android-ndk-r25c ./scripts/build-ffmpeg.sh
set -euo pipefail

FFMPEG_VERSION="${FFMPEG_VERSION:-n7.1}"
API=19
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-$ROOT/build-ffmpeg}"
OUT="$ROOT/app/src/main/jniLibs/armeabi-v7a/libffmpeg.so"

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME ke folder NDK r25c}"
TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
[ -x "$TC/bin/armv7a-linux-androideabi${API}-clang" ] || { echo "NDK tidak cocok (butuh r25c)"; exit 1; }

mkdir -p "$WORK"
cd "$WORK"
if [ ! -d ffmpeg ]; then
  git clone --depth 1 --branch "$FFMPEG_VERSION" https://github.com/FFmpeg/FFmpeg.git ffmpeg
fi
cd ffmpeg
make distclean >/dev/null 2>&1 || true

./configure \
  --target-os=android --arch=arm --cpu=armv7-a --enable-cross-compile \
  --cc="$TC/bin/armv7a-linux-androideabi${API}-clang" \
  --cxx="$TC/bin/armv7a-linux-androideabi${API}-clang++" \
  --ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm" \
  --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" \
  --sysroot="$TC/sysroot" \
  --extra-cflags="-O2 -fPIE" --extra-ldflags="-pie -static-libstdc++" \
  --pkg-config=false \
  --enable-static --disable-shared --disable-debug --disable-doc \
  --disable-ffplay --disable-ffprobe --disable-asm \
  --disable-autodetect --disable-everything \
  --enable-network \
  --enable-protocol=tcp,udp,rtp,file,pipe \
  --enable-demuxer=rtsp,rtp,sdp,matroska \
  --enable-muxer=segment,matroska,mp4,mov,hls,mpegts,tee \
  --enable-parser=h264,hevc,aac,mjpeg \
  --enable-decoder=h264,hevc,aac,pcm_alaw,pcm_mulaw,mjpeg \
  --enable-bsf=h264_mp4toannexb,hevc_mp4toannexb,aac_adtstoasc,extract_extradata \
  --enable-filter=null,anull,buffer,buffersink,abuffer,abuffersink,format,aformat,aresample \
  --enable-swresample

make -j"$(nproc)" ffmpeg
"$TC/bin/llvm-strip" ffmpeg

mkdir -p "$(dirname "$OUT")"
cp ffmpeg "$OUT"
echo
echo "OK -> $OUT ($(du -h "$OUT" | cut -f1))"
