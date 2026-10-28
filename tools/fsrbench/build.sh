#!/bin/sh
# Builds the FSR spike tools into a directory: fsrbench (frame generation) and fsrupscale (upscalers).
#   tools/fsrbench/build.sh <output directory>
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FFX="$ROOT/third_party/fidelityfx-1.1.4/sdk"
OUT="$1"
OBJ="$OUT/obj"
mkdir -p "$OBJ"
INCLUDES="-isystem $FFX/include -isystem $FFX/include/FidelityFX/host -isystem $FFX/src/shared -I$ROOT/src/main/native/fsr"
for source in "$FFX"/src/components/*/*.cpp "$FFX/src/shared/ffx_assert.cpp" "$FFX/src/shared/ffx_message.cpp" \
	"$FFX/src/shared/ffx_object_management.cpp"; do
	# shellcheck disable=SC2086
	xcrun clang++ -std=c++17 -O2 -w -include "$ROOT/src/main/native/fsr/ffx_macos_compat.h" $INCLUDES \
		-I"$(dirname "$source")" -c "$source" -o "$OBJ/$(basename "$source" .cpp).o"
done
for source in "$ROOT/src/main/native/fsr/ffx_metal.mm" "$ROOT/tools/fsrbench/main.mm" "$ROOT/tools/fsrbench/upscale.mm"; do
	# shellcheck disable=SC2086
	xcrun clang++ -std=c++17 -O2 -fobjc-arc -Wall -Wextra $INCLUDES -c "$source" -o "$OBJ/tool_$(basename "$source" .mm).o"
done
FRAMEWORKS="-framework Foundation -framework Metal -framework MetalFX -framework CoreGraphics -framework ImageIO -framework UniformTypeIdentifiers"
LIBRARY=$(ls "$OBJ"/*.o | grep -v '/tool_')
# shellcheck disable=SC2086
xcrun clang++ $LIBRARY "$OBJ/tool_ffx_metal.o" "$OBJ/tool_main.o" -o "$OUT/fsrbench" $FRAMEWORKS
# shellcheck disable=SC2086
xcrun clang++ $LIBRARY "$OBJ/tool_ffx_metal.o" "$OBJ/tool_upscale.o" -o "$OUT/fsrupscale" $FRAMEWORKS
echo "Built $OUT/fsrbench and $OUT/fsrupscale"
