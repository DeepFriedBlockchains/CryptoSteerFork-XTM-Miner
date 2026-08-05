#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"
ROOT=$(repo_root)
BUILD="$ROOT/build/host-native-tests"
rm -rf "$BUILD"
cmake -S "$ROOT/native/tests" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build "$BUILD"
"$BUILD/cryptosteer_host_tests"
