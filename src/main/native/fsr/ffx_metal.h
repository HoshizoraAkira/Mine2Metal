// A Metal backend for AMD FidelityFX SDK v1.1.4 (third_party/fidelityfx-1.1.4): the FfxInterface its optical flow
// and frame interpolation host code records work through. Compute passes run the shaders translated from AMD's Vulkan
// GLSL by scripts/build-fsr-shaders.sh; their bindings are read from Metal's pipeline reflection by name.
//
// Work goes into the Metal 3 command buffer passed as the FfxCommandList; Metal tracks the hazards between passes.
#ifndef FFX_METAL_H
#define FFX_METAL_H

#include <FidelityFX/host/ffx_interface.h>

#ifdef __OBJC__
#import <Metal/Metal.h>

// Returns the MSL source of a pass (named after its GLSL file, which is also its kernel name), or nil.
typedef NSString *(^FfxMetalShaderSource)(NSString *passName);

size_t ffxGetScratchMemorySizeMetal(void);
// Fills backendInterface; scratch must hold ffxGetScratchMemorySizeMetal() bytes and outlive the contexts made with it.
FfxErrorCode ffxGetInterfaceMetal(FfxInterface *backendInterface, id<MTLDevice> device, FfxMetalShaderSource shaders,
                                  void *scratch, size_t scratchSize);
// Wraps a texture or buffer the caller owns, for the dispatch descriptions.
FfxResource ffxGetResourceMetal(id<MTLResource> resource, FfxResourceDescription description, const wchar_t *name);
FfxCommandList ffxGetCommandListMetal(id<MTLCommandBuffer> commandBuffer);
MTLPixelFormat ffxGetMetalPixelFormat(FfxSurfaceFormat format);
// A texture or buffer matching a description AMD's code asks the application to create (shared resources).
id<MTLResource> ffxCreateResourceMetal(id<MTLDevice> device, const FfxCreateResourceDescription *description);
#endif

#endif
