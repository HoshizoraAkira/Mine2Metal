#include "m2m_context.h"

// See m2m_context.h: the Metal 4 branches run only when ctx.metal4 is set, which requires macOS 26.
#pragma clang diagnostic ignored "-Wunguarded-availability-new"

// world_shaders.metal, NUL-terminated; scripts/build-native.sh puts src/main/native on the include path.
__asm__(".section __TEXT,__const\n"
        ".globl _m2m_world_shader_source\n"
        ".private_extern _m2m_world_shader_source\n"
        "_m2m_world_shader_source:\n"
        ".incbin \"world_shaders.metal\"\n"
        ".byte 0\n"
        ".text\n");
extern const char m2m_world_shader_source[];

static const NSUInteger M2M_VOXEL_DIM = 128;
static const NSUInteger M2M_VOXEL_BYTES = M2M_VOXEL_DIM * M2M_VOXEL_DIM * M2M_VOXEL_DIM;
static const int32_t M2M_MAX_POINT_LIGHTS = 32;
static const NSUInteger M2M_BLOOM_LEVELS = 4;
// GPU copies of the voxel grid, filled from the CPU's master copy when it changed: more than the frames in flight,
// so the CPU never writes one the GPU may still read.
static const NSUInteger M2M_VOXEL_COPIES = 4;
static const MTLPixelFormat M2M_GBUFFER_FORMAT = MTLPixelFormatRGBA8Unorm;
static const MTLPixelFormat M2M_BLOOM_FORMAT = MTLPixelFormatRG11B10Float;
// The coarse voxel grid: a byte per 4x4x4 block (COARSE_SHIFT in world_shaders.metal).
static const NSUInteger M2M_COARSE_DIM = 32;
static const NSUInteger M2M_COARSE_BYTES = M2M_COARSE_DIM * M2M_COARSE_DIM * M2M_COARSE_DIM;
// The noise texture: value noise repeating every M2M_NOISE_PERIOD units at M2M_NOISE_TEXELS texels per unit
// (NOISE_PERIOD and NOISE_TEXELS in world_shaders.metal).
static const NSUInteger M2M_NOISE_PERIOD = 32;
static const NSUInteger M2M_NOISE_TEXELS = 4;
// The sky lookup: azimuth across, elevation down.
static const NSUInteger M2M_SKY_LUT_WIDTH = 512;
static const NSUInteger M2M_SKY_LUT_HEIGHT = 256;
// Screen columns per skyline band and pixels per light tile (SKYLINE_BAND and LIGHT_TILE in world_shaders.metal).
static const NSUInteger M2M_SKYLINE_BAND = 16;
static const NSUInteger M2M_SKYLINE_ROWS = 64;
static const NSUInteger M2M_LIGHT_TILE = 32;
// quality[7] flag: the passes read and write the scene through sRGB views (IO_LINEAR in world_shaders.metal).
static const int32_t M2M_IO_LINEAR = 1 << 16;

// ShaderUniforms in world_shaders.metal.
typedef struct {
	float matrices[52];
	float environment[36];
	int32_t quality[8];
} M2MShaderUniforms;

// The post pass's float2: bloom strength, and 1 when the shafts texture was drawn this frame.
typedef struct {
	float bloom;
	float shafts;
} M2MPostStrengths;

typedef struct {
	float pos_radius[4];
	float color_power[4];
} M2MShaderLight;

@interface M2MWorldShaderState : NSObject {
@public
	// The master's version each GPU copy of the voxel grid holds (M2M_VOXEL_COPIES of them); -1 for none yet.
	float voxelVersions[4];
	// Set when a copy was refreshed and its coarse grid is not built yet.
	BOOL coarseDirty[4];
	// The z slices of the master each copy still has to take (m2m_shader_voxels_changed); min > max for none noted.
	int32_t pendingMin[4];
	int32_t pendingMax[4];
}
@property(nonatomic, strong) id<MTLLibrary> library;
@property(nonatomic, strong) id<MTLRenderPipelineState> lightingPipeline;
@property(nonatomic, strong) id<MTLRenderPipelineState> cloudsPipeline;
@property(nonatomic, strong) id<MTLRenderPipelineState> bloomDownPipeline;
@property(nonatomic, strong) id<MTLRenderPipelineState> bloomUpPipeline;
@property(nonatomic, strong) id<MTLRenderPipelineState> shaftsPipeline;
@property(nonatomic, strong) id<MTLRenderPipelineState> skyLutPipeline;
@property(nonatomic, strong) id<MTLComputePipelineState> voxelCoarsePipeline;
@property(nonatomic, strong) id<MTLComputePipelineState> lightTilesPipeline;
@property(nonatomic, strong) id<MTLComputePipelineState> skylineReducePipeline;
@property(nonatomic, strong) id<MTLComputePipelineState> skylineLevelsPipeline;
// By the world's color format.
@property(nonatomic, strong) NSMutableDictionary<NSNumber *, id<MTLRenderPipelineState>> *resolvePipelines;
@property(nonatomic, strong) NSMutableDictionary<NSNumber *, id<MTLRenderPipelineState>> *postPipelines;
@property(nonatomic, strong) NSMutableDictionary<NSNumber *, id<MTLRenderPipelineState>> *waterPipelines;
@property(nonatomic, strong) id<MTLSamplerState> linearSampler;
@property(nonatomic, strong) id<MTLSamplerState> pointSampler;
// Repeating trilinear, for the noise texture; and azimuth repeating, elevation clamped, for the sky lookup.
@property(nonatomic, strong) id<MTLSamplerState> noiseSampler;
@property(nonatomic, strong) id<MTLSamplerState> skySampler;
@property(nonatomic, strong) id<MTLTexture> noiseTexture;
@property(nonatomic, strong) id<MTLTexture> skyLut;
// The coarse grid of each voxel copy (see M2M_COARSE_BYTES).
@property(nonatomic, strong) NSArray<id<MTLBuffer>> *coarseCopies;
// The voxel grid the CPU writes, and the copies the GPU reads (see M2M_VOXEL_COPIES) with the version each holds.
@property(nonatomic, strong) NSMutableData *voxelMaster;
@property(nonatomic, strong) NSArray<id<MTLBuffer>> *voxelCopies;
@property(nonatomic) NSUInteger voxelSlot;
// Half size: the light shafts of the post pass.
@property(nonatomic, strong) id<MTLTexture> shafts;
// Full size dependent: the skyline bands and their range maximum table (water reflections), and the light tiles.
@property(nonatomic, strong) id<MTLBuffer> skylineBands;
@property(nonatomic, strong) id<MTLBuffer> skyline;
@property(nonatomic) NSUInteger skylineBandCount;
@property(nonatomic) NSUInteger skylineLevels;
@property(nonatomic, strong) id<MTLBuffer> lightTiles;
@property(nonatomic) NSUInteger lightTilesX;
@property(nonatomic) NSUInteger lightTilesY;

// Full size: written by the opaque terrain, read by the lighting and resolve passes.
@property(nonatomic, strong) id<MTLTexture> gAlbedo;
@property(nonatomic, strong) id<MTLTexture> gData;
// Light above SDR white from the resolve, and its pyramid from half size down.
@property(nonatomic, strong) id<MTLTexture> bloomSource;
@property(nonatomic, strong) NSArray<id<MTLTexture>> *bloomLevels;
// At the lighting scale, ping-ponged as history.
@property(nonatomic, strong) id<MTLTexture> light0;
@property(nonatomic, strong) id<MTLTexture> light1;
@property(nonatomic, strong) id<MTLTexture> clouds0;
@property(nonatomic, strong) id<MTLTexture> clouds1;
@property(nonatomic) NSUInteger historySlot;
@property(nonatomic) BOOL historyValid;
// The resolve ran since the last post, so bloomSource holds this frame.
@property(nonatomic) BOOL bloomValid;
// The world without its translucent terrain, which the water refracts and reflects; full size, like the world.
@property(nonatomic, strong) id<MTLTexture> opaqueColor;
@property(nonatomic, strong) id<MTLTexture> opaqueDepth;
// sRGB views: of the world's color texture (for the texture it was made from, which changes with the window) and
// of opaqueColor. The hardware then converts the colors the resolve, water and post passes read and write.
@property(nonatomic, strong) id<MTLTexture> colorView;
@property(nonatomic, weak) id<MTLTexture> colorViewSource;
@property(nonatomic, strong) id<MTLTexture> opaqueColorView;
@end

@implementation M2MWorldShaderState
@end

// linear sets function constant 0 (the resolve, water and post passes read and write linear light through sRGB
// views); the other passes do not use it.
static id<MTLRenderPipelineState> m2m_shader_pipeline(M2MContext *ctx, id<MTLLibrary> library, NSString *fragment,
                                                      const MTLPixelFormat *formats, NSUInteger count, BOOL additive, BOOL linear) {
	MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
	desc.label = fragment;
	desc.vertexFunction = [library newFunctionWithName:@"m2m_fullscreen"];
	NSError *error = nil;
	MTLFunctionConstantValues *values = [MTLFunctionConstantValues new];
	bool linearValue = linear != NO;
	[values setConstantValue:&linearValue type:MTLDataTypeBool atIndex:0];
	desc.fragmentFunction = [library newFunctionWithName:fragment constantValues:values error:&error];
	if (!desc.fragmentFunction) {
		NSLog(@"mine2metal: could not specialize the world shader %@: %@", fragment, error);
		return nil;
	}
	for (NSUInteger i = 0; i < count; i++) {
		desc.colorAttachments[i].pixelFormat = formats[i];
		if (additive) {
			desc.colorAttachments[i].blendingEnabled = YES;
			desc.colorAttachments[i].sourceRGBBlendFactor = MTLBlendFactorOne;
			desc.colorAttachments[i].destinationRGBBlendFactor = MTLBlendFactorOne;
			desc.colorAttachments[i].sourceAlphaBlendFactor = MTLBlendFactorOne;
			desc.colorAttachments[i].destinationAlphaBlendFactor = MTLBlendFactorOne;
		}
	}
	id<MTLRenderPipelineState> state = [ctx.device newRenderPipelineStateWithDescriptor:desc error:&error];
	if (!state) {
		NSLog(@"mine2metal: could not create the world shader pipeline %@: %@", fragment, error);
	}
	return state;
}

// hash13 of world_shaders.metal at an integer lattice point.
static float m2m_lattice_hash(NSUInteger x, NSUInteger y, NSUInteger z) {
	float px = (float)x * 0.1031f, py = (float)y * 0.1030f, pz = (float)z * 0.0973f;
	px -= floorf(px);
	py -= floorf(py);
	pz -= floorf(pz);
	float dp = px * (py + 33.33f) + py * (px + 33.33f) + pz * (pz + 33.33f);
	px += dp;
	py += dp;
	pz += dp;
	float v = (px + py) * pz;
	return v - floorf(v);
}

// The noise texture: the value noise the shaders used to compute per sample, with a hash that repeats every
// M2M_NOISE_PERIOD lattice units, evaluated M2M_NOISE_TEXELS times per unit so trilinear sampling keeps it smooth.
static id<MTLTexture> m2m_noise_texture(M2MContext *ctx) {
	NSUInteger period = M2M_NOISE_PERIOD, texels = M2M_NOISE_TEXELS, n = period * texels;
	MTLTextureDescriptor *descriptor = [MTLTextureDescriptor new];
	descriptor.textureType = MTLTextureType3D;
	descriptor.pixelFormat = MTLPixelFormatR16Unorm;
	descriptor.width = n;
	descriptor.height = n;
	descriptor.depth = n;
	descriptor.usage = MTLTextureUsageShaderRead;
	id<MTLTexture> texture = [ctx.device newTextureWithDescriptor:descriptor];
	if (!texture) {
		return nil;
	}
	float *lattice = malloc(period * period * period * sizeof(float));
	uint16_t *data = malloc(n * n * n * sizeof(uint16_t));
	for (NSUInteger z = 0; z < period; z++) {
		for (NSUInteger y = 0; y < period; y++) {
			for (NSUInteger x = 0; x < period; x++) {
				lattice[(z * period + y) * period + x] = m2m_lattice_hash(x, y, z);
			}
		}
	}
	for (NSUInteger z = 0; z < n; z++) {
		for (NSUInteger y = 0; y < n; y++) {
			for (NSUInteger x = 0; x < n; x++) {
				float fx = ((float)x + 0.5f) / texels, fy = ((float)y + 0.5f) / texels, fz = ((float)z + 0.5f) / texels;
				NSUInteger ix = (NSUInteger)fx, iy = (NSUInteger)fy, iz = (NSUInteger)fz;
				float tx = fx - ix, ty = fy - iy, tz = fz - iz;
				tx = tx * tx * (3.0f - 2.0f * tx);
				ty = ty * ty * (3.0f - 2.0f * ty);
				tz = tz * tz * (3.0f - 2.0f * tz);
				float c[8];
				for (int k = 0; k < 8; k++) {
					NSUInteger cx = (ix + (k & 1)) % period, cy = (iy + ((k >> 1) & 1)) % period, cz = (iz + (k >> 2)) % period;
					c[k] = lattice[(cz * period + cy) * period + cx];
				}
				float v = ((c[0] * (1 - tx) + c[1] * tx) * (1 - ty) + (c[2] * (1 - tx) + c[3] * tx) * ty) * (1 - tz)
				        + ((c[4] * (1 - tx) + c[5] * tx) * (1 - ty) + (c[6] * (1 - tx) + c[7] * tx) * ty) * tz;
				data[(z * n + y) * n + x] = (uint16_t)(v * 65535.0f + 0.5f);
			}
		}
	}
	[texture replaceRegion:MTLRegionMake3D(0, 0, 0, n, n, n) mipmapLevel:0 slice:0 withBytes:data bytesPerRow:n * 2 bytesPerImage:n * n * 2];
	free(data);
	free(lattice);
	m2m_track(ctx, texture);
	return texture;
}

static id<MTLComputePipelineState> m2m_shader_kernel(M2MContext *ctx, id<MTLLibrary> library, NSString *name) {
	NSError *error = nil;
	id<MTLFunction> function = [library newFunctionWithName:name];
	id<MTLComputePipelineState> state = function ? [ctx.device newComputePipelineStateWithFunction:function error:&error] : nil;
	if (!state) {
		NSLog(@"mine2metal: could not create the world shader kernel %@: %@", name, error);
	}
	return state;
}

static M2MWorldShaderState *m2m_shader_state(M2MContext *ctx) {
	M2MWorldShaderState *state = ctx.worldShader;
	if (state) {
		return state;
	}
	NSError *error = nil;
	MTLCompileOptions *options = [MTLCompileOptions new];
	options.languageVersion = MTLLanguageVersion3_0;
	options.mathMode = MTLMathModeFast;
	id<MTLLibrary> library = [ctx.device newLibraryWithSource:@(m2m_world_shader_source) options:options error:&error];
	if (!library) {
		NSLog(@"mine2metal: could not compile the world shaders: %@", error);
		return nil;
	}

	state = [M2MWorldShaderState new];
	state.library = library;
	state.resolvePipelines = [NSMutableDictionary new];
	state.postPipelines = [NSMutableDictionary new];
	state.waterPipelines = [NSMutableDictionary new];

	MTLPixelFormat lighting = MTLPixelFormatRGBA16Float;
	state.lightingPipeline = m2m_shader_pipeline(ctx, library, @"m2m_lighting", &lighting, 1, NO, NO);
	state.cloudsPipeline = m2m_shader_pipeline(ctx, library, @"m2m_clouds", &lighting, 1, NO, NO);
	MTLPixelFormat bloom = M2M_BLOOM_FORMAT;
	state.bloomDownPipeline = m2m_shader_pipeline(ctx, library, @"m2m_bloom_down", &bloom, 1, NO, NO);
	state.bloomUpPipeline = m2m_shader_pipeline(ctx, library, @"m2m_bloom_up", &bloom, 1, YES, NO);
	state.shaftsPipeline = m2m_shader_pipeline(ctx, library, @"m2m_shafts", &bloom, 1, NO, NO);
	MTLPixelFormat lut = MTLPixelFormatRGBA16Float;
	state.skyLutPipeline = m2m_shader_pipeline(ctx, library, @"m2m_sky_lut", &lut, 1, NO, NO);
	state.voxelCoarsePipeline = m2m_shader_kernel(ctx, library, @"m2m_voxel_coarse");
	state.lightTilesPipeline = m2m_shader_kernel(ctx, library, @"m2m_light_tiles");
	state.skylineReducePipeline = m2m_shader_kernel(ctx, library, @"m2m_skyline_reduce");
	state.skylineLevelsPipeline = m2m_shader_kernel(ctx, library, @"m2m_skyline_levels");
	if (!state.lightingPipeline || !state.cloudsPipeline || !state.bloomDownPipeline || !state.bloomUpPipeline || !state.shaftsPipeline ||
	    !state.skyLutPipeline || !state.voxelCoarsePipeline || !state.lightTilesPipeline || !state.skylineReducePipeline ||
	    !state.skylineLevelsPipeline) {
		return nil;
	}

	MTLSamplerDescriptor *samplerDesc = [MTLSamplerDescriptor new];
	samplerDesc.sAddressMode = MTLSamplerAddressModeClampToEdge;
	samplerDesc.tAddressMode = MTLSamplerAddressModeClampToEdge;
	samplerDesc.minFilter = MTLSamplerMinMagFilterLinear;
	samplerDesc.magFilter = MTLSamplerMinMagFilterLinear;
	state.linearSampler = [ctx.device newSamplerStateWithDescriptor:samplerDesc];
	samplerDesc.minFilter = MTLSamplerMinMagFilterNearest;
	samplerDesc.magFilter = MTLSamplerMinMagFilterNearest;
	state.pointSampler = [ctx.device newSamplerStateWithDescriptor:samplerDesc];
	samplerDesc.minFilter = MTLSamplerMinMagFilterLinear;
	samplerDesc.magFilter = MTLSamplerMinMagFilterLinear;
	samplerDesc.sAddressMode = MTLSamplerAddressModeRepeat;
	samplerDesc.tAddressMode = MTLSamplerAddressModeRepeat;
	samplerDesc.rAddressMode = MTLSamplerAddressModeRepeat;
	state.noiseSampler = [ctx.device newSamplerStateWithDescriptor:samplerDesc];
	samplerDesc.tAddressMode = MTLSamplerAddressModeClampToEdge;
	samplerDesc.rAddressMode = MTLSamplerAddressModeClampToEdge;
	state.skySampler = [ctx.device newSamplerStateWithDescriptor:samplerDesc];
	state.noiseTexture = m2m_noise_texture(ctx);
	state.skyLut = m2m_new_private_texture(ctx, lut, M2M_SKY_LUT_WIDTH, M2M_SKY_LUT_HEIGHT, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
	if (!state.noiseTexture || !state.skyLut) {
		return nil;
	}

	state.voxelMaster = [NSMutableData dataWithLength:M2M_VOXEL_BYTES];
	NSMutableArray *copies = [NSMutableArray arrayWithCapacity:M2M_VOXEL_COPIES];
	NSMutableArray *coarse = [NSMutableArray arrayWithCapacity:M2M_VOXEL_COPIES];
	for (NSUInteger i = 0; i < M2M_VOXEL_COPIES; i++) {
		id<MTLBuffer> copy = [ctx.device newBufferWithLength:M2M_VOXEL_BYTES options:MTLResourceStorageModeShared];
		id<MTLBuffer> coarseCopy = [ctx.device newBufferWithLength:M2M_COARSE_BYTES options:MTLResourceStorageModePrivate];
		if (!copy || !coarseCopy) {
			return nil;
		}
		m2m_track(ctx, copy);
		m2m_track(ctx, coarseCopy);
		[copies addObject:copy];
		[coarse addObject:coarseCopy];
		state->voxelVersions[i] = -1.0f;
		state->coarseDirty[i] = YES;
		state->pendingMin[i] = 0;
		state->pendingMax[i] = (int32_t)M2M_VOXEL_DIM - 1;
	}
	state.voxelCopies = copies;
	state.coarseCopies = coarse;

	ctx.worldShader = state;
	return state;
}

// By the color format, which with an sRGB variant also decides the linear I/O specialization.
static id<MTLRenderPipelineState> m2m_shader_cached_pipeline(M2MContext *ctx, M2MWorldShaderState *state,
                                                             NSMutableDictionary *cache, NSString *fragment,
                                                             MTLPixelFormat colorFormat, BOOL withBloom) {
	BOOL linear = colorFormat == MTLPixelFormatRGBA8Unorm_sRGB || colorFormat == MTLPixelFormatBGRA8Unorm_sRGB;
	NSNumber *key = @((NSUInteger)colorFormat);
	id<MTLRenderPipelineState> pipeline = cache[key];
	if (!pipeline) {
		MTLPixelFormat formats[2] = {colorFormat, M2M_BLOOM_FORMAT};
		pipeline = m2m_shader_pipeline(ctx, state.library, fragment, formats, withBloom ? 2 : 1, NO, linear);
		if (pipeline) {
			cache[key] = pipeline;
		}
	}
	return pipeline;
}

static void m2m_shader_retire(M2MContext *ctx, M2MWorldShaderState *state, BOOL fullSize, BOOL lightSize) {
	if (fullSize) {
		m2m_retire(ctx, state.gAlbedo);
		m2m_retire(ctx, state.gData);
		m2m_retire(ctx, state.bloomSource);
		for (id<MTLTexture> level in state.bloomLevels) {
			m2m_retire(ctx, level);
		}
		state.gAlbedo = nil;
		state.gData = nil;
		state.bloomSource = nil;
		state.bloomLevels = nil;
		state.bloomValid = NO;
		m2m_retire(ctx, state.opaqueColor);
		m2m_retire(ctx, state.opaqueDepth);
		m2m_retire(ctx, state.opaqueColorView);
		m2m_retire(ctx, state.colorView);
		state.opaqueColor = nil;
		state.opaqueDepth = nil;
		state.opaqueColorView = nil;
		state.colorView = nil;
		state.colorViewSource = nil;
		m2m_retire(ctx, state.skylineBands);
		m2m_retire(ctx, state.skyline);
		m2m_retire(ctx, state.lightTiles);
		state.skylineBands = nil;
		state.skyline = nil;
		state.lightTiles = nil;
	}
	if (lightSize) {
		m2m_retire(ctx, state.light0);
		m2m_retire(ctx, state.light1);
		m2m_retire(ctx, state.clouds0);
		m2m_retire(ctx, state.clouds1);
		state.light0 = nil;
		state.light1 = nil;
		state.clouds0 = nil;
		state.clouds1 = nil;
		state.historyValid = NO;
	}
}

static BOOL m2m_shader_ensure_full_size(M2MContext *ctx, M2MWorldShaderState *state, NSUInteger width, NSUInteger height) {
	if (state.gAlbedo && state.gAlbedo.width == width && state.gAlbedo.height == height) {
		return YES;
	}
	m2m_shader_retire(ctx, state, YES, NO);
	MTLTextureUsage usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
	state.gAlbedo = m2m_new_private_texture(ctx, M2M_GBUFFER_FORMAT, width, height, usage);
	state.gData = m2m_new_private_texture(ctx, M2M_GBUFFER_FORMAT, width, height, usage);
	state.bloomSource = m2m_new_private_texture(ctx, M2M_BLOOM_FORMAT, width, height, usage);
	NSMutableArray *levels = [NSMutableArray arrayWithCapacity:M2M_BLOOM_LEVELS];
	for (NSUInteger i = 0; i < M2M_BLOOM_LEVELS; i++) {
		id<MTLTexture> level = m2m_new_private_texture(ctx, M2M_BLOOM_FORMAT, MAX(1u, width >> (i + 1)),
		                                               MAX(1u, height >> (i + 1)), usage);
		if (!level) {
			break;
		}
		[levels addObject:level];
	}
	state.bloomLevels = levels;

	NSUInteger bands = (width + M2M_SKYLINE_BAND - 1) / M2M_SKYLINE_BAND;
	NSUInteger skylineLevels = 1;
	while ((1u << skylineLevels) <= bands) {
		skylineLevels++;
	}
	state.skylineBandCount = bands;
	state.skylineLevels = skylineLevels;
	state.skylineBands = [ctx.device newBufferWithLength:bands * sizeof(uint32_t) options:MTLResourceStorageModePrivate];
	state.skyline = [ctx.device newBufferWithLength:bands * skylineLevels * sizeof(uint32_t) options:MTLResourceStorageModePrivate];
	state.lightTilesX = (width + M2M_LIGHT_TILE - 1) / M2M_LIGHT_TILE;
	state.lightTilesY = (height + M2M_LIGHT_TILE - 1) / M2M_LIGHT_TILE;
	state.lightTiles = [ctx.device newBufferWithLength:state.lightTilesX * state.lightTilesY * sizeof(uint32_t) options:MTLResourceStorageModePrivate];
	m2m_track(ctx, state.skylineBands);
	m2m_track(ctx, state.skyline);
	m2m_track(ctx, state.lightTiles);
	return state.gAlbedo && state.gData && state.bloomSource && levels.count == M2M_BLOOM_LEVELS && state.skylineBands && state.skyline &&
	       state.lightTiles;
}

static BOOL m2m_shader_ensure_light_size(M2MContext *ctx, M2MWorldShaderState *state, NSUInteger width, NSUInteger height) {
	if (state.light0 && state.light0.width == width && state.light0.height == height) {
		return YES;
	}
	m2m_shader_retire(ctx, state, NO, YES);
	MTLTextureUsage usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
	state.light0 = m2m_new_private_texture(ctx, MTLPixelFormatRGBA16Float, width, height, usage);
	state.light1 = m2m_new_private_texture(ctx, MTLPixelFormatRGBA16Float, width, height, usage);
	// Clouds are soft and accumulate over frames: half the lighting scale is plenty.
	NSUInteger cloudWidth = MAX(1u, width / 2);
	NSUInteger cloudHeight = MAX(1u, height / 2);
	state.clouds0 = m2m_new_private_texture(ctx, MTLPixelFormatRGBA16Float, cloudWidth, cloudHeight, usage);
	state.clouds1 = m2m_new_private_texture(ctx, MTLPixelFormatRGBA16Float, cloudWidth, cloudHeight, usage);
	return state.light0 && state.light1 && state.clouds0 && state.clouds1;
}

// The sRGB variant of an 8-bit unorm format, or MTLPixelFormatInvalid.
static MTLPixelFormat m2m_srgb_variant(MTLPixelFormat format) {
	switch (format) {
	case MTLPixelFormatRGBA8Unorm:
		return MTLPixelFormatRGBA8Unorm_sRGB;
	case MTLPixelFormatBGRA8Unorm:
		return MTLPixelFormatBGRA8Unorm_sRGB;
	default:
		return MTLPixelFormatInvalid;
	}
}

// The texture the world passes draw into: an sRGB view of color when its format has one, else color itself. Whether
// it is a view decides the IO_LINEAR flag of the frame's uniforms.
static id<MTLTexture> m2m_shader_color_target(M2MWorldShaderState *state, id<MTLTexture> color) {
	if (state.colorViewSource == color && state.colorView) {
		return state.colorView;
	}
	MTLPixelFormat format = m2m_srgb_variant(color.pixelFormat);
	id<MTLTexture> view = format != MTLPixelFormatInvalid ? [color newTextureViewWithPixelFormat:format] : nil;
	state.colorView = view;
	state.colorViewSource = view ? color : nil;
	return view ?: color;
}

static BOOL m2m_has_stencil(MTLPixelFormat format) {
	return format == MTLPixelFormatDepth32Float_Stencil8 || format == MTLPixelFormatX32_Stencil8 ||
	       format == MTLPixelFormatStencil8;
}

static void m2m_shader_fill_uniforms(M2MShaderUniforms *uniforms, const int32_t *quality, const float *matrices,
                                     const float *environment, int32_t light_count, BOOL history, BOOL linear) {
	memcpy(uniforms->matrices, matrices, sizeof(uniforms->matrices));
	memcpy(uniforms->environment, environment, sizeof(uniforms->environment));
	for (int i = 0; i < 7; i++) {
		uniforms->quality[i] = quality[i];
	}
	uniforms->quality[7] = light_count | (linear ? M2M_IO_LINEAR : 0);
	if (!history) {
		uniforms->matrices[35] = 0.0f;
	}
}

static void m2m_shader_draw(M2MContext *ctx, MTLRenderPassDescriptor *pass, NSString *label,
                            id<MTLRenderPipelineState> pipeline, void (^bind)(void)) {
	id<M2MRenderCommands> encoder = m2m_open_render_encoder(ctx, pass, label);
	[encoder setRenderPipelineState:pipeline];
	bind();
	[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
}

// With M2M_SHADER_TIMES set, each stage is committed on its own so the pass timings (-Dmine2metal.passTimes) show it.
static void m2m_shader_time(int64_t handle, const char *label) {
	static int enabled = -1;
	if (enabled < 0) {
		enabled = getenv("M2M_SHADER_TIMES") != NULL;
	}
	if (enabled) {
		m2m_context_commit(handle, label);
	}
}

static MTLRenderPassDescriptor *m2m_shader_pass(id<MTLTexture> target, MTLLoadAction load) {
	MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
	pass.colorAttachments[0].texture = target;
	pass.colorAttachments[0].loadAction = load;
	pass.colorAttachments[0].storeAction = MTLStoreActionStore;
	return pass;
}

// This frame's GPU copy of the voxel grid: the next one in turn, brought up to the master's version if it is behind.
static id<MTLBuffer> m2m_shader_voxels(M2MWorldShaderState *state, float version) {
	NSUInteger slot = state.voxelSlot;
	state.voxelSlot = (slot + 1) % M2M_VOXEL_COPIES;
	id<MTLBuffer> copy = state.voxelCopies[slot];
	if (state->voxelVersions[slot] != version) {
		// Only the z slices that changed since this copy was last refreshed (a slice is 128 x 128 contiguous bytes).
		NSUInteger sliceBytes = M2M_VOXEL_DIM * M2M_VOXEL_DIM;
		int32_t first = state->pendingMin[slot];
		int32_t last = state->pendingMax[slot];
		if (first > last) {
			first = 0;
			last = (int32_t)M2M_VOXEL_DIM - 1;
		}
		NSUInteger offset = (NSUInteger)first * sliceBytes;
		memcpy((char *)copy.contents + offset, (const char *)state.voxelMaster.bytes + offset, (NSUInteger)(last - first + 1) * sliceBytes);
		state->voxelVersions[slot] = version;
		state->coarseDirty[slot] = YES;
	}
	state->pendingMin[slot] = (int32_t)M2M_VOXEL_DIM;
	state->pendingMax[slot] = -1;
	return copy;
}

void m2m_shader_voxels_changed(int64_t handle, int32_t z_min, int32_t z_max) {
	M2MWorldShaderState *state = m2m_ctx(handle).worldShader;
	if (!state) {
		return;
	}
	z_min = MAX(0, z_min);
	z_max = MIN((int32_t)M2M_VOXEL_DIM - 1, z_max);
	for (NSUInteger i = 0; i < M2M_VOXEL_COPIES; i++) {
		state->pendingMin[i] = MIN(state->pendingMin[i], z_min);
		state->pendingMax[i] = MAX(state->pendingMax[i], z_max);
	}
}

// The coarse grid of the voxel copy in use this frame, rebuilt by the GPU when the copy was refreshed. Ends the open
// encoder.
static id<MTLBuffer> m2m_shader_coarse(M2MContext *ctx, M2MWorldShaderState *state, id<MTLBuffer> voxels) {
	NSUInteger slot = [state.voxelCopies indexOfObjectIdenticalTo:voxels];
	id<MTLBuffer> coarse = state.coarseCopies[slot];
	if (state->coarseDirty[slot]) {
		state->coarseDirty[slot] = NO;
		m2m_compute_begin(ctx, state.voxelCoarsePipeline);
		m2m_bind_buffer(ctx, 2, voxels, 0, NO, NO);
		m2m_bind_buffer(ctx, 5, coarse, 0, NO, NO);
		m2m_compute_dispatch(ctx, MTLSizeMake(M2M_COARSE_DIM / 8, M2M_COARSE_DIM / 8, M2M_COARSE_DIM / 8), MTLSizeMake(8, 8, 8));
		m2m_end_encoding(ctx);
	}
	return coarse;
}

// Opens the main world pass again on color and depth, with the G-buffer (cleared) as attachments 1 and 2 if asked.
static void m2m_shader_reopen_main(M2MContext *ctx, M2MWorldShaderState *state, id<MTLTexture> color, id<MTLTexture> depth,
                                   BOOL gbuffer, NSString *label) {
	MTLRenderPassDescriptor *main = m2m_shader_pass(color, MTLLoadActionLoad);
	main.depthAttachment.texture = depth;
	main.depthAttachment.loadAction = MTLLoadActionLoad;
	main.depthAttachment.storeAction = MTLStoreActionStore;
	if (m2m_has_stencil(depth.pixelFormat)) {
		main.stencilAttachment.texture = depth;
		main.stencilAttachment.loadAction = MTLLoadActionLoad;
		main.stencilAttachment.storeAction = MTLStoreActionStore;
	}
	if (gbuffer) {
		id<MTLTexture> targets[2] = {state.gAlbedo, state.gData};
		for (NSUInteger i = 0; i < 2; i++) {
			main.colorAttachments[i + 1].texture = targets[i];
			main.colorAttachments[i + 1].loadAction = MTLLoadActionClear;
			main.colorAttachments[i + 1].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 0.0);
			main.colorAttachments[i + 1].storeAction = MTLStoreActionStore;
		}
	}
	m2m_open_pass_encoder(ctx, main, label);
}

int64_t m2m_shader_voxel_buffer(int64_t handle) {
	@autoreleasepool {
		M2MWorldShaderState *state = m2m_shader_state(m2m_ctx(handle));
		return state ? (int64_t)(intptr_t)state.voxelMaster.mutableBytes : 0;
	}
}

int32_t m2m_shader_gbuffer_attach(int64_t handle, int64_t color_handle) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		id<MTLTexture> color = m2m_borrow(color_handle);
		M2MWorldShaderState *state = m2m_shader_state(ctx);
		if (!state || !color || !m2m_shader_ensure_full_size(ctx, state, color.width, color.height)) {
			return 0;
		}
		MTLRenderPassDescriptor *pass = m2m_pending_pass(ctx);
		id<MTLTexture> targets[2] = {state.gAlbedo, state.gData};
		for (NSUInteger i = 0; i < 2; i++) {
			MTLRenderPassColorAttachmentDescriptor *attachment = pass.colorAttachments[i + 1];
			attachment.texture = targets[i];
			attachment.loadAction = MTLLoadActionClear;
			attachment.clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 0.0);
			attachment.storeAction = MTLStoreActionStore;
		}
		return 1;
	}
}

int32_t m2m_shader_deferred(int64_t handle, int64_t color_handle, int64_t depth_handle, const int32_t *quality,
                            const float *matrices, const float *environment, const float *lights, int32_t light_count) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		id<MTLTexture> color = m2m_borrow(color_handle);
		id<MTLTexture> depth = m2m_borrow(depth_handle);
		M2MWorldShaderState *state = ctx.worldShader;
		if (!state || !color || !depth || !state.gAlbedo || state.gAlbedo.width != color.width ||
		    state.gAlbedo.height != color.height || !m2m_render_encoder(ctx)) {
			return 0;
		}
		id<MTLTexture> target = m2m_shader_color_target(state, color);
		id<MTLRenderPipelineState> resolve =
			m2m_shader_cached_pipeline(ctx, state, state.resolvePipelines, @"m2m_resolve", target.pixelFormat, YES);
		if (!resolve) {
			return 0;
		}

		int32_t scale = MAX(50, MIN(100, quality[6]));
		NSUInteger lightWidth = MAX(1u, color.width * (NSUInteger)scale / 100u);
		NSUInteger lightHeight = MAX(1u, color.height * (NSUInteger)scale / 100u);
		// The lighting pass gains little above 1200 rows; past that it only costs.
		NSUInteger maxHeight = 1200u * (NSUInteger)scale / 100u;
		if (lightHeight > maxHeight) {
			lightWidth = MAX(1u, color.width * maxHeight / color.height);
			lightHeight = maxHeight;
		}
		if (!m2m_shader_ensure_light_size(ctx, state, lightWidth, lightHeight)) {
			return 0;
		}

		M2MShaderUniforms uniforms;
		int32_t lightCount = lights ? MAX(0, MIN(M2M_MAX_POINT_LIGHTS, light_count)) : 0;
		// environment 34: the version of the voxel grid's master copy.
		id<MTLBuffer> voxels = m2m_shader_voxels(state, environment[34]);
		m2m_shader_fill_uniforms(&uniforms, quality, matrices, environment, lightCount, state.historyValid, target != color);
		M2MShaderLight none = {0};
		const void *lightBytes = lightCount > 0 ? (const void *)lights : (const void *)&none;
		NSUInteger lightLength = lightCount > 0 ? (NSUInteger)lightCount * sizeof(M2MShaderLight) : sizeof(none);

		id<MTLTexture> previousLight = state.historySlot == 0 ? state.light1 : state.light0;
		id<MTLTexture> currentLight = state.historySlot == 0 ? state.light0 : state.light1;
		id<MTLTexture> previousClouds = state.historySlot == 0 ? state.clouds1 : state.clouds0;
		id<MTLTexture> currentClouds = state.historySlot == 0 ? state.clouds0 : state.clouds1;

		// Ends the main pass: from here on it must be reopened, whatever happens.
		m2m_end_encoding(ctx);
		m2m_shader_time(handle, "opaque terrain (G-buffer)");

		id<MTLBuffer> coarse = m2m_shader_coarse(ctx, state, voxels);
		// The sky's color by direction, for this frame's sun and weather.
		m2m_shader_draw(ctx, m2m_shader_pass(state.skyLut, MTLLoadActionDontCare), @"mine2metal shader sky lookup", state.skyLutPipeline, ^{
			m2m_bind_texture(ctx, 0, state.skyLut, NO, YES);
			m2m_bind_bytes(ctx, 0, &uniforms, sizeof(uniforms), NO, YES);
		});

		// Clouds only over the overworld's sky (environment 12 is the dimension).
		if (quality[4] > 0 && environment[12] < 0.5f) {
			m2m_shader_draw(ctx, m2m_shader_pass(currentClouds, MTLLoadActionDontCare), @"mine2metal shader clouds", state.cloudsPipeline, ^{
				m2m_bind_texture(ctx, 0, depth, NO, YES);
				m2m_bind_texture(ctx, 3, previousClouds, NO, YES);
				m2m_bind_texture(ctx, 7, state.noiseTexture, NO, YES);
				m2m_bind_texture(ctx, 8, state.skyLut, NO, YES);
				m2m_bind_sampler(ctx, 0, state.linearSampler, NO, YES);
				m2m_bind_sampler(ctx, 2, state.noiseSampler, NO, YES);
				m2m_bind_sampler(ctx, 3, state.skySampler, NO, YES);
				m2m_bind_bytes(ctx, 0, &uniforms, sizeof(uniforms), NO, YES);
			});
		} else {
			// Cleared, so the resolve reads no clouds rather than a texture that was never written.
			m2m_open_render_encoder(ctx, m2m_shader_pass(currentClouds, MTLLoadActionClear), @"mine2metal shader no clouds");
		}
		m2m_shader_time(handle, "shader clouds");

		m2m_shader_draw(ctx, m2m_shader_pass(currentLight, MTLLoadActionDontCare), @"mine2metal shader lighting", state.lightingPipeline, ^{
			m2m_bind_texture(ctx, 0, depth, NO, YES);
			m2m_bind_texture(ctx, 1, state.gData, NO, YES);
			m2m_bind_texture(ctx, 2, previousLight, NO, YES);
			m2m_bind_texture(ctx, 4, state.gAlbedo, NO, YES);
			m2m_bind_texture(ctx, 7, state.noiseTexture, NO, YES);
			m2m_bind_sampler(ctx, 0, state.linearSampler, NO, YES);
			m2m_bind_sampler(ctx, 1, state.pointSampler, NO, YES);
			m2m_bind_sampler(ctx, 2, state.noiseSampler, NO, YES);
			m2m_bind_bytes(ctx, 0, &uniforms, sizeof(uniforms), NO, YES);
			m2m_bind_buffer(ctx, 2, voxels, 0, NO, YES);
			m2m_bind_buffer(ctx, 5, coarse, 0, NO, YES);
		});
		m2m_shader_time(handle, "shader lighting");

		// Which lights can reach each tile of the screen; the resolve reads the tiles only where there is block light.
		if (lightCount > 0) {
			m2m_end_encoding(ctx);
			m2m_compute_begin(ctx, state.lightTilesPipeline);
			m2m_bind_texture(ctx, 0, depth, NO, NO);
			m2m_bind_bytes(ctx, 0, &uniforms, sizeof(uniforms), NO, NO);
			m2m_bind_bytes(ctx, 1, lightBytes, lightLength, NO, NO);
			m2m_bind_buffer(ctx, 4, state.lightTiles, 0, NO, NO);
			m2m_compute_dispatch(ctx, MTLSizeMake(state.lightTilesX, state.lightTilesY, 1), MTLSizeMake(M2M_LIGHT_TILE / 2, M2M_LIGHT_TILE / 2, 1));
			m2m_end_encoding(ctx);
			m2m_shader_time(handle, "shader light tiles");
		}

		// Reads the sky vanilla drew from the attachment itself, so it needs no copy of the world.
		MTLRenderPassDescriptor *resolvePass = m2m_shader_pass(target, MTLLoadActionLoad);
		resolvePass.colorAttachments[1].texture = state.bloomSource;
		resolvePass.colorAttachments[1].loadAction = MTLLoadActionDontCare;
		resolvePass.colorAttachments[1].storeAction = MTLStoreActionStore;
		m2m_shader_draw(ctx, resolvePass, @"mine2metal shader resolve", resolve, ^{
			m2m_bind_texture(ctx, 0, depth, NO, YES);
			m2m_bind_texture(ctx, 1, state.gAlbedo, NO, YES);
			m2m_bind_texture(ctx, 2, state.gData, NO, YES);
			m2m_bind_texture(ctx, 3, currentLight, NO, YES);
			m2m_bind_texture(ctx, 4, currentClouds, NO, YES);
			m2m_bind_texture(ctx, 7, state.noiseTexture, NO, YES);
			m2m_bind_texture(ctx, 8, state.skyLut, NO, YES);
			m2m_bind_sampler(ctx, 0, state.linearSampler, NO, YES);
			m2m_bind_sampler(ctx, 2, state.noiseSampler, NO, YES);
			m2m_bind_sampler(ctx, 3, state.skySampler, NO, YES);
			m2m_bind_bytes(ctx, 0, &uniforms, sizeof(uniforms), NO, YES);
			m2m_bind_bytes(ctx, 1, lightBytes, lightLength, NO, YES);
			m2m_bind_buffer(ctx, 2, voxels, 0, NO, YES);
			m2m_bind_buffer(ctx, 4, state.lightTiles, 0, NO, YES);
			m2m_bind_buffer(ctx, 5, coarse, 0, NO, YES);
		});
		m2m_shader_time(handle, "shader resolve");

		// The rest of the world (entities, water, particles) continues in the main pass.
		m2m_shader_reopen_main(ctx, state, color, depth, NO, @"mine2metal world after deferred lighting");

		state.historySlot = 1 - state.historySlot;
		state.historyValid = YES;
		state.bloomValid = YES;
		return 1;
	}
}

int32_t m2m_shader_water_begin(int64_t handle, int64_t color_handle, int64_t depth_handle) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		id<MTLTexture> color = m2m_borrow(color_handle);
		id<MTLTexture> depth = m2m_borrow(depth_handle);
		M2MWorldShaderState *state = ctx.worldShader;
		if (!state || !color || !depth || !state.gAlbedo || state.gAlbedo.width != color.width ||
		    state.gAlbedo.height != color.height || !m2m_render_encoder(ctx)) {
			return 0;
		}
		if (!state.opaqueColor || state.opaqueColor.pixelFormat != color.pixelFormat || state.opaqueColor.width != color.width ||
		    state.opaqueColor.height != color.height || state.opaqueDepth.pixelFormat != depth.pixelFormat) {
			m2m_retire(ctx, state.opaqueColor);
			m2m_retire(ctx, state.opaqueDepth);
			m2m_retire(ctx, state.opaqueColorView);
			state.opaqueColorView = nil;
			state.opaqueColor = m2m_new_private_texture(ctx, color.pixelFormat, color.width, color.height, MTLTextureUsageShaderRead);
			state.opaqueDepth = m2m_new_private_texture(ctx, depth.pixelFormat, depth.width, depth.height, MTLTextureUsageShaderRead);
			if (!state.opaqueColor || !state.opaqueDepth) {
				return 0;
			}
			MTLPixelFormat srgb = m2m_srgb_variant(color.pixelFormat);
			state.opaqueColorView = srgb != MTLPixelFormatInvalid ? [state.opaqueColor newTextureViewWithPixelFormat:srgb] : nil;
		}
		if (!m2m_shader_cached_pipeline(ctx, state, state.waterPipelines, @"m2m_water", m2m_shader_color_target(state, color).pixelFormat, NO)) {
			return 0;
		}

		m2m_end_encoding(ctx);
		id<M2MCopier> copier = m2m_copier(ctx);
		[copier copyFromTexture:color toTexture:state.opaqueColor];
		[copier copyFromTexture:depth toTexture:state.opaqueDepth];
		m2m_shader_reopen_main(ctx, state, color, depth, YES, @"mine2metal translucent terrain (G-buffer)");
		return 1;
	}
}

int32_t m2m_shader_water_end(int64_t handle, int64_t color_handle, int64_t depth_handle, const int32_t *quality,
                             const float *matrices, const float *environment) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		id<MTLTexture> color = m2m_borrow(color_handle);
		id<MTLTexture> depth = m2m_borrow(depth_handle);
		M2MWorldShaderState *state = ctx.worldShader;
		id<MTLTexture> target = state ? m2m_shader_color_target(state, color) : color;
		// Linear in and out only when both the target and the scene copy have sRGB views.
		BOOL linear = state && target != color && state.opaqueColorView != nil;
		id<MTLRenderPipelineState> water =
			state ? m2m_shader_cached_pipeline(ctx, state, state.waterPipelines, @"m2m_water", (linear ? target : color).pixelFormat, NO) : nil;
		if (!water || !state.opaqueColor || !m2m_render_encoder(ctx)) {
			return 0;
		}
		M2MShaderUniforms uniforms;
		m2m_shader_fill_uniforms(&uniforms, quality, matrices, environment, 0, YES, linear);

		m2m_end_encoding(ctx);
		m2m_shader_time(handle, "translucent terrain (G-buffer)");

		// The skyline of the opaque scene, which lets reflected rays stop once they could only read sky.
		[m2m_copier(ctx) fillBuffer:state.skylineBands range:NSMakeRange(0, state.skylineBands.length) value:0];
		m2m_compute_begin(ctx, state.skylineReducePipeline);
		m2m_bind_texture(ctx, 0, state.opaqueDepth, NO, NO);
		m2m_bind_bytes(ctx, 0, &uniforms, sizeof(uniforms), NO, NO);
		m2m_bind_buffer(ctx, 1, state.skylineBands, 0, NO, NO);
		m2m_compute_dispatch(ctx, MTLSizeMake(state.skylineBandCount, (color.height + M2M_SKYLINE_ROWS - 1) / M2M_SKYLINE_ROWS, 1),
		                     MTLSizeMake(M2M_SKYLINE_BAND, M2M_SKYLINE_ROWS, 1));
		uint32_t bandCount = (uint32_t)state.skylineBandCount;
		m2m_compute_begin(ctx, state.skylineLevelsPipeline);
		m2m_bind_buffer(ctx, 1, state.skylineBands, 0, NO, NO);
		m2m_bind_buffer(ctx, 2, state.skyline, 0, NO, NO);
		m2m_bind_bytes(ctx, 3, &bandCount, sizeof(bandCount), NO, NO);
		m2m_compute_dispatch(ctx, MTLSizeMake((state.skylineBandCount + 63) / 64, state.skylineLevels, 1), MTLSizeMake(64, 1, 1));
		m2m_end_encoding(ctx);

		m2m_shader_draw(ctx, m2m_shader_pass(linear ? target : color, MTLLoadActionLoad), @"mine2metal shader water", water, ^{
			m2m_bind_texture(ctx, 0, depth, NO, YES);
			m2m_bind_texture(ctx, 1, state.opaqueDepth, NO, YES);
			m2m_bind_texture(ctx, 2, linear ? state.opaqueColorView : state.opaqueColor, NO, YES);
			m2m_bind_texture(ctx, 3, state.gAlbedo, NO, YES);
			m2m_bind_texture(ctx, 4, state.gData, NO, YES);
			m2m_bind_texture(ctx, 7, state.noiseTexture, NO, YES);
			m2m_bind_texture(ctx, 8, state.skyLut, NO, YES);
			m2m_bind_sampler(ctx, 0, state.linearSampler, NO, YES);
			m2m_bind_sampler(ctx, 1, state.pointSampler, NO, YES);
			m2m_bind_sampler(ctx, 2, state.noiseSampler, NO, YES);
			m2m_bind_sampler(ctx, 3, state.skySampler, NO, YES);
			m2m_bind_bytes(ctx, 0, &uniforms, sizeof(uniforms), NO, YES);
			m2m_bind_buffer(ctx, 2, state.skyline, 0, NO, YES);
		});
		m2m_shader_time(handle, "shader water");
		m2m_shader_reopen_main(ctx, state, color, depth, NO, @"mine2metal world after water");
		return 1;
	}
}

int32_t m2m_shader_post(int64_t handle, int64_t color_handle, int64_t depth_handle, const int32_t *quality,
                        const float *matrices, const float *environment) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		id<MTLTexture> color = m2m_borrow(color_handle);
		id<MTLTexture> depth = m2m_borrow(depth_handle);
		M2MWorldShaderState *state = ctx.worldShader;
		if (!state || !color || !depth) {
			return 0;
		}
		BOOL bloom = quality[5] > 0 && state.bloomValid && state.bloomSource.width == color.width &&
		             state.bloomSource.height == color.height;
		state.bloomValid = NO;
		// Under water the post pass draws the water fog, so it always runs then.
		if (!bloom && quality[4] <= 0 && environment[33] < 0.5f) {
			return 0;
		}
		id<MTLTexture> target = m2m_shader_color_target(state, color);
		id<MTLRenderPipelineState> post =
			m2m_shader_cached_pipeline(ctx, state, state.postPipelines, @"m2m_post", target.pixelFormat, NO);
		if (!post) {
			return 0;
		}

		m2m_shader_time(handle, "world after shader");
		if (bloom) {
			id<MTLTexture> source = state.bloomSource;
			for (id<MTLTexture> level in state.bloomLevels) {
				id<MTLTexture> from = source;
				m2m_shader_draw(ctx, m2m_shader_pass(level, MTLLoadActionDontCare), @"mine2metal bloom down",
				                state.bloomDownPipeline, ^{
					                m2m_bind_texture(ctx, 0, from, NO, YES);
					                m2m_bind_sampler(ctx, 0, state.linearSampler, NO, YES);
				                });
				source = level;
			}
			for (NSInteger i = (NSInteger)M2M_BLOOM_LEVELS - 2; i >= 0; i--) {
				id<MTLTexture> from = state.bloomLevels[(NSUInteger)i + 1];
				m2m_shader_draw(ctx, m2m_shader_pass(state.bloomLevels[(NSUInteger)i], MTLLoadActionLoad), @"mine2metal bloom up",
				                state.bloomUpPipeline, ^{
					                m2m_bind_texture(ctx, 0, from, NO, YES);
					                m2m_bind_sampler(ctx, 0, state.linearSampler, NO, YES);
				                });
			}
		}

		M2MShaderUniforms uniforms;
		m2m_shader_fill_uniforms(&uniforms, quality, matrices, environment, 0, YES, target != color);

		// Light shafts, in the air or under water, at half size. The texture also stands in for a missing bloom one.
		NSUInteger halfWidth = MAX(1u, color.width / 2);
		NSUInteger halfHeight = MAX(1u, color.height / 2);
		if (!state.shafts || state.shafts.width != halfWidth || state.shafts.height != halfHeight) {
			m2m_retire(ctx, state.shafts);
			state.shafts = m2m_new_private_texture(ctx, M2M_BLOOM_FORMAT, halfWidth, halfHeight,
			                                       MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
			if (!state.shafts) {
				return 0;
			}
		}
		BOOL shafts = quality[4] > 0;
		if (shafts) {
			id<MTLTexture> target = state.shafts;
			m2m_shader_draw(ctx, m2m_shader_pass(target, MTLLoadActionDontCare), @"mine2metal shader shafts", state.shaftsPipeline, ^{
				m2m_bind_texture(ctx, 0, depth, NO, YES);
				m2m_bind_texture(ctx, 1, target, NO, YES);
				m2m_bind_sampler(ctx, 1, state.pointSampler, NO, YES);
				m2m_bind_bytes(ctx, 0, &uniforms, sizeof(uniforms), NO, YES);
			});
		}

		static const float bloomStrengths[4] = {0.0f, 0.03f, 0.05f, 0.08f};
		M2MPostStrengths strengths = {bloom ? bloomStrengths[MAX(0, MIN(3, quality[5]))] : 0.0f, shafts ? 1.0f : 0.0f};
		m2m_shader_draw(ctx, m2m_shader_pass(target, MTLLoadActionLoad), @"mine2metal shader post", post, ^{
			m2m_bind_texture(ctx, 0, depth, NO, YES);
			m2m_bind_texture(ctx, 1, bloom ? state.bloomLevels.firstObject : state.shafts, NO, YES);
			m2m_bind_texture(ctx, 2, state.shafts, NO, YES);
			m2m_bind_sampler(ctx, 0, state.linearSampler, NO, YES);
			m2m_bind_sampler(ctx, 1, state.pointSampler, NO, YES);
			m2m_bind_bytes(ctx, 0, &uniforms, sizeof(uniforms), NO, YES);
			m2m_bind_bytes(ctx, 1, &strengths, sizeof(strengths), NO, YES);
		});
		m2m_end_encoding(ctx);
		m2m_shader_time(handle, "shader bloom and shafts");
		return 1;
	}
}

void m2m_shader_release(int64_t handle) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		M2MWorldShaderState *state = ctx.worldShader;
		if (!state) {
			return;
		}
		m2m_shader_retire(ctx, state, YES, YES);
		for (id<MTLBuffer> copy in state.voxelCopies) {
			m2m_retire(ctx, copy);
		}
		for (id<MTLBuffer> copy in state.coarseCopies) {
			m2m_retire(ctx, copy);
		}
		state.voxelCopies = nil;
		state.coarseCopies = nil;
		m2m_retire(ctx, state.noiseTexture);
		m2m_retire(ctx, state.skyLut);
		state.noiseTexture = nil;
		state.skyLut = nil;
		m2m_retire(ctx, state.shafts);
		state.shafts = nil;
		ctx.worldShader = nil;
	}
}
