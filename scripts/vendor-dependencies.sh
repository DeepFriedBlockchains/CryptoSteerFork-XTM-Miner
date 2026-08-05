#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"
ROOT=$(repo_root)
SRC="$ROOT/third_party/src"
mkdir -p "$SRC"
require_command git
require_command jq

vendor_one() {
  local key="$1" name repo commit destination
  name=$(jq -r ".native[$key].name" "$ROOT/DEPENDENCY_LOCK.json")
  repo=$(jq -r ".native[$key].repository" "$ROOT/DEPENDENCY_LOCK.json")
  commit=$(jq -r ".native[$key].commit" "$ROOT/DEPENDENCY_LOCK.json")
  destination="$SRC/$name"
  if [[ ! -d "$destination/.git" ]]; then
    log "Cloning $name"
    git clone --filter=blob:none --no-checkout "$repo" "$destination"
  fi
  git -C "$destination" remote set-url origin "$repo"
  git -C "$destination" fetch --force --no-tags origin "$commit"
  git -C "$destination" checkout --detach --force "$commit"
  git -C "$destination" clean -ffdqx
  [[ "$(git -C "$destination" rev-parse HEAD)" == "$commit" ]] || die "$name source commit verification failed."
  [[ -z "$(git -C "$destination" status --porcelain)" ]] || die "$name source tree is dirty after checkout."
}

for index in 0 1 2 3; do vendor_one "$index"; done

# RandomX 2.0.1 uses a conditional branch to a globally visible AArch64
# template symbol. Shared-library ThinLTO can resolve that symbol outside the
# +/-1 MiB CONDBR19 range. Branch to a local alias instead; this is a
# one-instruction, performance-neutral source patch with fail-closed matching.
python3 "$ROOT/build-logic/patch_randomx_aarch64_branch.py" "$SRC/RandomX"

jq -n --arg generated "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" \
  --arg randomx "$(git -C "$SRC/RandomX" rev-parse HEAD)" \
  --arg libuv "$(git -C "$SRC/libuv" rev-parse HEAD)" \
  --arg hwloc "$(git -C "$SRC/hwloc" rev-parse HEAD)" \
  --arg openssl "$(git -C "$SRC/OpenSSL" rev-parse HEAD)" \
  '{generatedAtUtc:$generated, commits:{RandomX:$randomx,libuv:$libuv,hwloc:$hwloc,OpenSSL:$openssl}}' > "$ROOT/third_party/SOURCE_STATE.json"
log "All native dependency sources are pinned and clean."
