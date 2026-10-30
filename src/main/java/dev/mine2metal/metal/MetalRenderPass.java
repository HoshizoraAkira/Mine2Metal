package dev.mine2metal.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.mine2metal.Mine2Metal;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.function.Supplier;

/**
 * Binds into the native render encoder opened by {@link MetalCommandEncoder#createRenderPass}. Uniforms are bound
 * lazily at the next draw, because a texel buffer can only be told apart from a uniform buffer through the pipeline.
 */
final class MetalRenderPass implements RenderPassBackend {
	/** Sizes of MTLDraw(Indexed)PrimitivesIndirectArguments, which match GL's and Vulkan's indirect layouts. */
	private static final int INDEXED_INDIRECT_STRIDE = 20;
	private static final int INDIRECT_STRIDE = 16;
	private static final int MAX_VERTEX_BUFFERS = 8;

	private static boolean warnedIndexedFan;

	private final long ctx;
	private final MetalCommandEncoder encoder;
	private final int depthFormat;
	private final int stencilFormat;
	private final RenderPass.RenderArea renderArea;
	private final @Nullable Object[] uniforms = new Object[MetalRenderPipeline.MAX_UNIFORMS];
	private long dirtyVertexUniforms;
	private long dirtyFragmentUniforms;
	private @Nullable MetalRenderPipeline pipeline;
	private @Nullable MetalBuffer indexBuffer;
	private int indexSize;
	/** Whether the world shader's G-buffer is attached right now (see {@link #resume}). */
	private boolean gbuffer;
	/** The main world pass's color and depth, which the world shader works on; 0 for any other pass. */
	final long worldColor;
	final long worldDepth;

	// What is bound on the encoder, to bind again when the world shader reopens it (see resume).
	private int scissorX;
	private int scissorY;
	private int scissorWidth;
	private int scissorHeight;
	private final long[] vertexBuffers = new long[MAX_VERTEX_BUFFERS];
	private final long[] vertexOffsets = new long[MAX_VERTEX_BUFFERS];
	private byte @Nullable [] pushConstants;

	MetalRenderPass(long ctx, MetalCommandEncoder encoder, int depthFormat, int stencilFormat, RenderPass.RenderArea renderArea, long worldColor,
			long worldDepth) {
		this.ctx = ctx;
		this.encoder = encoder;
		this.depthFormat = depthFormat;
		this.stencilFormat = stencilFormat;
		this.renderArea = renderArea;
		this.gbuffer = worldColor != 0L;
		this.worldColor = worldColor;
		this.worldDepth = worldDepth;
		this.disableScissor();
	}

	boolean hasGbuffer() {
		return this.gbuffer;
	}

	boolean isWorldPass() {
		return this.worldColor != 0L;
	}

	/**
	 * The world shader ended the encoder this pass drew into and opened a new one on the same color and depth, with or
	 * without the G-buffer: everything this pass had bound goes onto the new encoder.
	 */
	void resume(boolean gbuffer) {
		this.gbuffer = gbuffer;
		MetalRenderPipeline current = this.pipeline;
		if (current != null && current.isUsable()) {
			long state = current.state(this.depthFormat, this.stencilFormat, gbuffer);
			if (state != 0L) {
				MetalNative.renderPipeline(this.ctx, state, current.depthState, current.cull, current.wireframe, current.depthBias, current.depthBiasSlope);
			}
		}
		MetalNative.renderScissor(this.ctx, this.scissorX, this.scissorY, this.scissorWidth, this.scissorHeight);
		for (int slot = 0; slot < MAX_VERTEX_BUFFERS; slot++) {
			if (this.vertexBuffers[slot] != 0L) {
				MetalNative.renderVertexBuffer(this.ctx, MetalRenderPipeline.VERTEX_BUFFER_BASE_SLOT + slot, this.vertexBuffers[slot],
						this.vertexOffsets[slot]);
			}
		}
		byte[] constants = this.pushConstants;
		if (constants != null) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				ByteBuffer data = stack.bytes(constants);
				MetalNative.renderBytes(this.ctx, MetalRenderPipeline.PUSH_CONSTANTS_SLOT, MemoryUtil.memAddress(data), data.remaining());
			}
		}
		this.dirtyVertexUniforms = -1L;
		this.dirtyFragmentUniforms = -1L;
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(BackendRenderPipeline backendPipeline) {
		MetalRenderPipeline previous = this.pipeline;
		MetalRenderPipeline next = (MetalRenderPipeline) backendPipeline;
		this.pipeline = next;
		if (!next.isUsable()) {
			return;
		}
		long state = next.state(this.depthFormat, this.stencilFormat, this.gbuffer);
		if (state == 0L) {
			return;
		}
		MetalNative.renderPipeline(this.ctx, state, next.depthState, next.cull, next.wireframe, next.depthBias, next.depthBiasSlope);
		// A buffer slice binds to the buffer table for uniform buffers and the texture table for texel buffers;
		// other slots stay bound across pipeline switches on the same encoder.
		long texelDirty = (previous != null ? previous.texelBufferMask : 0L) | next.texelBufferMask;
		this.dirtyVertexUniforms |= texelDirty;
		this.dirtyFragmentUniforms |= texelDirty;
	}

	@Override
	public void setUniform(int index, Object value) {
		long bit = 1L << index;
		this.uniforms[index] = value;
		this.dirtyVertexUniforms |= bit;
		this.dirtyFragmentUniforms |= bit;
	}

	@Override
	public void pushConstants(ByteBuffer data) {
		byte[] copy = this.pushConstants;
		if (copy == null || copy.length != data.remaining()) {
			copy = new byte[data.remaining()];
			this.pushConstants = copy;
		}
		data.duplicate().get(copy);
		MetalNative.renderBytes(this.ctx, MetalRenderPipeline.PUSH_CONSTANTS_SLOT, MemoryUtil.memAddress(data), data.remaining());
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		this.scissorX = x;
		this.scissorY = y;
		this.scissorWidth = width;
		this.scissorHeight = height;
		MetalNative.renderScissor(this.ctx, x, y, width, height);
	}

	@Override
	public void disableScissor() {
		this.enableScissor(this.renderArea.x(), this.renderArea.y(), this.renderArea.width(), this.renderArea.height());
	}

	@Override
	public void setVertexBuffer(int slot, @Nullable GpuBufferSlice slice) {
		// A null slice unbinds the slot, as in Mojang's GL backend (the cloud renderer does this when it has no mesh).
		long buffer = slice != null ? ((MetalBuffer) slice.buffer()).use() : 0L;
		long offset = slice != null ? slice.offset() : 0L;
		if (slot < MAX_VERTEX_BUFFERS) {
			this.vertexBuffers[slot] = buffer;
			this.vertexOffsets[slot] = offset;
		}
		MetalNative.renderVertexBuffer(this.ctx, MetalRenderPipeline.VERTEX_BUFFER_BASE_SLOT + slot, buffer, offset);
	}

	@Override
	public void setIndexBuffer(GpuBuffer buffer, IndexType indexType) {
		this.indexBuffer = (MetalBuffer) buffer;
		this.indexSize = indexType.bytes;
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
		if (!this.prepareDraw()) {
			return;
		}
		if (this.pipeline.topology == PrimitiveTopology.TRIANGLE_FAN) {
			this.drawIndexedFan((long) firstIndex * this.indexSize, indexCount, instanceCount, baseVertex, firstInstance);
			return;
		}
		MetalNative.renderDrawIndexed(this.ctx, this.pipeline.primitive, this.indexSize, this.indexBuffer.use(), (long) firstIndex * this.indexSize,
				indexCount, instanceCount, baseVertex, firstInstance);
	}

	@Override
	public void multiDrawIndexed(IntBuffer draws, int drawCount, int firstInstance, int instanceCount) {
		throw new UnsupportedOperationException("Interleaved multi-draw is not advertised by the Metal backend");
	}

	@Override
	public void multiDrawIndexed(PointerBuffer indexOffsets, IntBuffer indexCounts, IntBuffer baseVertices, int drawCount) {
		if (!this.prepareDraw()) {
			return;
		}
		if (this.pipeline.topology == PrimitiveTopology.TRIANGLE_FAN) {
			for (int i = 0; i < drawCount; i++) {
				this.drawIndexedFan(indexOffsets.get(indexOffsets.position() + i), indexCounts.get(indexCounts.position() + i),
						1, baseVertices.get(baseVertices.position() + i), 0);
			}
			return;
		}
		MetalNative.renderMultiDrawIndexed(this.ctx, this.pipeline.primitive, this.indexSize, this.indexBuffer.use(), MemoryUtil.memAddress(indexOffsets),
				MemoryUtil.memAddress(indexCounts), MemoryUtil.memAddress(baseVertices), drawCount);
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
		if (!this.prepareDraw()) {
			return;
		}
		if (this.pipeline.topology == PrimitiveTopology.TRIANGLE_FAN) {
			// The draw arguments are only known on the GPU.
			warnIndexedFan("indirect");
			return;
		}
		MetalNative.renderDrawIndexedIndirect(this.ctx, this.pipeline.primitive, this.indexSize, this.indexBuffer.use(),
				((MetalBuffer) commands.buffer()).use(), commands.offset(), drawCount, INDEXED_INDIRECT_STRIDE);
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		if (!this.prepareDraw()) {
			return;
		}
		if (this.pipeline.topology == PrimitiveTopology.TRIANGLE_FAN) {
			this.drawFan(firstVertex, vertexCount, instanceCount, firstInstance);
			return;
		}
		MetalNative.renderDraw(this.ctx, this.pipeline.primitive, firstVertex, vertexCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDraw(IntBuffer draws, int drawCount, int firstInstance, int instanceCount) {
		throw new UnsupportedOperationException("Interleaved multi-draw is not advertised by the Metal backend");
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		if (!this.prepareDraw()) {
			return;
		}
		if (this.pipeline.topology == PrimitiveTopology.TRIANGLE_FAN) {
			for (int i = 0; i < drawCount; i++) {
				this.drawFan(firstVertices.get(firstVertices.position() + i), vertexCounts.get(vertexCounts.position() + i), 1, 0);
			}
			return;
		}
		MetalNative.renderMultiDraw(this.ctx, this.pipeline.primitive, MemoryUtil.memAddress(firstVertices), MemoryUtil.memAddress(vertexCounts), drawCount);
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
		if (!this.prepareDraw()) {
			return;
		}
		MetalNative.renderDrawIndirect(this.ctx, this.pipeline.primitive, ((MetalBuffer) commands.buffer()).use(), commands.offset(), drawCount,
				INDIRECT_STRIDE);
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
		// Apple GPUs cannot sample inside a render encoder, and splitting the pass here would change its load and
		// store actions; the value stays unavailable, which the profiler treats as a missing sample.
	}

	/** Metal has no fans: (0, i, i + 1) triangles through a throwaway index buffer keep the same winding. */
	private void drawFan(int firstVertex, int vertexCount, int instanceCount, int firstInstance) {
		int triangles = vertexCount - 2;
		if (triangles <= 0) {
			return;
		}
		GpuBufferSlice.MappedView indices = this.encoder.transientMemory().allocateGpuMapped((long) triangles * 3 * Integer.BYTES, Integer.BYTES,
				GpuBuffer.USAGE_INDEX);
		IntBuffer data = indices.data().order(ByteOrder.nativeOrder()).asIntBuffer();
		for (int i = 1; i <= triangles; i++) {
			data.put(firstVertex).put(firstVertex + i).put(firstVertex + i + 1);
		}
		MetalNative.renderDrawIndexed(this.ctx, MetalFormats.PRIMITIVE_TRIANGLE, Integer.BYTES, ((MetalBuffer) indices.slice().buffer()).use(),
				indices.slice().offset(), triangles * 3, instanceCount, 0, firstInstance);
	}

	/**
	 * Indexed fans become (f0, fi, fi+1) triangles, read on the CPU from the shared index buffer; only Minecraft's debug
	 * renderers draw them, so a buffer that still has a GPU write pending is skipped rather than waited for.
	 */
	private void drawIndexedFan(long byteOffset, int indexCount, int instanceCount, int baseVertex, int firstInstance) {
		int triangles = indexCount - 2;
		if (triangles <= 0) {
			return;
		}
		MetalBuffer source = this.indexBuffer;
		if (!this.encoder.isBufferReadableOnCpu(source)) {
			warnIndexedFan("from an index buffer the GPU is still writing");
			return;
		}
		GpuBufferSlice.MappedView indices = this.encoder.transientMemory().allocateGpuMapped((long) triangles * 3 * Integer.BYTES, Integer.BYTES,
				GpuBuffer.USAGE_INDEX);
		IntBuffer data = indices.data().order(ByteOrder.nativeOrder()).asIntBuffer();
		long base = source.address + byteOffset;
		int first = this.readIndex(base, 0);
		for (int i = 1; i <= triangles; i++) {
			data.put(first).put(this.readIndex(base, i)).put(this.readIndex(base, i + 1));
		}
		MetalNative.renderDrawIndexed(this.ctx, MetalFormats.PRIMITIVE_TRIANGLE, Integer.BYTES, ((MetalBuffer) indices.slice().buffer()).use(),
				indices.slice().offset(), triangles * 3, instanceCount, baseVertex, firstInstance);
	}

	private int readIndex(long base, int i) {
		return this.indexSize == Short.BYTES ? Short.toUnsignedInt(MemoryUtil.memGetShort(base + (long) i * Short.BYTES))
				: MemoryUtil.memGetInt(base + (long) i * Integer.BYTES);
	}

	private static void warnIndexedFan(String reason) {
		if (!warnedIndexedFan) {
			warnedIndexedFan = true;
			Mine2Metal.LOGGER.warn("Skipping indexed triangle fans drawn {} on Metal", reason);
		}
	}

	/** Binds what changed since the last draw; false when the current pipeline cannot draw. */
	private boolean prepareDraw() {
		MetalRenderPipeline current = this.pipeline;
		if (current == null || !current.isUsable() || current.state(this.depthFormat, this.stencilFormat, this.gbuffer) == 0L) {
			return false;
		}

		long dirtyVertex = this.dirtyVertexUniforms & current.vertexUniformMask;
		long dirtyFragment = this.dirtyFragmentUniforms & current.fragmentUniformMask;
		this.dirtyVertexUniforms &= ~dirtyVertex;
		this.dirtyFragmentUniforms &= ~dirtyFragment;
		long dirty = dirtyVertex | dirtyFragment;
		while (dirty != 0L) {
			int index = Long.numberOfTrailingZeros(dirty);
			long bit = 1L << index;
			dirty &= ~bit;
			Object value = this.uniforms[index];
			if (value != null) {
				this.bindUniform(current, index, value, (dirtyVertex & bit) != 0L, (dirtyFragment & bit) != 0L);
			}
		}
		return true;
	}

	private void bindUniform(MetalRenderPipeline current, int index, Object value, boolean vertex, boolean fragment) {
		if (value instanceof TextureViewAndSampler textureAndSampler) {
			MetalNative.renderTexture(this.ctx, index, ((MetalTextureView) textureAndSampler.view()).handle,
					((MetalSampler) textureAndSampler.sampler()).handle, vertex, fragment);
		} else if (value instanceof GpuBufferSlice slice) {
			MetalBuffer buffer = (MetalBuffer) slice.buffer();
			if (current.uniformType(index) == UniformType.TEXEL_BUFFER) {
				BindGroupLayout.UniformDescription description = current.uniforms.get(index);
				GpuFormat format = description.gpuFormat();
				long texture = this.encoder.texelView(buffer.use(), slice.offset(), slice.length(), MetalFormats.pixelFormat(format), format.blockSize());
				MetalNative.renderTexture(this.ctx, index, texture, 0L, vertex, fragment);
			} else {
				MetalNative.renderBuffer(this.ctx, index, buffer.use(), slice.offset(), vertex, fragment);
			}
		} else {
			throw new IllegalArgumentException("Unsupported uniform value " + value.getClass().getName() + " at slot " + index);
		}
	}
}
