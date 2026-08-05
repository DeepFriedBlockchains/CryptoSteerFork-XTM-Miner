#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

PROJECT_NAME="CryptoSteer-XMR-Miner"
PROJECT_VERSION="2.2.1"
ANDROID_API="37"
ANDROID_MIN_API="36"
# NDK r29 ships its highest ARM64 native platform libraries at API 35.
# Building reusable static dependencies for API 35 is compatible with the
# containing application, whose manifest minimum remains API 36.
ANDROID_NATIVE_API="35"
ANDROID_NDK_VERSION="29.0.14206865"
ANDROID_CMAKE_VERSION="3.22.1"
ANDROID_BUILD_TOOLS="37.0.0"
ABI="arm64-v8a"

log() { printf '[%s] %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }
require_command() { command -v "$1" >/dev/null 2>&1 || die "Required command is unavailable: $1"; }
sha256_file() { sha256sum "$1" | awk '{print $1}'; }
repo_root() { cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P; }
cleanup_temp() { if [[ -n "${TEMP_DIR:-}" && -d "${TEMP_DIR:-}" ]]; then rm -rf -- "$TEMP_DIR"; fi; return 0; }
trap 'rc=$?; [[ $rc -ne 0 ]] && log "Failed at line $LINENO while running: $BASH_COMMAND" >&2; cleanup_temp; exit $rc' EXIT
