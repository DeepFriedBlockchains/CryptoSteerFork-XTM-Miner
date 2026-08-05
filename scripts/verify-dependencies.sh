#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"
ROOT=$(repo_root)
PREFIX="$ROOT/third_party/install/android-arm64"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
READELF="$SDK_ROOT/ndk/$ANDROID_NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
require_command jq
[[ -x "$READELF" ]] || die "llvm-readelf from NDK r29 is unavailable."
required=(
  include/randomx.h include/uv.h include/hwloc.h include/openssl/ssl.h
  lib/librandomx.a lib/libuv_a.a lib/libhwloc.a lib/libssl.a lib/libcrypto.a
)
for path in "${required[@]}"; do [[ -s "$PREFIX/$path" ]] || die "Verified dependency artifact is missing: $PREFIX/$path"; done

source_identity_mode="markers"
for index in 0 1 2 3; do
  name=$(jq -r ".native[$index].name" "$ROOT/DEPENDENCY_LOCK.json")
  commit=$(jq -r ".native[$index].commit" "$ROOT/DEPENDENCY_LOCK.json")
  source_dir="$ROOT/third_party/src/$name"
  [[ -d "$source_dir" ]] || die "$name source directory is missing."
  if [[ -d "$source_dir/.git" ]]; then
    source_identity_mode="git"
    [[ "$(git -C "$source_dir" rev-parse HEAD)" == "$commit" ]] || die "$name is not at its locked commit."
  else
    marker="$source_dir/.cryptosteer-upstream-commit"
    [[ -s "$marker" && "$(tr -d '\r\n' < "$marker")" == "$commit" ]] || \
      die "$name vendored source marker does not match its locked commit."
  fi
done

RANDOMX_SOURCE="$ROOT/third_party/src/RandomX/src/jit_compiler_a64_static.S"
RANDOMX_PATCH_STATE="$ROOT/third_party/RANDOMX_AARCH64_BRANCH_PATCH.json"
[[ -s "$RANDOMX_PATCH_STATE" ]] || die "RandomX AArch64 branch patch state is missing."
grep -Fq -- '.Lcryptosteer_randomx_program_aarch64_main_loop:' "$RANDOMX_SOURCE" || \
  die "RandomX local ARM64 main-loop alias is missing."
if grep -Eq '^[[:space:]]*bne[[:space:]]+DECL\(randomx_program_aarch64_main_loop\)[[:space:]]*$' "$RANDOMX_SOURCE"; then
  die "RandomX still branches conditionally to the global ARM64 main-loop symbol."
fi
expected_randomx_sha=$(jq -r '.afterSha256' "$RANDOMX_PATCH_STATE")
[[ "$(sha256_file "$RANDOMX_SOURCE")" == "$expected_randomx_sha" ]] || \
  die "RandomX patched assembly checksum does not match its recorded state."
if [[ -d "$ROOT/third_party/src/RandomX/.git" ]]; then
  randomx_changes=$(git -C "$ROOT/third_party/src/RandomX" status --porcelain --untracked-files=no)
  [[ "$randomx_changes" == ' M src/jit_compiler_a64_static.S' || "$randomx_changes" == 'M  src/jit_compiler_a64_static.S' ]] || \
    die "RandomX has unexpected tracked modifications after applying the approved ARM64 patch: ${randomx_changes:-none}"
else
  [[ -s "$ROOT/third_party/src/RandomX/.cryptosteer-upstream-commit" ]] || \
    die "Vendored RandomX source identity marker is missing."
fi
find "$PREFIX/lib" -maxdepth 1 -name '*.a' -print0 | while IFS= read -r -d '' archive; do
  "$READELF" -h "$archive" >/dev/null || die "Unreadable native archive: $archive"
done
log "Dependency source identity ($source_identity_mode) and artifact verification passed."
