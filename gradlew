#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
GRADLE_VERSION=9.5.0
GRADLE_SHA256=553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746
GRADLE_USER_HOME=${GRADLE_USER_HOME:-$HOME/.gradle}
DIST_ROOT="$GRADLE_USER_HOME/wrapper/dists/gradle-$GRADLE_VERSION-bin/cryptosteer"
GRADLE_BIN="$DIST_ROOT/gradle-$GRADLE_VERSION/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  command -v curl >/dev/null 2>&1 || { echo "curl is required to bootstrap Gradle." >&2; exit 69; }
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required to bootstrap Gradle." >&2; exit 69; }
  mkdir -p "$DIST_ROOT"
  ARCHIVE="$DIST_ROOT/gradle-$GRADLE_VERSION-bin.zip"
  if [ ! -f "$ARCHIVE" ]; then
    curl --fail --location --retry 4 --output "$ARCHIVE.tmp" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    mv "$ARCHIVE.tmp" "$ARCHIVE"
  fi
  ACTUAL=$(sha256sum "$ARCHIVE" | awk '{print $1}')
  [ "$ACTUAL" = "$GRADLE_SHA256" ] || { echo "Gradle distribution checksum mismatch." >&2; rm -f "$ARCHIVE"; exit 65; }
  rm -rf "$DIST_ROOT/gradle-$GRADLE_VERSION"
  unzip -q "$ARCHIVE" -d "$DIST_ROOT"
fi
exec "$GRADLE_BIN" --project-dir "$APP_HOME" "$@"
