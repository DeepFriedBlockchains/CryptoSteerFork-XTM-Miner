#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"

ROOT=$(repo_root)
MODE="${1:-fdroid}"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"

[[ "$(uname -s)" == "Linux" ]] || die "This build supports Linux only."
[[ "$(uname -m)" == "x86_64" ]] || die "The pinned Android host tools require x86-64 Linux."

case "$MODE" in
  fdroid|release|debug) ;;
  *) die "Usage: $0 [fdroid|release|debug]" ;;
esac

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$PATH"

for command in java javac git jq python3 clang clang++ cmake ninja make; do
  require_command "$command"
done

java_version=$(javac -version 2>&1 | awk '{print $2}')
java_major=${java_version%%.*}
[[ "$java_major" == "17" ]] || die "JDK 17 is required; active javac version is $java_version."

[[ -x "$SDK_ROOT/ndk/$ANDROID_NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]] || \
  die "Pinned NDK not found. Run scripts/setup-linux-builder.sh."
[[ -x "$SDK_ROOT/cmake/$ANDROID_CMAKE_VERSION/bin/cmake" ]] || \
  die "Pinned CMake not found. Run scripts/setup-linux-builder.sh."

missing_source=0
for dependency in RandomX libuv hwloc OpenSSL; do
  [[ -d "$ROOT/third_party/src/$dependency" ]] || missing_source=1
done
if (( missing_source )); then
  log "Obtaining pinned native source"
  "$ROOT/scripts/vendor-dependencies.sh"
fi

log "Building pinned Android native dependencies"
"$ROOT/scripts/build-native-dependencies.sh"

log "Running source, native, and integration checks"
"$ROOT/scripts/run-all-checks.sh"

cd "$ROOT"
case "$MODE" in
  fdroid)
    ./gradlew --no-daemon --no-configuration-cache \
      :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintFdroid :app:assembleFdroid
    output_pattern="$ROOT/app/build/outputs/apk/fdroid/*.apk"
    ;;
  release)
    if [[ -z "${CRYPTOSTEER_SIGNING_PROPERTIES:-}" ]]; then
      log "CRYPTOSTEER_SIGNING_PROPERTIES is not set; the release APK will be unsigned."
    else
      [[ -r "$CRYPTOSTEER_SIGNING_PROPERTIES" ]] || die "Signing properties file is not readable."
    fi
    ./gradlew --no-daemon --no-configuration-cache \
      :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintRelease :app:assembleRelease
    output_pattern="$ROOT/app/build/outputs/apk/release/*.apk"
    ;;
  debug)
    ./gradlew --no-daemon --no-configuration-cache \
      :app:testDebugUnitTest :app:assembleDebug
    output_pattern="$ROOT/app/build/outputs/apk/debug/*.apk"
    ;;
esac

log "Build complete"
find "$(dirname "$output_pattern")" -maxdepth 1 -type f -name '*.apk' -print
