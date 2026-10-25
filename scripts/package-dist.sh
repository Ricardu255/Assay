#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=${1:-dist}
JAR="$ROOT/app/target/assay.jar"
cd "$ROOT"

if [ ! -f "$JAR" ]; then
  mvn -q -DskipTests -pl :assay -am clean package
fi

STAGE="$OUT/assay"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "$JAR" "$STAGE/assay.jar"
cp assay assay.cmd QUICKSTART.md "$STAGE/"
cp -R examples "$STAGE/examples"

if command -v zip >/dev/null 2>&1; then
  rm -f "$OUT/assay.zip"
  (cd "$STAGE" && zip -qr "../assay.zip" .)
  echo "created $OUT/assay.zip"
else
  rm -f "$OUT/assay.tar.gz"
  tar -czf "$OUT/assay.tar.gz" -C "$OUT" assay
  echo "created $OUT/assay.tar.gz"
fi
echo "users need Java 17 only; no Maven required"
