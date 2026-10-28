#!/bin/sh
# Copies the parts of AMD FidelityFX SDK v1.1.4 (MIT) that FSR 3 frame generation and the FSR 1 and FSR 3.1 upscalers
# need into third_party/. Later SDK
# versions are not MIT licensed; see docs/fsr-frame-gen.md.
set -eu
TAG=v1.1.4
REPO=GPUOpen-LibrariesAndSDKs/FidelityFX-SDK
DEST="$(cd "$(dirname "$0")/.." && pwd)/third_party/fidelityfx-1.1.4"

gh api "repos/$REPO/git/trees/$TAG?recursive=1" --jq '.tree[] | select(.type == "blob") | .path' | grep -E \
	-e '^LICENSE\.txt$' \
	-e '^sdk/include/FidelityFX/gpu/ffx_[a-z_]+\.h$' \
	-e '^sdk/include/FidelityFX/gpu/(spd|frameinterpolation|opticalflow|fsr1|fsr3upscaler)/[^/]+\.h$' \
	-e '^sdk/src/backends/vk/shaders/(frameinterpolation|opticalflow|fsr1|fsr3upscaler)/[^/]+\.glsl$' \
	-e '^sdk/include/FidelityFX/host/ffx_(assert|error|frameinterpolation|fsr1|fsr3upscaler|fx|interface|message|opticalflow|types|util)\.h$' \
	-e '^sdk/src/components/(frameinterpolation|opticalflow|fsr1|fsr3upscaler)/[^/]+\.(cpp|h)$' \
	-e '^sdk/src/shared/ffx_(assert|message|object_management)\.(cpp|h)$' |
while read -r path; do
	mkdir -p "$DEST/$(dirname "$path")"
	curl -fsSL "https://raw.githubusercontent.com/$REPO/$TAG/$path" -o "$DEST/$path"
done
# The only change to AMD's code: contexts are opaque arrays sized for Windows, where wchar_t (used for the pass names
# they hold) is 2 bytes. It is 4 bytes on macOS, so the context sizes are doubled.
sed -i '' 's/#define FFX_SDK_DEFAULT_CONTEXT_SIZE (1024 \* 128)/#define FFX_SDK_DEFAULT_CONTEXT_SIZE (1024 * 256) \/\/ mine2metal: doubled for 4-byte wchar_t/' \
	"$DEST/sdk/include/FidelityFX/host/ffx_types.h"
sed -i '' 's/#define FFX_FSR1_CONTEXT_SIZE       (27448)/#define FFX_FSR1_CONTEXT_SIZE       (27448 * 2) \/\/ mine2metal: doubled for 4-byte wchar_t/' \
	"$DEST/sdk/include/FidelityFX/host/ffx_fsr1.h"
grep -q 'mine2metal: doubled' "$DEST/sdk/include/FidelityFX/host/ffx_types.h"
grep -q 'mine2metal: doubled' "$DEST/sdk/include/FidelityFX/host/ffx_fsr1.h"
echo "Vendored FidelityFX SDK $TAG into $DEST"
