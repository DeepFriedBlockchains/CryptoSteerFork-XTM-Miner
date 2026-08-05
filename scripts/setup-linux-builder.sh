#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"

[[ "$(uname -s)" == "Linux" ]] || die "This setup supports Linux only."
[[ "$(uname -m)" == "x86_64" ]] || die "The pinned Android host tools require x86-64 Linux."

for command in curl unzip sha256sum java javac; do
  require_command "$command"
done

java_version=$(javac -version 2>&1 | awk '{print $2}')
java_major=${java_version%%.*}
[[ "$java_major" == "17" ]] || die "JDK 17 is required; active javac version is $java_version."

SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
CMDLINE_VERSION="15859902"
CMDLINE_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
CMDLINE_URL="${ANDROID_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_VERSION}_latest.zip}"

mkdir -p "$SDK_ROOT" "$HOME/.android"
touch "$HOME/.android/repositories.cfg"

if [[ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
  TEMP_DIR=$(mktemp -d)
  archive="$TEMP_DIR/command-line-tools.zip"
  log "Downloading Android command-line tools"
  curl --fail --location --retry 4 --output "$archive" "$CMDLINE_URL"
  [[ "$(sha256_file "$archive")" == "$CMDLINE_SHA256" ]] || die "Android command-line tools checksum mismatch."
  unzip -q "$archive" -d "$TEMP_DIR/unpacked"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mkdir -p "$SDK_ROOT/cmdline-tools/latest"
  cp -a "$TEMP_DIR/unpacked/cmdline-tools/." "$SDK_ROOT/cmdline-tools/latest/"
fi

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$PATH"

set +o pipefail
yes | sdkmanager --licenses >/dev/null
license_status=${PIPESTATUS[1]}
set -o pipefail
(( license_status == 0 )) || die "Android SDK license acceptance failed."

log "Installing the pinned Android packages"
sdkmanager --channel=0 --install \
  "platform-tools" \
  "build-tools;${ANDROID_BUILD_TOOLS}" \
  "ndk;${ANDROID_NDK_VERSION}" \
  "cmake;${ANDROID_CMAKE_VERSION}"

sdk_list=$(sdkmanager --channel=0 --list)
platform_package=""
for candidate in \
  "platforms;android-37.0" \
  "platforms;android-CinnamonBun" \
  "platforms;android-${ANDROID_API}"
do
  if grep -Fq "$candidate" <<<"$sdk_list"; then
    platform_package="$candidate"
    break
  fi
done
[[ -n "$platform_package" ]] || die "Android platform 37 is not visible in the current SDK repository."
sdkmanager --channel=0 --install "$platform_package"

platform_dir=""
for candidate_dir in \
  "$SDK_ROOT/platforms/android-${ANDROID_API}" \
  "$SDK_ROOT/platforms/android-37.0" \
  "$SDK_ROOT/platforms/android-CinnamonBun"
do
  if [[ -f "$candidate_dir/android.jar" ]]; then
    platform_dir="$candidate_dir"
    break
  fi
done
[[ -n "$platform_dir" ]] || die "Android platform 37 was not installed."
if [[ ! -e "$SDK_ROOT/platforms/android-${ANDROID_API}" ]]; then
  ln -s "$(basename "$platform_dir")" "$SDK_ROOT/platforms/android-${ANDROID_API}"
fi

[[ -x "$SDK_ROOT/ndk/$ANDROID_NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]] || \
  die "The pinned NDK installation is incomplete."
[[ -x "$SDK_ROOT/cmake/$ANDROID_CMAKE_VERSION/bin/cmake" ]] || \
  die "The pinned CMake installation is incomplete."
[[ -x "$SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS/apksigner" ]] || \
  die "The pinned Build Tools installation is incomplete."

log "Android build environment is ready"
printf 'export ANDROID_SDK_ROOT=%q\n' "$SDK_ROOT"
