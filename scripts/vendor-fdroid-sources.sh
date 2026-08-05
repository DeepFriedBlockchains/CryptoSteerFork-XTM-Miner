#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"
ROOT=$(repo_root)
SRC="$ROOT/third_party/src"
require_command git
require_command jq
require_command python3
rm -rf "$SRC"
mkdir -p "$SRC"
for index in 0 1 2 3; do
  name=$(jq -r ".native[$index].name" "$ROOT/DEPENDENCY_LOCK.json")
  repo=$(jq -r ".native[$index].repository" "$ROOT/DEPENDENCY_LOCK.json")
  commit=$(jq -r ".native[$index].commit" "$ROOT/DEPENDENCY_LOCK.json")
  temp=$(mktemp -d)
  git clone --filter=blob:none --no-checkout "$repo" "$temp/repo"
  git -C "$temp/repo" fetch --force --no-tags origin "$commit"
  git -C "$temp/repo" checkout --detach --force "$commit"
  git -C "$temp/repo" clean -ffdqx
  [[ "$(git -C "$temp/repo" rev-parse HEAD)" == "$commit" ]] || die "$name commit verification failed."
  rm -rf "$temp/repo/.git"
  mv "$temp/repo" "$SRC/$name"
  printf '%s\n' "$commit" > "$SRC/$name/.cryptosteer-upstream-commit"
  rm -rf "$temp"
done
python3 "$ROOT/build-logic/patch_randomx_aarch64_branch.py" "$SRC/RandomX"
jq -n --arg generated "2026-08-05T00:00:00Z" \
  --arg randomx "$(cat "$SRC/RandomX/.cryptosteer-upstream-commit")" \
  --arg libuv "$(cat "$SRC/libuv/.cryptosteer-upstream-commit")" \
  --arg hwloc "$(cat "$SRC/hwloc/.cryptosteer-upstream-commit")" \
  --arg openssl "$(cat "$SRC/OpenSSL/.cryptosteer-upstream-commit")" \
  '{generatedAtUtc:$generated,commits:{RandomX:$randomx,libuv:$libuv,hwloc:$hwloc,OpenSSL:$openssl},format:"vendored-source-without-vcs-history"}' \
  > "$ROOT/third_party/SOURCE_STATE.json"
log "Pinned native source trees are ready for the public F-Droid release."
