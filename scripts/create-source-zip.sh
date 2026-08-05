#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"
ROOT=$(repo_root)
OUTPUT="${1:-$(dirname "$ROOT")/${PROJECT_NAME}-source-v${PROJECT_VERSION}.zip}"
INCLUDE_VENDORED="${CRYPTOSTEER_INCLUDE_VENDORED_SOURCES:-0}"
require_command python3
require_command sha256sum
rm -f "$OUTPUT" "$OUTPUT.sha256"
args=(
  --root "$ROOT"
  --output "$OUTPUT"
  --project-name "$PROJECT_NAME"
)
[[ "$INCLUDE_VENDORED" == "1" ]] && args+=(--include-vendored)
python3 "$ROOT/build-logic/create_source_archive.py" "${args[@]}"
(cd "$(dirname "$OUTPUT")" && sha256sum "$(basename "$OUTPUT")" > "$(basename "$OUTPUT").sha256")
log "Created deterministic source ZIP: $OUTPUT"
