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

	private static boolean warnedIndexedFan;

	private final long ctx;
	private final MetalCommandEncoder encoder;
	private final int depthFormat;
	private final int stencilFormat;
	private final RenderPass.RenderArea renderArea;
	private final @Nullable Object[] uniforms = new Object[MetalRenderPipeline.MAX_UNIFORMS];
	private long dirtyUniforms;
	private @Nullable MetalRenderPipeline pipeline;
	private @Nullable MetalBuffer indexBuffer;
	private int indexSize;

	MetalRenderPass(long ctx, MetalCommandEncoder encoder, int depthFormat, int stencilFormat, RenderPass.RenderArea renderArea) {
		this.ctx = ctx;
		this.encoder = encoder;
		this.depthFormat = depthFormat;
		this.stencilFormat = stencilFormat;
		this.renderArea = renderArea;
		this.disableScissor();
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(BackendRenderPipeline backendPipeline) {
		MetalRenderPipeline next = (MetalRenderPipeline) backendPipeline;
		this.pipeline = next;
		if (!next.isUsable()) {
			return;
		}
		long state = next.state(this.depthFormat, this.stencilFormat);
		if (state == 0L) {
			return;
		}
		MetalNative.renderPipeline(this.ctx, state, next.depthState, next.cull, next.wireframe, next.depthBias, next.depthBiasSlope);
		// The new pipeline may read a slot as a different kind of resource.
		for (int i = 0; i < this.uniforms.length; i++) {
			if (this.uniforms[i] != null) {
				this.dirtyUniforms |= 1L << i;
			}
		}
	}

	@Override
	public void setUniform(int index, Object value) {
		this.uniforms[index] = value;
		this.dirtyUniforms |= 1L << index;
	}

	@Override
	public void pushConstants(ByteBuffer data) {
		MetalNative.renderBytes(this.ctx, MetalRenderPipeline.PUSH_CONSTANTS_SLOT, MemoryUtil.memAddress(data), data.remaining());
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		MetalNative.renderScissor(this.ctx, x, y, width, height);
	}

	@Override
	public void disableScissor() {
		MetalNative.renderScissor(this.ctx, this.renderArea.x(), this.renderArea.y(), this.renderArea.width(), this.renderArea.height());
	}

	@Override
	public void setVertexBuffer(int slot, GpuBufferSlice slice) {
		MetalNative.renderVertexBuffer(this.ctx, MetalRenderPipeline.VERTEX_BUFFER_BASE_SLOT + slot, ((MetalBuffer) slice.buffer()).handle, slice.offset());
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
			warnIndexedFan();
			return;
		}
		MetalNative.renderDrawIndexed(this.ctx, this.pipeline.primitive, this.indexSize, this.indexBuffer.handle, (long) firstIndex * this.indexSize,
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
			warnIndexedFan();
			return;
		}
		MetalNative.renderMultiDrawIndexed(this.ctx, this.pipeline.primitive, this.indexSize, this.indexBuffer.handle, MemoryUtil.memAddress(indexOffsets),
				MemoryUtil.memAddress(indexCounts), MemoryUtil.memAddress(baseVertices), drawCount);
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
		if (!this.prepareDraw()) {
			return;
		}
		if (this.pipeline.topology == PrimitiveTopology.TRIANGLE_FAN) {
			warnIndexedFan();
			return;
		}
		MetalNative.renderDrawIndexedIndirect(this.ctx, this.pipeline.primitive, this.indexSize, this.indexBuffer.handle,
				((MetalBuffer) commands.buffer()).handle, commands.offset(), drawCount, INDEXED_INDIRECT_STRIDE);
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
		MetalNative.renderDrawIndirect(this.ctx, this.pipeline.primitive, ((MetalBuffer) commands.buffer()).handle, commands.offset(), drawCount,
				INDIRECT_STRIDE);
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
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
		MetalNative.renderDrawIndexed(this.ctx, MetalFormats.PRIMITIVE_TRIANGLE, Integer.BYTES, ((MetalBuffer) indices.slice().buffer()).handle,
				indices.slice().offset(), triangles * 3, instanceCount, 0, firstInstance);
	}

	private static void warnIndexedFan() {
		if (!warnedIndexedFan) {
			warnedIndexedFan = true;
			Mine2Metal.LOGGER.warn("Indexed triangle fans are not supported on Metal yet; skipping them");
		}
	}

	/** Binds what changed since the last draw; false when the current pipeline cannot draw. */
	private boolean prepareDraw() {
		MetalRenderPipeline current = this.pipeline;
		if (current == null || !current.isUsable() || current.state(this.depthFormat, this.stencilFormat) == 0L) {
			return false;
		}

		long dirty = this.dirtyUniforms;
		while (dirty != 0L) {
			int index = Long.numberOfTrailingZeros(dirty);
			dirty &= dirty - 1;
			Object value = this.uniforms[index];
			if (value != null) {
				this.bindUniform(current, index, value);
			}
		}
		this.dirtyUniforms = 0L;
		return true;
	}

	private void bindUniform(MetalRenderPipeline current, int index, Object value) {
		if (value instanceof TextureViewAndSampler textureAndSampler) {
			MetalNative.renderTexture(this.ctx, index, ((MetalTextureView) textureAndSampler.view()).handle, ((MetalSampler) textureAndSampler.sampler()).handle);
		} else if (value instanceof GpuBufferSlice slice) {
			MetalBuffer buffer = (MetalBuffer) slice.buffer();
			if (current.uniformType(index) == UniformType.TEXEL_BUFFER) {
				BindGroupLayout.UniformDescription description = current.uniforms.get(index);
				GpuFormat format = description.gpuFormat();
				long texture = MetalNative.bufferTextureCreate(buffer.handle, slice.offset(), slice.length(), MetalFormats.pixelFormat(format),
						format.blockSize());
				MetalNative.renderTexture(this.ctx, index, texture, 0L);
				this.encoder.releaseAfterGpu(texture);
			} else {
				MetalNative.renderBuffer(this.ctx, index, buffer.handle, slice.offset());
			}
		} else {
			throw new IllegalArgumentException("Unsupported uniform value " + value.getClass().getName() + " at slot " + index);
		}
	}
}
