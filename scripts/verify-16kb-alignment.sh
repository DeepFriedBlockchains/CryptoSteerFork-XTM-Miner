#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"
[[ $# -eq 1 ]] || die "Usage: verify-16kb-alignment.sh <apk>"
APK=$(realpath "$1")
[[ -f "$APK" ]] || die "APK not found: $APK"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
READELF="$SDK_ROOT/ndk/$ANDROID_NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
ZIPALIGN="$SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS/zipalign"
[[ -x "$READELF" && -x "$ZIPALIGN" ]] || die "Pinned Android inspection tools are unavailable."
"$ZIPALIGN" -c -P 16 4 "$APK"
TEMP_DIR=$(mktemp -d)
unzip -q "$APK" 'lib/*/*.so' -d "$TEMP_DIR"
found=0
while IFS= read -r -d '' library; do
  found=1
  report=$("$READELF" -lW "$library")
  if ! REPORT="$report" python3 -c 'import os,sys
for line in os.environ["REPORT"].splitlines():
    fields=line.split()
    if fields and fields[0] == "LOAD":
        value=fields[-1]
        alignment=int(value, 16 if value.lower().startswith("0x") else 10)
        if alignment < 16384: sys.exit(1)'; then
    printf '%s\n' "$report" >&2
    die "ELF LOAD alignment below 16 KB: $library"
  fi
  log "16 KB LOAD alignment passed: ${library#$TEMP_DIR/}"
done < <(find "$TEMP_DIR" -type f -name '*.so' -print0)
(( found == 1 )) || die "No packaged native libraries were found."
