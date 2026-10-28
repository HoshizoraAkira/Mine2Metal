#!/bin/sh
# Translates the FSR 3 frame generation compute shaders (FidelityFX SDK v1.1.4, Vulkan GLSL) to Metal Shading Language:
# GLSL -> SPIR-V (glslangValidator) -> MSL (spirv-cross, which also writes binding reflection). Each pass's entry point
# is renamed to its file name. The MSL is compiled at run time with newLibraryWithSource, like the backend's other
# shaders, so the build needs no Metal toolchain.
#
#   scripts/build-fsr-shaders.sh <output directory>
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FFX="$ROOT/third_party/fidelityfx-1.1.4/sdk"
OUT="$1"
mkdir -p "$OUT/spv" "$OUT/msl" "$OUT/reflection"

COMMON="-DFFX_GPU=1 -DFFX_GLSL=1 -DFFX_HALF=${FSR_HALF:-0} -I$FFX/include/FidelityFX/gpu"
FRAMEINTERPOLATION="-DFFX_FRAMEINTERPOLATION_OPTION_UPSAMPLE_SAMPLERS_USE_DATA_HALF=0
	-DFFX_FRAMEINTERPOLATION_OPTION_ACCUMULATE_SAMPLERS_USE_DATA_HALF=0
	-DFFX_FRAMEINTERPOLATION_OPTION_REPROJECT_SAMPLERS_USE_DATA_HALF=1
	-DFFX_FRAMEINTERPOLATION_OPTION_POSTPROCESSLOCKSTATUS_SAMPLERS_USE_DATA_HALF=0
	-DFFX_FRAMEINTERPOLATION_OPTION_UPSAMPLE_USE_LANCZOS_TYPE=2
	-DFFX_FRAMEINTERPOLATION_OPTION_LOW_RES_MOTION_VECTORS=${FSR_LOW_RES_MOTION_VECTORS:-1}
	-DFFX_FRAMEINTERPOLATION_OPTION_JITTER_MOTION_VECTORS=0
	-DFFX_FRAMEINTERPOLATION_OPTION_INVERTED_DEPTH=0
	-I$FFX/include/FidelityFX/gpu/frameinterpolation"
OPTICALFLOW="-DFFX_OPTICALFLOW_OPTION_HDR_COLOR_INPUT=${FSR_HDR_COLOR_INPUT:-0} -I$FFX/include/FidelityFX/gpu/opticalflow"

failed=0
for source in "$FFX"/src/backends/vk/shaders/frameinterpolation/*.glsl "$FFX"/src/backends/vk/shaders/opticalflow/*.glsl; do
	name="$(basename "$source" .glsl)"
	case "$name" in
	ffx_frameinterpolation_*) defines="$FRAMEINTERPOLATION" ;;
	*) defines="$OPTICALFLOW" ;;
	esac
	# shellcheck disable=SC2086
	if ! glslangValidator -V --target-env vulkan1.2 -S comp -Os $COMMON $defines "$source" -o "$OUT/spv/$name.spv" > "$OUT/spv/$name.log" 2>&1; then
		echo "GLSL failed: $name (see $OUT/spv/$name.log)"; failed=1; continue
	fi
	if ! spirv-cross "$OUT/spv/$name.spv" --msl --msl-version 30100 --rename-entry-point main "$name" comp \
		--output "$OUT/msl/$name.metal" > "$OUT/msl/$name.log" 2>&1; then
		echo "MSL failed: $name (see $OUT/msl/$name.log)"; failed=1; continue
	fi
	spirv-cross "$OUT/spv/$name.spv" --reflect --output "$OUT/reflection/$name.json"
	echo "ok: $name"
done
[ "$failed" = 0 ] || exit 1
