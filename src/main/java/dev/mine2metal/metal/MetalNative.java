package dev.mine2metal.metal;

import com.mojang.renderpearl.api.device.BackendCreationException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM downcalls into libmine2metal.dylib (see src/main/native/mine2metal.h). Objective-C objects cross the boundary as
 * retained pointers held in a {@code long}; every one handed out by native code must eventually go back through
 * {@link #release}.
 */
final class MetalNative {
	private static final String LIBRARY_RESOURCE = "/natives/macos-arm64/libmine2metal.dylib";
	private static final int ERROR_CAPACITY = 1024;

	private static SymbolLookup library;

	private MetalNative() {
	}

	static synchronized void load() throws BackendCreationException {
		if (library != null) {
			return;
		}
		try {
			library = SymbolLookup.libraryLookup(extractLibrary(), Arena.global());
		} catch (IOException | IllegalArgumentException e) {
			throw new BackendCreationException("Could not load the Metal native library: " + e.getMessage(), BackendCreationException.Reason.PLATFORM_ERROR);
		}
	}

	static long deviceCreate(boolean validation) {
		try {
			return (long) Functions.DEVICE_CREATE.invokeExact(validation ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static String deviceName(long device) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment buffer = arena.allocate(256);
			long length = (long) Functions.DEVICE_NAME.invokeExact(device, buffer, buffer.byteSize());
			return buffer.getString(0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean deviceValidationActive(long device) {
		try {
			return (int) Functions.DEVICE_VALIDATION_ACTIVE.invokeExact(device) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean deviceSupportsMetal3(long device) {
		try {
			return (int) Functions.DEVICE_SUPPORTS_METAL3.invokeExact(device) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void release(long object) {
		try {
			Functions.RELEASE.invokeExact(object);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean deviceSupportsMetal4(long device) {
		try {
			return (int) Functions.DEVICE_SUPPORTS_METAL4.invokeExact(device) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** As {@link #release}, for anything the GPU may use; with Metal 4 it also leaves the residency set. */
	static void resourceRelease(long ctx, long object) {
		try {
			Functions.RESOURCE_RELEASE.invokeExact(ctx, object);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Returns the new context; on failure throws with the native error message. */
	static long contextCreate(long device, boolean debug, boolean metal4) throws BackendCreationException {
		long ctx;
		String error;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment buffer = arena.allocate(ERROR_CAPACITY);
			ctx = (long) Functions.CONTEXT_CREATE.invokeExact(device, debug ? 1 : 0, metal4 ? 1 : 0, buffer, (long) ERROR_CAPACITY);
			error = buffer.getString(0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (ctx == 0L) {
			throw new BackendCreationException("Could not set up Metal: " + error, BackendCreationException.Reason.PLATFORM_ERROR);
		}
		return ctx;
	}

	static long contextErrorCount(long ctx) {
		try {
			return (long) Functions.CONTEXT_ERROR_COUNT.invokeExact(ctx);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Failed command buffer messages since the last call, and whether one of them lost the device. */
	static GpuErrors contextTakeErrors(long ctx) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ERROR_CAPACITY * 8);
			boolean deviceLost = (int) Functions.CONTEXT_TAKE_ERRORS.invokeExact(ctx, out, out.byteSize()) != 0;
			String text = out.getString(0);
			return new GpuErrors(text.isEmpty() ? List.of() : List.of(text.split("\n")), deviceLost);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	record GpuErrors(List<String> messages, boolean deviceLost) {
	}

	static long contextMaxBufferLength(long ctx) {
		try {
			return (long) Functions.CONTEXT_MAX_BUFFER_LENGTH.invokeExact(ctx);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long contextCommit(long ctx) {
		try {
			return (long) Functions.CONTEXT_COMMIT.invokeExact(ctx, MemorySegment.NULL);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Commits and accumulates the command buffer's GPU time under label. */
	static long contextCommit(long ctx, String label) {
		try (Arena arena = Arena.ofConfined()) {
			return (long) Functions.CONTEXT_COMMIT.invokeExact(ctx, arena.allocateFrom(label));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void contextSetStageTiming(long ctx, boolean enabled) {
		try {
			Functions.CONTEXT_SET_STAGE_TIMING.invokeExact(ctx, enabled ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static double frameGenerationGpuMillis(long ctx) {
		try {
			return (double) Functions.FRAME_GENERATION_GPU_MILLIS.invokeExact(ctx);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** "label=seconds" lines for labelled commits since the last call. */
	static String contextTakeLabelTimes(long ctx) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(16384);
			long length = (long) Functions.CONTEXT_TAKE_LABEL_TIMES.invokeExact(ctx, out, out.byteSize());
			return out.getString(0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long contextCompleted(long ctx) {
		try {
			return (long) Functions.CONTEXT_COMPLETED.invokeExact(ctx);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Returns {render encoders, blit encoders, merged passes} so far. */
	static long[] contextEncoderCounts(long ctx) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment counts = arena.allocate(JAVA_LONG, 3);
			Functions.CONTEXT_ENCODER_COUNTS.invokeExact(ctx, counts, counts.asSlice(JAVA_LONG.byteSize()), counts.asSlice(2 * JAVA_LONG.byteSize()));
			return counts.toArray(JAVA_LONG);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static double contextGpuSeconds(long ctx) {
		try {
			return (double) Functions.CONTEXT_GPU_SECONDS.invokeExact(ctx);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void contextWait(long ctx, long index) {
		try {
			Functions.CONTEXT_WAIT.invokeExact(ctx, index);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long bufferCreate(long ctx, long size) {
		try {
			return (long) Functions.BUFFER_CREATE.invokeExact(ctx, size);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long bufferContents(long buffer) {
		try {
			return (long) Functions.BUFFER_CONTENTS.invokeExact(buffer);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long textureCreate(long ctx, int pixelFormat, int width, int height, int layers, int mipLevels, int flags) {
		try {
			return (long) Functions.TEXTURE_CREATE.invokeExact(ctx, pixelFormat, width, height, layers, mipLevels, flags);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long textureViewCreate(long texture, int baseMip, int mipLevels) {
		try {
			return (long) Functions.TEXTURE_VIEW_CREATE.invokeExact(texture, baseMip, mipLevels);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long samplerCreate(long ctx, int addressU, int addressV, int minFilter, int magFilter, int mipFilter, int maxAnisotropy, float maxLod) {
		try {
			return (long) Functions.SAMPLER_CREATE.invokeExact(ctx, addressU, addressV, minFilter, magFilter, mipFilter, maxAnisotropy, maxLod);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void blitBuffer(long ctx, long src, long srcOffset, long dst, long dstOffset, long size) {
		try {
			Functions.BLIT_BUFFER.invokeExact(ctx, src, srcOffset, dst, dstOffset, size);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void blitBufferToTexture(long ctx, long buffer, long offset, long bytesPerRow, long bytesPerImage, long texture, int layer, int mip,
			int x, int y, int width, int height) {
		try {
			Functions.BLIT_BUFFER_TO_TEXTURE.invokeExact(ctx, buffer, offset, bytesPerRow, bytesPerImage, texture, layer, mip, x, y, width, height);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void blitTextureToBuffer(long ctx, long texture, int mip, int x, int y, int width, int height, long buffer, long offset, long bytesPerRow) {
		try {
			Functions.BLIT_TEXTURE_TO_BUFFER.invokeExact(ctx, texture, mip, x, y, width, height, buffer, offset, bytesPerRow);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void blitTextureToTexture(long ctx, long src, long dst, int mip, int dstX, int dstY, int srcX, int srcY, int width, int height) {
		try {
			Functions.BLIT_TEXTURE_TO_TEXTURE.invokeExact(ctx, src, dst, mip, dstX, dstY, srcX, srcY, width, height);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void passColor(long ctx, int index, long texture, boolean clear, float r, float g, float b, float a) {
		try {
			Functions.PASS_COLOR.invokeExact(ctx, index, texture, clear ? 1 : 0, r, g, b, a);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void passDepth(long ctx, long texture, boolean hasStencil, boolean clear, double depth) {
		try {
			Functions.PASS_DEPTH.invokeExact(ctx, texture, hasStencil ? 1 : 0, clear ? 1 : 0, depth);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void passOpen(long ctx) {
		try {
			Functions.PASS_OPEN.invokeExact(ctx, MemorySegment.NULL);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void passOpen(long ctx, String label) {
		try (Arena arena = Arena.ofConfined()) {
			Functions.PASS_OPEN.invokeExact(ctx, arena.allocateFrom(label));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void passClose(long ctx) {
		try {
			Functions.PASS_CLOSE.invokeExact(ctx);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Compiles MSL; throws with the compiler output on failure. */
	static long libraryCreate(long ctx, String source) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment error = arena.allocate(ERROR_CAPACITY);
			long library = (long) Functions.LIBRARY_CREATE.invokeExact(ctx, arena.allocateFrom(source), error, (long) ERROR_CAPACITY);
			if (library == 0L) {
				throw new IllegalStateException(error.getString(0));
			}
			return library;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** See m2m_pipeline_create for the layout encoding; throws with Metal's message on failure. */
	static long pipelineCreate(long ctx, long vertexLibrary, String vertexEntry, long fragmentLibrary, String fragmentEntry, int[] layout, int depthFormat,
			int stencilFormat, String label) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment error = arena.allocate(ERROR_CAPACITY);
			long pipeline = (long) Functions.PIPELINE_CREATE.invokeExact(ctx, vertexLibrary, arena.allocateFrom(vertexEntry), fragmentLibrary,
					arena.allocateFrom(fragmentEntry), arena.allocateFrom(JAVA_INT, layout), depthFormat, stencilFormat, arena.allocateFrom(label), error,
					(long) ERROR_CAPACITY);
			if (pipeline == 0L) {
				throw new IllegalStateException(error.getString(0));
			}
			return pipeline;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long depthStateCreate(long ctx, int compare, boolean write) {
		try {
			return (long) Functions.DEPTH_STATE_CREATE.invokeExact(ctx, compare, write ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long bufferTextureCreate(long buffer, long offset, long length, int pixelFormat, int bytesPerPixel) {
		try {
			return (long) Functions.BUFFER_TEXTURE_CREATE.invokeExact(buffer, offset, length, pixelFormat, bytesPerPixel);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void renderPipeline(long ctx, long pipeline, long depthState, boolean cull, boolean wireframe, float depthBias, float depthBiasSlope) {
		try {
			Functions.RENDER_PIPELINE.invokeExact(ctx, pipeline, depthState, cull ? 1 : 0, wireframe ? 1 : 0, depthBias, depthBiasSlope);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void renderBuffer(long ctx, int index, long buffer, long offset) {
		try {
			Functions.RENDER_BUFFER.invokeExact(ctx, index, buffer, offset);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void renderTexture(long ctx, int index, long texture, long sampler) {
		try {
			Functions.RENDER_TEXTURE.invokeExact(ctx, index, texture, sampler);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void renderBytes(long ctx, int index, long address, long length) {
		try {
			Functions.RENDER_BYTES.invokeExact(ctx, index, address, length);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void renderVertexBuffer(long ctx, int index, long buffer, long offset) {
		try {
			Functions.RENDER_VERTEX_BUFFER.invokeExact(ctx, index, buffer, offset);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void renderScissor(long ctx, int x, int y, int width, int height) {
		try {
			Functions.RENDER_SCISSOR.invokeExact(ctx, x, y, width, height);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void renderDraw(long ctx, int primitive, int firstVertex, int vertexCount, int instanceCount, int firstInstance) {
		try {
			Functions.RENDER_DRAW.invokeExact(ctx, primitive, firstVertex, vertexCount, instanceCount, firstInstance);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void renderDrawIndexed(long ctx, int primitive, int indexSize, long indexBuffer, long indexOffset, int indexCount, int instanceCount,
			int baseVertex, int firstInstance) {
		try {
			Functions.RENDER_DRAW_INDEXED.invokeExact(ctx, primitive, indexSize, indexBuffer, indexOffset, indexCount, instanceCount, baseVertex,
					firstInstance);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void renderMultiDrawIndexed(long ctx, int primitive, int indexSize, long indexBuffer, long indexOffsetsAddress, long indexCountsAddress,
			long baseVerticesAddress, int drawCount) {
		try {
			Functions.RENDER_MULTI_DRAW_INDEXED.invokeExact(ctx, primitive, indexSize, indexBuffer, indexOffsetsAddress, indexCountsAddress, baseVerticesAddress,
					drawCount);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void renderMultiDraw(long ctx, int primitive, long firstVerticesAddress, long vertexCountsAddress, int drawCount) {
		try {
			Functions.RENDER_MULTI_DRAW.invokeExact(ctx, primitive, firstVerticesAddress, vertexCountsAddress, drawCount);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void renderDrawIndexedIndirect(long ctx, int primitive, int indexSize, long indexBuffer, long indirectBuffer, long offset, int drawCount,
			int stride) {
		try {
			Functions.RENDER_DRAW_INDEXED_INDIRECT.invokeExact(ctx, primitive, indexSize, indexBuffer, indirectBuffer, offset, drawCount, stride);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void renderDrawIndirect(long ctx, int primitive, long indirectBuffer, long offset, int drawCount, int stride) {
		try {
			Functions.RENDER_DRAW_INDIRECT.invokeExact(ctx, primitive, indirectBuffer, offset, drawCount, stride);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean metalFxSpatialSupported(long ctx) {
		try {
			return (int) Functions.METALFX_SPATIAL_SUPPORTED.invokeExact(ctx) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean metalFxTemporalSupported(long ctx) {
		try {
			return (int) Functions.METALFX_TEMPORAL_SUPPORTED.invokeExact(ctx) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** {min, max} output/input size ratios the temporal scaler accepts. */
	static float[] metalFxTemporalScaleRange(long ctx) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(JAVA_FLOAT, 2);
			Functions.METALFX_TEMPORAL_SCALE_RANGE.invokeExact(ctx, out, out.asSlice(JAVA_FLOAT.byteSize()));
			return out.toArray(JAVA_FLOAT);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** The engines of {@code m2m_temporal_upscale}. */
	static final int TEMPORAL_METALFX = 1;
	static final int TEMPORAL_FSR = 2;

	static boolean temporalUpscale(long ctx, int engine, long color, long depth, long output, float[] matrices, float[] camera, float jitterX,
			float jitterY, boolean reset, boolean reactive) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment params = arena.allocateFrom(JAVA_FLOAT, matrices);
			MemorySegment cameraParams = arena.allocateFrom(JAVA_FLOAT, camera);
			return (int) Functions.TEMPORAL_UPSCALE.invokeExact(ctx, engine, color, depth, output, params, cameraParams, jitterX, jitterY,
					reset ? 1 : 0, reactive ? 1 : 0) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean frameInterpolationSupported(long ctx) {
		try {
			return (int) Functions.FRAME_INTERPOLATION_SUPPORTED.invokeExact(ctx) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean fsrFrameGenerationSupported(long ctx) {
		try {
			return (int) Functions.FSR_FRAMEGEN_SUPPORTED.invokeExact(ctx) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** engine: 1 for MetalFX, 2 for FSR 3.1 (M2M_FRAMEGEN_* in mine2metal.h). */
	static boolean frameInterpolationCapture(long ctx, int engine, long color, long depth, float[] matrices, float[] camera, boolean reset) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment params = arena.allocateFrom(JAVA_FLOAT, matrices);
			MemorySegment values = arena.allocateFrom(JAVA_FLOAT, camera);
			return (int) Functions.FRAME_INTERPOLATION_CAPTURE.invokeExact(ctx, engine, color, depth, params, values, reset ? 1 : 0) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** The generated frame of the latest present (borrowed), or 0. */
	static long frameInterpolationLastOutput(long ctx) {
		try {
			return (long) Functions.FRAME_INTERPOLATION_LAST_OUTPUT.invokeExact(ctx);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void frameInterpolationRelease(long ctx) {
		try {
			Functions.FRAME_INTERPOLATION_RELEASE.invokeExact(ctx);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean metalFxSpatialUpscale(long ctx, long input, long output) {
		try {
			return (int) Functions.METALFX_SPATIAL_UPSCALE.invokeExact(ctx, input, output) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean fsr1Upscale(long ctx, long input, long output, float sharpness) {
		try {
			return (int) Functions.FSR1_UPSCALE.invokeExact(ctx, input, output, sharpness) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** On-screen times (seconds) of frames presented since the last call. */
	static double[] takePresentedTimes(long ctx) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(JAVA_DOUBLE, 4096);
			long count = (long) Functions.TAKE_PRESENTED_TIMES.invokeExact(ctx, out, 4096L);
			return out.asSlice(0, count * JAVA_DOUBLE.byteSize()).toArray(JAVA_DOUBLE);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void layerConfigure(long ctx, long layer, int width, int height, boolean vsync) {
		try {
			Functions.LAYER_CONFIGURE.invokeExact(ctx, layer, width, height, vsync ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean clearRegion(long ctx, long color, float r, float g, float b, float a, long depth, boolean hasStencil, double depthValue, int x, int y,
			int width, int height, int mipLevel) {
		try {
			return (int) Functions.CLEAR_REGION.invokeExact(ctx, color, r, g, b, a, depth, hasStencil ? 1 : 0, depthValue, x, y, width, height, mipLevel) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long timestampPoolCreate(long size) {
		try {
			return (long) Functions.TIMESTAMP_POOL_CREATE.invokeExact(size);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Values in nanoseconds of host time, -1 where not available yet. */
	static long[] timestampPoolRead(long pool, int first, int count) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(JAVA_LONG, count);
			Functions.TIMESTAMP_POOL_READ.invokeExact(pool, (long) first, (long) count, out);
			return out.toArray(JAVA_LONG);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void timestampWrite(long ctx, long pool, int index) {
		try {
			Functions.TIMESTAMP_WRITE.invokeExact(ctx, pool, (long) index);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long hostTimeNanos() {
		try {
			return (long) Functions.HOST_TIME_NANOS.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void layerSetHud(long layer, boolean enabled) {
		try {
			Functions.LAYER_SET_HUD.invokeExact(layer, enabled ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void layerSetHdr(long ctx, long layer, boolean enabled) {
		try {
			Functions.LAYER_SET_HDR.invokeExact(ctx, layer, enabled ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean hdrSupported() {
		try {
			return (int) Functions.HDR_SUPPORTED.invokeExact() != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static float hdrHeadroom() {
		try {
			return (float) Functions.HDR_HEADROOM.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void hdrSetPeak(long ctx, float peak, boolean ignoreDisplay) {
		try {
			Functions.HDR_SET_PEAK.invokeExact(ctx, peak, ignoreDisplay ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void hdrCapture(long ctx, long color) {
		try {
			Functions.HDR_CAPTURE.invokeExact(ctx, color);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** The RGBA16Float frame of the latest present (borrowed), or 0. */
	static long hdrLastFrame(long ctx) {
		try {
			return (long) Functions.HDR_LAST_FRAME.invokeExact(ctx);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void layerDetach(long ctx) {
		try {
			Functions.LAYER_DETACH.invokeExact(ctx);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean present(long ctx, long layer, long texture) {
		try {
			return (int) Functions.PRESENT.invokeExact(ctx, layer, texture) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException runtime) {
			return runtime;
		}
		if (t instanceof Error error) {
			throw error;
		}
		return new IllegalStateException(t);
	}

	/**
	 * Copies the dylib to {@code .mine2metal/natives/<sha-256>/} in the game directory. A copy whose contents still
	 * match is reused, the write is atomic so a crash cannot leave a half-written library, and directories of other
	 * versions are removed.
	 */
	private static Path extractLibrary() throws IOException {
		byte[] bytes;
		try (InputStream in = MetalNative.class.getResourceAsStream(LIBRARY_RESOURCE)) {
			if (in == null) {
				throw new IOException(LIBRARY_RESOURCE + " is missing from the mod jar");
			}
			bytes = in.readAllBytes();
		}
		String hash = sha256(bytes);
		Path natives = FabricLoader.getInstance().getGameDir().resolve(".mine2metal").resolve("natives");
		Path file = natives.resolve(hash).resolve("libmine2metal.dylib");

		if (!Files.isRegularFile(file) || !hash.equals(sha256(Files.readAllBytes(file)))) {
			Files.createDirectories(file.getParent());
			Path partial = Files.createTempFile(file.getParent(), "libmine2metal", ".partial");
			Files.write(partial, bytes);
			Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		removeOtherVersions(natives, hash);
		return file;
	}

	private static void removeOtherVersions(Path natives, String keep) {
		try (Stream<Path> versions = Files.list(natives)) {
			for (Path version : versions.filter(path -> !path.getFileName().toString().equals(keep)).toList()) {
				try (Stream<Path> files = Files.walk(version)) {
					for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
						Files.deleteIfExists(path);
					}
				}
			}
		} catch (IOException e) {
			// Stale copies only cost disk space; never fail startup over them.
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Resolved on first use, which {@link #load} guarantees comes after the library is mapped. */
	private static final class Functions {
		private static final Linker LINKER = Linker.nativeLinker();

		static final MethodHandle DEVICE_CREATE = function("m2m_device_create", JAVA_LONG, JAVA_INT);
		static final MethodHandle DEVICE_VALIDATION_ACTIVE = function("m2m_device_validation_active", JAVA_INT, JAVA_LONG);
		static final MethodHandle DEVICE_NAME = function("m2m_device_name", JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG);
		static final MethodHandle DEVICE_SUPPORTS_METAL3 = function("m2m_device_supports_metal3", JAVA_INT, JAVA_LONG);
		static final MethodHandle DEVICE_SUPPORTS_METAL4 = function("m2m_device_supports_metal4", JAVA_INT, JAVA_LONG);
		static final MethodHandle RELEASE = function("m2m_release", null, JAVA_LONG);
		static final MethodHandle RESOURCE_RELEASE = function("m2m_resource_release", null, JAVA_LONG, JAVA_LONG);

		static final MethodHandle CONTEXT_CREATE = function("m2m_context_create", JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG);
		static final MethodHandle CONTEXT_ERROR_COUNT = function("m2m_context_error_count", JAVA_LONG, JAVA_LONG);
		static final MethodHandle CONTEXT_TAKE_ERRORS = function("m2m_context_take_errors", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG);
		static final MethodHandle CONTEXT_MAX_BUFFER_LENGTH = function("m2m_context_max_buffer_length", JAVA_LONG, JAVA_LONG);
		static final MethodHandle CONTEXT_COMMIT = function("m2m_context_commit", JAVA_LONG, JAVA_LONG, ADDRESS);
		static final MethodHandle CONTEXT_TAKE_LABEL_TIMES = function("m2m_context_take_label_times", JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG);
		static final MethodHandle CONTEXT_COMPLETED = function("m2m_context_completed", JAVA_LONG, JAVA_LONG);
		static final MethodHandle CONTEXT_ENCODER_COUNTS = function("m2m_context_encoder_counts", null, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS);
		static final MethodHandle CONTEXT_GPU_SECONDS = function("m2m_context_gpu_seconds", JAVA_DOUBLE, JAVA_LONG);
		static final MethodHandle CONTEXT_WAIT = function("m2m_context_wait", null, JAVA_LONG, JAVA_LONG);

		static final MethodHandle BUFFER_CREATE = function("m2m_buffer_create", JAVA_LONG, JAVA_LONG, JAVA_LONG);
		static final MethodHandle BUFFER_CONTENTS = function("m2m_buffer_contents", JAVA_LONG, JAVA_LONG);
		static final MethodHandle TEXTURE_CREATE = function("m2m_texture_create", JAVA_LONG,
				JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
		static final MethodHandle TEXTURE_VIEW_CREATE = function("m2m_texture_view_create", JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
		static final MethodHandle SAMPLER_CREATE = function("m2m_sampler_create", JAVA_LONG,
				JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT);

		static final MethodHandle BLIT_BUFFER = function("m2m_blit_buffer", null,
				JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
		static final MethodHandle BLIT_BUFFER_TO_TEXTURE = function("m2m_blit_buffer_to_texture", null,
				JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
		static final MethodHandle BLIT_TEXTURE_TO_BUFFER = function("m2m_blit_texture_to_buffer", null,
				JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG);
		static final MethodHandle BLIT_TEXTURE_TO_TEXTURE = function("m2m_blit_texture_to_texture", null,
				JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);

		static final MethodHandle PASS_COLOR = function("m2m_pass_color", null,
				JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
		static final MethodHandle PASS_DEPTH = function("m2m_pass_depth", null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_DOUBLE);
		static final MethodHandle PASS_OPEN = function("m2m_pass_open", null, JAVA_LONG, ADDRESS);
		static final MethodHandle PASS_CLOSE = function("m2m_pass_close", null, JAVA_LONG);

		static final MethodHandle LIBRARY_CREATE = function("m2m_library_create", JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG);
		static final MethodHandle PIPELINE_CREATE = function("m2m_pipeline_create", JAVA_LONG,
				JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG);
		static final MethodHandle DEPTH_STATE_CREATE = function("m2m_depth_state_create", JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
		static final MethodHandle BUFFER_TEXTURE_CREATE = function("m2m_buffer_texture_create", JAVA_LONG,
				JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);

		static final MethodHandle RENDER_PIPELINE = function("m2m_render_pipeline", null,
				JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT);
		static final MethodHandle RENDER_BUFFER = function("m2m_render_buffer", null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
		static final MethodHandle RENDER_TEXTURE = function("m2m_render_texture", null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
		static final MethodHandle RENDER_BYTES = function("m2m_render_bytes", null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
		static final MethodHandle RENDER_VERTEX_BUFFER = function("m2m_render_vertex_buffer", null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
		static final MethodHandle RENDER_SCISSOR = function("m2m_render_scissor", null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
		static final MethodHandle RENDER_DRAW = function("m2m_render_draw", null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
		static final MethodHandle RENDER_DRAW_INDEXED = function("m2m_render_draw_indexed", null,
				JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
		static final MethodHandle RENDER_MULTI_DRAW_INDEXED = function("m2m_render_multi_draw_indexed", null,
				JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
		static final MethodHandle RENDER_MULTI_DRAW = function("m2m_render_multi_draw", null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT);
		static final MethodHandle RENDER_DRAW_INDEXED_INDIRECT = function("m2m_render_draw_indexed_indirect", null,
				JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
		static final MethodHandle RENDER_DRAW_INDIRECT = function("m2m_render_draw_indirect", null,
				JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);

		static final MethodHandle METALFX_SPATIAL_SUPPORTED = function("m2m_metalfx_spatial_supported", JAVA_INT, JAVA_LONG);
		static final MethodHandle METALFX_TEMPORAL_SUPPORTED = function("m2m_metalfx_temporal_supported", JAVA_INT, JAVA_LONG);
		static final MethodHandle METALFX_TEMPORAL_SCALE_RANGE = function("m2m_metalfx_temporal_scale_range", null, JAVA_LONG, ADDRESS, ADDRESS);
		static final MethodHandle TEMPORAL_UPSCALE = function("m2m_temporal_upscale", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG,
				ADDRESS, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT, JAVA_INT);
		static final MethodHandle FRAME_INTERPOLATION_SUPPORTED = function("m2m_frame_interpolation_supported", JAVA_INT, JAVA_LONG);
		static final MethodHandle CONTEXT_SET_STAGE_TIMING = function("m2m_context_set_stage_timing", null, JAVA_LONG, JAVA_INT);
		static final MethodHandle FRAME_GENERATION_GPU_MILLIS = function("m2m_frame_generation_gpu_millis", JAVA_DOUBLE, JAVA_LONG);
		static final MethodHandle FSR_FRAMEGEN_SUPPORTED = function("m2m_fsr_framegen_supported", JAVA_INT, JAVA_LONG);
		static final MethodHandle FRAME_INTERPOLATION_CAPTURE = function("m2m_frame_interpolation_capture", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG,
				JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT);
		static final MethodHandle FRAME_INTERPOLATION_LAST_OUTPUT = function("m2m_frame_interpolation_last_output", JAVA_LONG, JAVA_LONG);
		static final MethodHandle FRAME_INTERPOLATION_RELEASE = function("m2m_frame_interpolation_release", null, JAVA_LONG);
		static final MethodHandle METALFX_SPATIAL_UPSCALE = function("m2m_metalfx_spatial_upscale", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG);
		static final MethodHandle FSR1_UPSCALE = function("m2m_fsr1_upscale", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_FLOAT);

		static final MethodHandle TAKE_PRESENTED_TIMES = function("m2m_take_presented_times", JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG);
		static final MethodHandle LAYER_CONFIGURE = function("m2m_layer_configure", null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
		static final MethodHandle CLEAR_REGION = function("m2m_clear_region", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT,
				JAVA_LONG, JAVA_INT, JAVA_DOUBLE, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
		static final MethodHandle TIMESTAMP_POOL_CREATE = function("m2m_timestamp_pool_create", JAVA_LONG, JAVA_LONG);
		static final MethodHandle TIMESTAMP_POOL_READ = function("m2m_timestamp_pool_read", null, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS);
		static final MethodHandle TIMESTAMP_WRITE = function("m2m_timestamp_write", null, JAVA_LONG, JAVA_LONG, JAVA_LONG);
		static final MethodHandle HOST_TIME_NANOS = function("m2m_host_time_nanos", JAVA_LONG);
		static final MethodHandle LAYER_SET_HUD = function("m2m_layer_set_hud", null, JAVA_LONG, JAVA_INT);
		static final MethodHandle LAYER_SET_HDR = function("m2m_layer_set_hdr", null, JAVA_LONG, JAVA_LONG, JAVA_INT);
		static final MethodHandle HDR_SUPPORTED = function("m2m_hdr_supported", JAVA_INT);
		static final MethodHandle HDR_HEADROOM = function("m2m_hdr_headroom", JAVA_FLOAT);
		static final MethodHandle HDR_SET_PEAK = function("m2m_hdr_set_peak", null, JAVA_LONG, JAVA_FLOAT, JAVA_INT);
		static final MethodHandle HDR_CAPTURE = function("m2m_hdr_capture", null, JAVA_LONG, JAVA_LONG);
		static final MethodHandle HDR_LAST_FRAME = function("m2m_hdr_last_frame", JAVA_LONG, JAVA_LONG);
		static final MethodHandle LAYER_DETACH = function("m2m_layer_detach", null, JAVA_LONG);
		static final MethodHandle PRESENT = function("m2m_present", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG);

		private Functions() {
		}

		private static MethodHandle function(String name, MemoryLayout result, MemoryLayout... arguments) {
			MemorySegment symbol = library.find(name).orElseThrow(() -> new IllegalStateException("Missing native symbol " + name));
			FunctionDescriptor descriptor = result == null ? FunctionDescriptor.ofVoid(arguments) : FunctionDescriptor.of(result, arguments);
			return LINKER.downcallHandle(symbol, descriptor);
		}
	}
}
