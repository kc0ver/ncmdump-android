#!/usr/bin/env bash
#
# 用 Android NDK 交叉编译 taurusxin/ncmdump 为上可执行的原生二进制，
# 产物按 ABI 放到 app/src/main/jniLibs/<abi>/libncmdump.so
#
# 用法:
#   ./native/build.sh                 # 构建全部 ABI
#   ./native/build.sh x86_64          # 只构建指定 ABI
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"

if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
  NDK="$ANDROID_NDK_HOME"
else
  NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
[[ -d "$NDK" ]] || { echo "找不到 Android NDK，请设置 ANDROID_NDK_HOME 或先用 sdkmanager 安装 ndk" >&2; exit 1; }

CMAKE="${CMAKE_BIN:-}"
if [[ -z "$CMAKE" ]]; then
  if command -v cmake >/dev/null 2>&1; then CMAKE="$(command -v cmake)"; else CMAKE="$(ls -d "$SDK"/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -1)"; fi
fi
[[ -x "$CMAKE" ]] || { echo "找不到 cmake" >&2; exit 1; }

NINJA="${NINJA_BIN:-$(dirname "$CMAKE")/ninja}"
if [[ ! -x "$NINJA" ]]; then NINJA="$(command -v ninja || true)"; fi

API="${ANDROID_API:-26}"
ALL_ABIS=(arm64-v8a armeabi-v7a x86_64)
if [[ $# -gt 0 ]]; then ABIS=("$@"); else ABIS=("${ALL_ABIS[@]}"); fi

NCMDUMP_DIR="$ROOT/third_party/ncmdump"
TAGLIB_SRC="$ROOT/third_party/taglib"
BUILD_DIR="$ROOT/native/build"
OUT_DIR="$ROOT/app/src/main/jniLibs"
JOBS="$(nproc)"

if [[ ! -f "$NCMDUMP_DIR/src/main.cpp" || ! -f "$TAGLIB_SRC/CMakeLists.txt" ]]; then
  echo "==> 初始化 git submodule"
  git -C "$ROOT" submodule update --init --recursive
fi

echo "==> NDK    : $NDK"
echo "==> CMake  : $CMAKE"
echo "==> Ninja  : ${NINJA:-<none>}"
echo "==> API    : $API"
echo "==> ABIs   : ${ABIS[*]}"
echo

for ABI in "${ABIS[@]}"; do
  echo "=============================================================="
  echo "==> [$ABI] 编译 TagLib (静态库)"
  echo "=============================================================="
  TL_BUILD="$BUILD_DIR/taglib/$ABI"
  TL_PREFIX="$BUILD_DIR/taglib-prefix/$ABI"
  rm -rf "$TL_BUILD"
  GEN=(); [[ -x "$NINJA" ]] && GEN=(-G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA")
  "$CMAKE" -S "$TAGLIB_SRC" -B "$TL_BUILD" "${GEN[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$TL_PREFIX" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TESTING=OFF \
    -DBUILD_EXAMPLES=OFF \
    -DBUILD_BINDINGS=OFF \
    -DWITH_ZLIB=ON \
    -DVISIBILITY_HIDDEN=OFF \
    > "$BUILD_DIR/taglib-$ABI-configure.log" 2>&1 || {
      tail -40 "$BUILD_DIR/taglib-$ABI-configure.log"; exit 1; }
  "$CMAKE" --build "$TL_BUILD" --parallel "$JOBS" > "$BUILD_DIR/taglib-$ABI-build.log" 2>&1 || {
      tail -60 "$BUILD_DIR/taglib-$ABI-build.log"; exit 1; }
  "$CMAKE" --install "$TL_BUILD" > "$BUILD_DIR/taglib-$ABI-install.log" 2>&1 || {
      tail -40 "$BUILD_DIR/taglib-$ABI-install.log"; exit 1; }

  echo "==> [$ABI] 编译 ncmdump CLI"
  NC_BUILD="$BUILD_DIR/ncmdump/$ABI"
  rm -rf "$NC_BUILD"
  "$CMAKE" -S "$ROOT/native" -B "$NC_BUILD" "${GEN[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_PREFIX_PATH="$TL_PREFIX" \
    -DCMAKE_FIND_ROOT_PATH="$TL_PREFIX" \
    > "$BUILD_DIR/ncmdump-$ABI-configure.log" 2>&1 || {
      tail -40 "$BUILD_DIR/ncmdump-$ABI-configure.log"; exit 1; }
  "$CMAKE" --build "$NC_BUILD" --parallel "$JOBS" > "$BUILD_DIR/ncmdump-$ABI-build.log" 2>&1 || {
      tail -60 "$BUILD_DIR/ncmdump-$ABI-build.log"; exit 1; }

  mkdir -p "$OUT_DIR/$ABI"
  cp "$NC_BUILD/libncmdump.so" "$OUT_DIR/$ABI/libncmdump.so"
  STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
  if [[ -x "$STRIP" ]]; then "$STRIP" --strip-unneeded "$OUT_DIR/$ABI/libncmdump.so" || true; fi
  chmod 755 "$OUT_DIR/$ABI/libncmdump.so"
  echo "==> [$ABI] 产物: $OUT_DIR/$ABI/libncmdump.so ($(du -h "$OUT_DIR/$ABI/libncmdump.so" | cut -f1))"
  echo
done

echo "==> 全部完成"
ls -la "$OUT_DIR"/*/libncmdump.so
