#!/bin/sh
# Translates the FSR 3 frame generation and FSR 1 / FSR 3.1 upscaler compute shaders (FidelityFX SDK v1.1.4, Vulkan GLSL) to Metal Shading Language:
# GLSL -> SPIR-V (glslangValidator) -> MSL (spirv-cross, which also writes binding reflection). Each pass's entry point
# is renamed to its file name. The MSL is compiled at run time with newLibraryWithSource, like the backend's other
# shaders, so the build needs no Metal toolchain.
#
#   [FSR_EFFECTS="frameinterpolation opticalflow"] scripts/build-fsr-shaders.sh <output directory>
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
# Reprojection with the Lanczos approximation; the exact lookup table is the other choice AMD offers.
FSR3UPSCALER="-DFFX_FSR3UPSCALER_OPTION_UPSAMPLE_SAMPLERS_USE_DATA_HALF=0
	-DFFX_FSR3UPSCALER_OPTION_ACCUMULATE_SAMPLERS_USE_DATA_HALF=0
	-DFFX_FSR3UPSCALER_OPTION_REPROJECT_SAMPLERS_USE_DATA_HALF=1
	-DFFX_FSR3UPSCALER_OPTION_POSTPROCESSLOCKSTATUS_SAMPLERS_USE_DATA_HALF=0
	-DFFX_FSR3UPSCALER_OPTION_UPSAMPLE_USE_LANCZOS_TYPE=2
	-DFFX_FSR3UPSCALER_OPTION_REPROJECT_USE_LANCZOS_TYPE=0
	-DFFX_FSR3UPSCALER_OPTION_HDR_COLOR_INPUT=${FSR_HDR_COLOR_INPUT:-0}
	-DFFX_FSR3UPSCALER_OPTION_LOW_RESOLUTION_MOTION_VECTORS=${FSR_LOW_RES_MOTION_VECTORS:-1}
	-DFFX_FSR3UPSCALER_OPTION_JITTERED_MOTION_VECTORS=0
	-DFFX_FSR3UPSCALER_OPTION_INVERTED_DEPTH=0
	-DFFX_FSR3UPSCALER_OPTION_APPLY_SHARPENING=0
	-I$FFX/include/FidelityFX/gpu/fsr3upscaler"
FSR1="-DFFX_FSR1_OPTION_APPLY_RCAS=1 -DFFX_FSR1_OPTION_RCAS_PASSTHROUGH_ALPHA=0 -DFFX_FSR1_OPTION_SRGB_CONVERSIONS=0
	-I$FFX/include/FidelityFX/gpu/fsr1"

failed=0
# FSR_EFFECTS limits the build to some effects, by directory name.
SOURCES=""
for effect in ${FSR_EFFECTS:-frameinterpolation opticalflow fsr3upscaler fsr1}; do
	SOURCES="$SOURCES $FFX/src/backends/vk/shaders/$effect/*.glsl"
done
# shellcheck disable=SC2086
for source in $SOURCES; do
	name="$(basename "$source" .glsl)"
	case "$name" in
	ffx_frameinterpolation_*) defines="$FRAMEINTERPOLATION" ;;
	ffx_opticalflow_*) defines="$OPTICALFLOW" ;;
	ffx_fsr3upscaler_*) defines="$FSR3UPSCALER" ;;
	*) defines="$FSR1" ;;
	esac
	# Passes rewritten for Apple GPUs replace AMD's (src/main/native/fsr/glsl, see the comments there).
	override="$ROOT/src/main/native/fsr/glsl/$name.glsl"
	[ -f "$override" ] && source="$override"
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
