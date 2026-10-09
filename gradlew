#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$ROOT/gradle/wrapper/gradle-wrapper.jar"
if [ -f "$JAR" ]; then
  exec java -Dorg.gradle.appname=gradlew -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
fi
VER=9.7.1
SHA256=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/nirmalam-bootstrap"
HOME_DIR="$CACHE/gradle-$VER"
if [ ! -x "$HOME_DIR/bin/gradle" ]; then
  mkdir -p "$CACHE"
  ZIP="$CACHE/gradle-$VER-bin.zip"
  URL="https://services.gradle.org/distributions/gradle-$VER-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$URL" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "$URL"
  else
    echo "Gradle wrapper JAR is absent and neither curl nor wget is available." >&2
    exit 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$ZIP" | awk '{print $1}')
    [ "$ACTUAL" = "$SHA256" ] || { echo "Gradle distribution checksum mismatch." >&2; rm -f "$ZIP"; exit 1; }
  else
    echo "sha256sum is required to verify the Gradle bootstrap download." >&2
    rm -f "$ZIP"
    exit 1
  fi
  unzip -q -o "$ZIP" -d "$CACHE"
fi
exec "$HOME_DIR/bin/gradle" "$@"
