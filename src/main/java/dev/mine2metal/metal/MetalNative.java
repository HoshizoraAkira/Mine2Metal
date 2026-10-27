package dev.mine2metal.metal;

import com.mojang.renderpearl.api.device.BackendCreationException;

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

	static long deviceCreate() {
		try {
			return (long) Functions.DEVICE_CREATE.invokeExact();
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

	/** Returns the new context; on failure throws with the native error message. */
	static long contextCreate(long device) throws BackendCreationException {
		long ctx;
		String error;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment buffer = arena.allocate(ERROR_CAPACITY);
			ctx = (long) Functions.CONTEXT_CREATE.invokeExact(device, buffer, (long) ERROR_CAPACITY);
			error = buffer.getString(0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (ctx == 0L) {
			throw new BackendCreationException("Could not set up Metal: " + error, BackendCreationException.Reason.PLATFORM_ERROR);
		}
		return ctx;
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

	static void layerConfigure(long ctx, long layer, int width, int height, boolean vsync) {
		try {
			Functions.LAYER_CONFIGURE.invokeExact(ctx, layer, width, height, vsync ? 1 : 0);
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

	private static Path extractLibrary() throws IOException {
		try (InputStream in = MetalNative.class.getResourceAsStream(LIBRARY_RESOURCE)) {
			if (in == null) {
				throw new IOException(LIBRARY_RESOURCE + " is missing from the mod jar");
			}
			Path directory = Files.createTempDirectory("mine2metal");
			Path file = directory.resolve("libmine2metal.dylib");
			Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
			file.toFile().deleteOnExit();
			directory.toFile().deleteOnExit();
			return file;
		}
	}

	/** Resolved on first use, which {@link #load} guarantees comes after the library is mapped. */
	private static final class Functions {
		private static final Linker LINKER = Linker.nativeLinker();

		static final MethodHandle DEVICE_CREATE = function("m2m_device_create", JAVA_LONG);
		static final MethodHandle DEVICE_NAME = function("m2m_device_name", JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG);
		static final MethodHandle DEVICE_SUPPORTS_METAL3 = function("m2m_device_supports_metal3", JAVA_INT, JAVA_LONG);
		static final MethodHandle RELEASE = function("m2m_release", null, JAVA_LONG);

		static final MethodHandle CONTEXT_CREATE = function("m2m_context_create", JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG);
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

		static final MethodHandle LAYER_CONFIGURE = function("m2m_layer_configure", null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
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
