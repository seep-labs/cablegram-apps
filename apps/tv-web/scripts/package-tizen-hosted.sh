#!/usr/bin/env bash
# Packages and signs platforms/tizen-hosted (the Samsung wrapper that only opens https://tv.cablegram.app) into a .wgt.
#
# Usage: scripts/package-tizen-hosted.sh <certificate folder> [output folder]
#   <certificate folder> holds author.p12 and, for a Samsung certificate, distributor.p12 (Tizen Studio, Certificate Manager).
#   The passwords are asked for here, never stored. A temporary signing profile is registered for the signing and removed afterwards.
#
# The signed package carries the licence and notices like every other build (legal/LICENSE, legal/NOTICE) and is named
# Cablegram_TV_<version>.wgt (`tizen install` fails on the space in the name Tizen gives it). A TV only accepts it when the
# distributor certificate lists its DUID; Apps2Samsung signs a package for a TV itself.
set -euo pipefail

CERTS="${1:?usage: package-tizen-hosted.sh <certificate folder> [output folder]}"
OUT="${2:-$(pwd)/build/tizen-hosted}"
TIZEN="${TIZEN:-$HOME/tizen-studio/tools/ide/bin/tizen}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
WRAPPER="$ROOT/apps/tv-web/platforms/tizen-hosted"

[ -x "$TIZEN" ] || { echo "Tizen CLI not found at $TIZEN (set TIZEN=...)" >&2; exit 1; }
[ -f "$CERTS/author.p12" ] || { echo "$CERTS/author.p12 not found" >&2; exit 1; }
[ -f "$ROOT/LICENSE" ] && [ -f "$ROOT/NOTICE" ] || { echo "LICENSE and NOTICE are missing; the package must carry them" >&2; exit 1; }

read -r -s -p "Author certificate password: " AUTHOR_PASSWORD; echo
ARGS=(-a "$CERTS/author.p12" -p "$AUTHOR_PASSWORD")
if [ -f "$CERTS/distributor.p12" ]; then
  read -r -s -p "Distributor certificate password (Enter: same as the author's): " DIST_PASSWORD; echo
  ARGS+=(-d "$CERTS/distributor.p12" -dp "${DIST_PASSWORD:-$AUTHOR_PASSWORD}")
fi

PROFILE="cg-hosted-$$"
STAGE="$(mktemp -d)"
cleanup() { "$TIZEN" security-profiles remove -n "$PROFILE" >/dev/null 2>&1 || true; rm -rf "$STAGE"; }
trap cleanup EXIT

"$TIZEN" security-profiles add -n "$PROFILE" "${ARGS[@]}" | tail -1
cp -R "$WRAPPER/." "$STAGE/"
mkdir -p "$STAGE/legal"
cp "$ROOT/LICENSE" "$ROOT/NOTICE" "$STAGE/legal/"

VERSION="$(grep -o 'version="[0-9]*\.[0-9]*\.[0-9]*"' "$WRAPPER/config.xml" | head -1 | cut -d'"' -f2)"
mkdir -p "$OUT"
rm -f "$OUT"/*.wgt
"$TIZEN" package -t wgt -s "$PROFILE" -o "$OUT" -- "$STAGE" | tail -2
PACKAGE="$(ls "$OUT"/*.wgt | head -1)"
FINAL="$OUT/Cablegram_TV_${VERSION}.wgt"
mv "$PACKAGE" "$FINAL"

# A package without both signatures installs nowhere: fail here, not on the TV.
LISTING="$(unzip -l "$FINAL")"
for need in author-signature.xml signature1.xml config.xml legal/LICENSE legal/NOTICE; do
  grep -q "$need" <<<"$LISTING" || { echo "The package has no $need" >&2; exit 1; }
done
echo "Signed package: $FINAL"
shasum -a 256 "$FINAL"
