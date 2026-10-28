#!/bin/sh
# Builds the FSR 3 frame generation spike: tools/fsrbench/build.sh <output binary>
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FFX="$ROOT/third_party/fidelityfx-1.1.4/sdk"
OUT="$1"
OBJ="$(dirname "$OUT")/fsrbench-obj"
mkdir -p "$OBJ"
INCLUDES="-isystem $FFX/include -isystem $FFX/include/FidelityFX/host -isystem $FFX/src/shared -I$ROOT/src/main/native/fsr"
for source in "$FFX/src/components/frameinterpolation/ffx_frameinterpolation.cpp" "$FFX/src/components/opticalflow/ffx_opticalflow.cpp" \
	"$FFX/src/shared/ffx_assert.cpp" "$FFX/src/shared/ffx_message.cpp" "$FFX/src/shared/ffx_object_management.cpp"; do
	# shellcheck disable=SC2086
	xcrun clang++ -std=c++17 -O2 -w -include "$ROOT/src/main/native/fsr/ffx_macos_compat.h" $INCLUDES \
		-I"$(dirname "$source")" -c "$source" -o "$OBJ/$(basename "$source" .cpp).o"
done
# shellcheck disable=SC2086
xcrun clang++ -std=c++17 -O2 -fobjc-arc -Wall -Wextra $INCLUDES -c "$ROOT/src/main/native/fsr/ffx_metal.mm" -o "$OBJ/ffx_metal.o"
# shellcheck disable=SC2086
xcrun clang++ -std=c++17 -O2 -fobjc-arc -Wall -Wextra $INCLUDES -c "$ROOT/tools/fsrbench/main.mm" -o "$OBJ/main.o"
xcrun clang++ "$OBJ"/*.o -o "$OUT" -framework Foundation -framework Metal -framework CoreGraphics -framework ImageIO \
	-framework UniformTypeIdentifiers
echo "Built $OUT"
