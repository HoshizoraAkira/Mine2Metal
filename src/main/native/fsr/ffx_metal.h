// A Metal backend for AMD FidelityFX SDK v1.1.4 (third_party/fidelityfx-1.1.4): the FfxInterface its frame
// generation and upscaler host code records work through. Compute passes run the shaders translated from AMD's
// Vulkan GLSL by scripts/build-fsr-shaders.sh; their bindings are read from Metal's pipeline reflection by name.
//
// The backend keeps resources and pipelines; a host records the work. FfxMetalCommandBufferHost records into a plain
// Metal 3 command buffer (the spike tools in tools/fsrbench); the game records through its own Metal 3 / Metal 4
// encoding (fsr.m).
#ifndef FFX_METAL_H
#define FFX_METAL_H

#include <FidelityFX/host/ffx_interface.h>

#ifdef __OBJC__
#import <Metal/Metal.h>

// What FidelityFX work is recorded into. Dispatches within one compute run (begun by setComputePipeline) see what
// the ones before them wrote; the other commands end the compute run.
@protocol FfxMetalHost <NSObject>
// A texture or buffer the backend made: tracked until releaseResource, which waits for the GPU.
- (void)didCreateResource:(id<MTLResource>)resource;
- (void)releaseResource:(id<MTLResource>)resource;
- (void)setComputePipeline:(id<MTLComputePipelineState>)pipeline;
- (void)setTexture:(id<MTLTexture>)texture atIndex:(NSUInteger)index;
- (void)setBuffer:(id<MTLBuffer>)buffer offset:(NSUInteger)offset atIndex:(NSUInteger)index;
- (void)setBytes:(const void *)bytes length:(NSUInteger)length atIndex:(NSUInteger)index;
- (void)setSamplerState:(id<MTLSamplerState>)sampler atIndex:(NSUInteger)index;
- (void)dispatchThreadgroups:(MTLSize)threadgroups threadsPerThreadgroup:(MTLSize)threadsPerThreadgroup;
// Clears every mip level; the backend's own textures can be render targets.
- (void)clearTexture:(id<MTLTexture>)texture color:(MTLClearColor)color;
- (void)fillBuffer:(id<MTLBuffer>)buffer value:(uint8_t)value;
- (void)copyBuffer:(id<MTLBuffer>)source offset:(NSUInteger)sourceOffset toBuffer:(id<MTLBuffer>)destination
            offset:(NSUInteger)destinationOffset size:(NSUInteger)size;
- (void)copyTexture:(id<MTLTexture>)source toTexture:(id<MTLTexture>)destination;
// After the last job of an ExecuteGpuJobs.
- (void)endEncoding;
@optional
// Around each job, with the pass name, for profiling.
- (void)beginJob:(NSString *)label;
- (void)endJob;
@end

// Records into commandBuffer, which the caller sets before each dispatch and commits. With FFX_METAL_PROFILE set, each
// job instead runs in a command buffer of its own on the same queue, committed right away, and GPU time per pass is
// printed every few hundred jobs (correct only when commandBuffer holds nothing else).
@interface FfxMetalCommandBufferHost : NSObject <FfxMetalHost>
@property(nonatomic, strong) id<MTLCommandBuffer> commandBuffer;
@end

// The MSL source of a pass (named after its GLSL file, which is also its kernel name) for the permutation AMD's code
// asks for, or nil.
typedef NSString *(^FfxMetalShaderSource)(NSString *passName, uint32_t permutationOptions);

size_t ffxGetScratchMemorySizeMetal(void);
// Fills backendInterface; scratch must hold ffxGetScratchMemorySizeMetal() bytes and outlive the contexts made with it,
// which ffxDestroyInterfaceMetal frees afterwards.
FfxErrorCode ffxGetInterfaceMetal(FfxInterface *backendInterface, id<MTLDevice> device, id<FfxMetalHost> host,
                                  FfxMetalShaderSource shaders, void *scratch, size_t scratchSize);
void ffxDestroyInterfaceMetal(FfxInterface *backendInterface);
// Wraps a texture or buffer the caller owns (and keeps alive), for the dispatch descriptions.
FfxResource ffxGetResourceMetal(id<MTLResource> resource, FfxResourceDescription description, const wchar_t *name);
FfxCommandList ffxGetCommandListMetal(id<FfxMetalHost> host);
MTLPixelFormat ffxGetMetalPixelFormat(FfxSurfaceFormat format);
// A texture or buffer matching a description AMD's code asks the application to create (shared resources).
id<MTLResource> ffxCreateResourceMetal(id<MTLDevice> device, const FfxCreateResourceDescription *description);
#endif

#endif
