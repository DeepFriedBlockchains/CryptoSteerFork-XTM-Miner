#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"
ROOT=$(repo_root)
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
NDK="$SDK_ROOT/ndk/$ANDROID_NDK_VERSION"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CMAKE="${CRYPTOSTEER_CMAKE_BIN:-$SDK_ROOT/cmake/$ANDROID_CMAKE_VERSION/bin/cmake}"
NINJA="${CRYPTOSTEER_NINJA_BIN:-$SDK_ROOT/cmake/$ANDROID_CMAKE_VERSION/bin/ninja}"
PREFIX="$ROOT/third_party/install/android-arm64"
BUILD="$ROOT/third_party/build/android-arm64"
SRC="$ROOT/third_party/src"
JOBS="${CRYPTOSTEER_BUILD_JOBS:-$(nproc)}"

[[ -x "$TOOLCHAIN/bin/clang" && -x "$TOOLCHAIN/bin/clang++" ]] || die "NDK Clang toolchain is missing. Run setup-linux-builder.sh."
[[ -x "$CMAKE" && -x "$NINJA" ]] || die "Pinned CMake/Ninja installation is missing."
for source in RandomX libuv hwloc OpenSSL; do [[ -d "$SRC/$source" ]] || die "Missing $source source; run vendor-dependencies.sh."; done
rm -rf "$PREFIX" "$BUILD"
mkdir -p "$PREFIX" "$BUILD"

# Build third-party static libraries against the highest native platform that
# actually exists in NDK r29. A library may target an API below the containing
# application's minSdk; the app remains restricted to API 36 by its manifest.
# Using API 35 here also lets the NDK Clang driver select its startup objects
# normally instead of attempting to resolve a nonexistent API-36 CRT directory.
(( ANDROID_NATIVE_API <= ANDROID_MIN_API )) || \
  die "Native dependency API $ANDROID_NATIVE_API cannot exceed app minSdk $ANDROID_MIN_API."
TARGET_TRIPLE="aarch64-linux-android${ANDROID_NATIVE_API}"
SYSROOT="$TOOLCHAIN/sysroot"
CRT_ROOT="$SYSROOT/usr/lib/aarch64-linux-android/$ANDROID_NATIVE_API"
[[ -d "$SYSROOT/usr/include" ]] || die "NDK sysroot headers are missing: $SYSROOT/usr/include"
[[ -f "$CRT_ROOT/crtbegin_so.o" && -f "$CRT_ROOT/crtend_so.o" ]] || \
  die "NDK startup objects are missing for native dependency API $ANDROID_NATIVE_API: $CRT_ROOT"

WRAPPER_DIR="$BUILD/toolchain-wrappers"
mkdir -p "$WRAPPER_DIR"
cat > "$WRAPPER_DIR/${TARGET_TRIPLE}-clang" <<WRAPPER
#!/usr/bin/env sh
exec "$TOOLCHAIN/bin/clang" --target="$TARGET_TRIPLE" "\$@"
WRAPPER
cat > "$WRAPPER_DIR/${TARGET_TRIPLE}-clang++" <<WRAPPER
#!/usr/bin/env sh
exec "$TOOLCHAIN/bin/clang++" --target="$TARGET_TRIPLE" "\$@"
WRAPPER
chmod 0755 "$WRAPPER_DIR/${TARGET_TRIPLE}-clang" "$WRAPPER_DIR/${TARGET_TRIPLE}-clang++"
CC_WRAPPER="$WRAPPER_DIR/${TARGET_TRIPLE}-clang"
CXX_WRAPPER="$WRAPPER_DIR/${TARGET_TRIPLE}-clang++"
"$CC_WRAPPER" --version >/dev/null
"$CXX_WRAPPER" --version >/dev/null

# Prove that the exact compiler command used by OpenSSL/autoconf can link a
# 16 KB-compatible Android ARM64 shared object before any long build begins.
PROBE_C="$BUILD/ndk-link-probe.c"
PROBE_SO="$BUILD/ndk-link-probe.so"
printf '%s\n' 'int cryptosteer_ndk_link_probe(void) { return 1; }' > "$PROBE_C"
"$CC_WRAPPER" -fPIC -shared \
  -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
  "$PROBE_C" -o "$PROBE_SO"
"$TOOLCHAIN/bin/llvm-readelf" -h "$PROBE_SO" >/dev/null
rm -f "$PROBE_C" "$PROBE_SO"

export ANDROID_NDK_ROOT="$NDK"
export PATH="$WRAPPER_DIR:$TOOLCHAIN/bin:$PATH"
export SOURCE_DATE_EPOCH=1785558600
COMMON_CMAKE=(
  -G Ninja
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI=arm64-v8a
  -DANDROID_PLATFORM="android-${ANDROID_NATIVE_API}"
  -DANDROID_STL=c++_static
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  -DCMAKE_INSTALL_PREFIX="$PREFIX"
  -DCMAKE_AR="$TOOLCHAIN/bin/llvm-ar"
  -DCMAKE_RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
)

log "Building OpenSSL 3.5.7 for Android ARM64"
pushd "$SRC/OpenSSL" >/dev/null
make clean >/dev/null 2>&1 || true
CC="$CC_WRAPPER" CXX="$CXX_WRAPPER" \
AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib" \
./Configure android-arm64 -fPIC \
  no-shared no-module no-dso no-legacy no-apps no-tests no-ui-console no-docs \
  --prefix="$PREFIX" --openssldir="$PREFIX/ssl"
make -j"$JOBS"
make install_sw
[[ -f "$PREFIX/lib/libcrypto.a" && -f "$PREFIX/lib/libssl.a" ]] || \
  die "OpenSSL static libraries were not installed."
popd >/dev/null

log "Building libuv 1.52.1"
"$CMAKE" -S "$SRC/libuv" -B "$BUILD/libuv" "${COMMON_CMAKE[@]}" \
  -DBUILD_TESTING=OFF -DLIBUV_BUILD_TESTS=OFF -DLIBUV_BUILD_BENCH=OFF -DLIBUV_BUILD_SHARED=OFF
"$CMAKE" --build "$BUILD/libuv" --parallel "$JOBS"
"$CMAKE" --install "$BUILD/libuv"

log "Building hwloc 2.14.0"
pushd "$SRC/hwloc" >/dev/null
./autogen.sh
CC="$CC_WRAPPER" \
CXX="$CXX_WRAPPER" \
AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib" STRIP="$TOOLCHAIN/bin/llvm-strip" \
CFLAGS="-O3 -fPIC" CXXFLAGS="-O3 -fPIC" LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
./configure --host=aarch64-linux-android --prefix="$PREFIX" --disable-shared --enable-static \
  --disable-libxml2 --disable-cairo --disable-pci --disable-opencl --disable-cuda --disable-nvml \
  --disable-rsmi --disable-levelzero --disable-plugins --disable-io --without-x --disable-picky
make -j"$JOBS"
make install
popd >/dev/null

log "Building RandomX 2.0.1"
"$CMAKE" -S "$SRC/RandomX" -B "$BUILD/randomx" "${COMMON_CMAKE[@]}" \
  -DBUILD_SHARED_LIBS=OFF -DARCH_ID=arm64 -DRANDOMX_TOOLS=OFF
"$CMAKE" --build "$BUILD/randomx" --parallel "$JOBS"
randomx_archive=$(find "$BUILD/randomx" -type f -name 'librandomx.a' -print -quit)
[[ -n "$randomx_archive" ]] || die "RandomX static library was not produced."
install -m 0644 "$randomx_archive" "$PREFIX/lib/librandomx.a"
install -m 0644 "$SRC/RandomX/src/randomx.h" "$PREFIX/include/randomx.h"

# Fail before Gradle if the archive still contains the exact relocation that
# cannot reach a preemptible/global target in a large Android shared library.
RANDOMX_RELOCATION_CHECK="$BUILD/randomx-relocation-check"
rm -rf "$RANDOMX_RELOCATION_CHECK"
mkdir -p "$RANDOMX_RELOCATION_CHECK"
randomx_member=$("$TOOLCHAIN/bin/llvm-ar" t "$PREFIX/lib/librandomx.a" | grep -E '(^|/)jit_compiler_a64_static\.S\.o$' | head -n 1 || true)
[[ -n "$randomx_member" ]] || die "RandomX AArch64 assembly object is missing from librandomx.a."
(
  cd "$RANDOMX_RELOCATION_CHECK"
  "$TOOLCHAIN/bin/llvm-ar" x "$PREFIX/lib/librandomx.a" "$randomx_member"
  extracted=$(find . -type f -name 'jit_compiler_a64_static.S.o' -print -quit)
  [[ -n "$extracted" ]] || die "Unable to extract the RandomX AArch64 assembly object."
  if "$TOOLCHAIN/bin/llvm-readelf" -rW "$extracted" | grep -E 'R_AARCH64_CONDBR19.*randomx_program_aarch64_main_loop'; then
    die "RandomX still contains an out-of-range-capable conditional relocation to the global ARM64 main loop."
  fi
)
log "RandomX AArch64 branch relocation verification passed."

if [[ -f "$PREFIX/lib/libuv.a" && ! -f "$PREFIX/lib/libuv_a.a" ]]; then cp "$PREFIX/lib/libuv.a" "$PREFIX/lib/libuv_a.a"; fi
"$ROOT/scripts/verify-dependencies.sh"
log "Native dependencies installed at $PREFIX"
