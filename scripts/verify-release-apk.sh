#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"
[[ $# -eq 1 ]] || die "Usage: verify-release-apk.sh <signed-apk>"
APK=$(realpath "$1")
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
APKSIGNER="$SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS/apksigner"
AAPT2="$SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS/aapt2"
[[ -x "$APKSIGNER" ]] || die "apksigner is unavailable."
"$APKSIGNER" verify --verbose --print-certs "$APK"
"$(dirname "$0")/verify-16kb-alignment.sh" "$APK"
if [[ -x "$AAPT2" ]]; then
  permissions=$($AAPT2 dump permissions "$APK")
  printf '%s\n' "$permissions"
  for forbidden in READ_SMS WRITE_SMS READ_CONTACTS READ_CALL_LOG SYSTEM_ALERT_WINDOW BIND_ACCESSIBILITY_SERVICE BIND_VPN_SERVICE; do
    grep -q "$forbidden" <<<"$permissions" && die "Forbidden permission detected: $forbidden"
  done
fi
sha256sum "$APK"
