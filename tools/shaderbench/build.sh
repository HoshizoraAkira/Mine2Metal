#!/bin/sh
# Builds shaderbench, the offline benchmark of the world shaders (see main.m).
#   tools/shaderbench/build.sh <output directory>
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$1"
mkdir -p "$OUT"
xcrun clang -fobjc-arc -O2 -Wall -Wextra "$ROOT/tools/shaderbench/main.m" -o "$OUT/shaderbench" \
	-framework Foundation -framework Metal -framework CoreGraphics -framework ImageIO -framework UniformTypeIdentifiers -framework QuartzCore
echo "Built $OUT/shaderbench"
