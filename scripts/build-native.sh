#!/bin/sh
# Builds libmine2metal.dylib: the Objective-C backend (src/main/native), the FidelityFX backend (src/main/native/fsr),
# AMD's FidelityFX host code (third_party/fidelityfx-1.1.4) and the embedded FSR shaders.
#   scripts/build-native.sh <output dylib> <minimum macOS version> <fsr_shaders.c>
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$1"
MIN_VERSION="$2"
SHADERS="$3"
FFX="$ROOT/third_party/fidelityfx-1.1.4/sdk"
OBJ="$(dirname "$OUT")/obj"
rm -rf "$OBJ"
mkdir -p "$OBJ"

COMMON="-arch arm64 -mmacosx-version-min=$MIN_VERSION -O2 -fvisibility=hidden"
FFX_INCLUDES="-isystem $FFX/include -isystem $FFX/include/FidelityFX/host -isystem $FFX/src/shared -I$ROOT/src/main/native/fsr"
STRICT="-Wall -Wextra -Werror"

# src/main/native is on the include path for the world shaders shaders.m embeds with .incbin.
for source in "$ROOT"/src/main/native/*.m; do
	# shellcheck disable=SC2086
	xcrun clang $COMMON -fobjc-arc $STRICT $FFX_INCLUDES -I"$ROOT/src/main/native" -c "$source" -o "$OBJ/$(basename "$source" .m).o"
done
for source in "$ROOT"/src/main/native/fsr/*.mm; do
	# shellcheck disable=SC2086
	xcrun clang++ $COMMON -std=c++17 -fobjc-arc $STRICT $FFX_INCLUDES -I"$ROOT/src/main/native" -c "$source" -o "$OBJ/$(basename "$source" .mm).o"
done
# AMD's code is compiled as it is: its own warnings are not ours to fix.
for source in "$FFX/src/components/frameinterpolation/ffx_frameinterpolation.cpp" "$FFX/src/components/opticalflow/ffx_opticalflow.cpp" \
	"$FFX/src/components/fsr3upscaler/ffx_fsr3upscaler.cpp" "$FFX/src/components/fsr1/ffx_fsr1.cpp" \
	"$FFX/src/shared/ffx_assert.cpp" "$FFX/src/shared/ffx_message.cpp" "$FFX/src/shared/ffx_object_management.cpp"; do
	# shellcheck disable=SC2086
	xcrun clang++ $COMMON -std=c++17 -w -include "$ROOT/src/main/native/fsr/ffx_macos_compat.h" $FFX_INCLUDES \
		-I"$(dirname "$source")" -c "$source" -o "$OBJ/ffx_$(basename "$source" .cpp).o"
done
# shellcheck disable=SC2086
xcrun clang $COMMON -w -I"$ROOT/src/main/native/fsr" -c "$SHADERS" -o "$OBJ/fsr_shaders.o"

# shellcheck disable=SC2086
xcrun clang++ -dynamiclib $COMMON "$OBJ"/*.o -o "$OUT" -install_name @rpath/libmine2metal.dylib \
	-framework AppKit -framework Foundation -framework Metal -framework MetalFX -framework QuartzCore
