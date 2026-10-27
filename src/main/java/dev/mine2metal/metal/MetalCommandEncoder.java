package dev.mine2metal.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import dev.mine2metal.Mine2Metal;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Records into one native command buffer per submit. Work that must wait for the GPU (releasing objects, readback
 * callbacks, recycling transient memory) is tagged with the submit it belongs to and run once that submit completes.
 */
final class MetalCommandEncoder implements CommandEncoderBackend {
	private static final int MAX_FRAMES_IN_FLIGHT = 3;

	private final long ctx;
	private final MetalTransientMemory transientMemory;
	private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
	/** Index the commands being recorded will get when committed. */
	private long recordingIndex = 1L;
	private boolean warnedPartialClear;

	MetalCommandEncoder(long ctx) {
		this.ctx = ctx;
		this.transientMemory = new MetalTransientMemory(ctx, this);
	}

	long recordingIndex() {
		return this.recordingIndex;
	}

	void releaseAfterGpu(long handle) {
		this.afterGpu(() -> MetalNative.release(handle));
	}

	void afterGpu(Runnable action) {
		this.deferred.add(new Deferred(this.recordingIndex, action));
	}

	@Override
	public void submit() {
		this.transientMemory.endSubmit();
		this.commit();
		MetalNative.contextWait(this.ctx, this.recordingIndex - MAX_FRAMES_IN_FLIGHT);
		this.runCompleted();
	}

	/** Commits what has been recorded without ending the frame. */
	private void commit() {
		this.recordingIndex = MetalNative.contextCommit(this.ctx) + 1;
	}

	private void runCompleted() {
		long completed = MetalNative.contextCompleted(this.ctx);
		while (!this.deferred.isEmpty() && this.deferred.peek().index() <= completed) {
			this.deferred.poll().action().run();
		}
	}

	void close() {
		this.commit();
		MetalNative.contextWait(this.ctx, this.recordingIndex - 1);
		this.runCompleted();
		this.transientMemory.close();
	}

	@Override
	public TransientMemory transientMemory() {
		return this.transientMemory;
	}

	@Override
	public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
		for (int i = 0; i < colors.size(); i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
			Optional<Vector4fc> clear = attachment.clearValue();
			Vector4fc color = clear.orElse(null);
			MetalNative.passColor(this.ctx, i, ((MetalTextureView) attachment.textureView()).handle, clear.isPresent(),
					color == null ? 0.0F : color.x(), color == null ? 0.0F : color.y(), color == null ? 0.0F : color.z(), color == null ? 0.0F : color.w());
		}

		int depthFormat = 0;
		int stencilFormat = 0;
		RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth = descriptor.depthAttachment();
		if (depth != null) {
			MetalTextureView view = (MetalTextureView) depth.textureView();
			GpuFormat format = view.texture().getFormat();
			MetalNative.passDepth(this.ctx, view.handle, format.hasStencilAspect(), depth.clearValue().isPresent(), depth.clearValue().orElse(1.0));
			depthFormat = MetalFormats.pixelFormat(format);
			stencilFormat = format.hasStencilAspect() ? depthFormat : 0;
		}

		MetalNative.passOpen(this.ctx);
		return new MetalRenderPass(this.ctx, this, depthFormat, stencilFormat, descriptor.renderArea());
	}

	@Override
	public void submitRenderPass() {
		MetalNative.passClose(this.ctx);
	}

	@Override
	public void clearColorTexture(GpuTexture texture, Vector4fc color) {
		this.clear(texture, color, null, Double.NaN);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc color, GpuTexture depthTexture, double depth) {
		this.clear(colorTexture, color, depthTexture, depth);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc color, GpuTexture depthTexture, double depth, int x, int y, int width, int height,
			int mipLevel) {
		if (mipLevel == 0 && x == 0 && y == 0 && width == colorTexture.getWidth(0) && height == colorTexture.getHeight(0)) {
			this.clear(colorTexture, color, depthTexture, depth);
		} else if (!this.warnedPartialClear) {
			this.warnedPartialClear = true;
			Mine2Metal.LOGGER.warn("Partial texture clears are not implemented yet; skipping them");
		}
	}

	@Override
	public void clearDepthTexture(GpuTexture texture, double depth) {
		this.clear(null, null, texture, depth);
	}

	/** A render pass whose only job is its clear load actions. */
	private void clear(@Nullable GpuTexture colorTexture, @Nullable Vector4fc color, @Nullable GpuTexture depthTexture, double depth) {
		if (colorTexture != null && color != null) {
			MetalNative.passColor(this.ctx, 0, ((MetalTexture) colorTexture).handle, true, color.x(), color.y(), color.z(), color.w());
		}
		if (depthTexture != null) {
			MetalNative.passDepth(this.ctx, ((MetalTexture) depthTexture).handle, depthTexture.getFormat().hasStencilAspect(), true, depth);
		}
		MetalNative.passOpen(this.ctx);
		MetalNative.passClose(this.ctx);
	}

	@Override
	public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
		// Staged through a blit so the write lands in command order, after any earlier GPU reads of the old contents.
		GpuBufferSlice staging = this.transientMemory.uploadGpu(data, 4L, GpuBuffer.USAGE_COPY_SRC);
		MetalNative.blitBuffer(this.ctx, ((MetalBuffer) staging.buffer()).handle, staging.offset(), ((MetalBuffer) destination.buffer()).handle,
				destination.offset(), data.remaining());
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
		MetalNative.blitBuffer(this.ctx, ((MetalBuffer) source.buffer()).handle, source.offset(), ((MetalBuffer) target.buffer()).handle, target.offset(),
				source.length());
	}

	@Override
	public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int layer, int x, int y, int width, int height) {
		GpuBufferSlice staging = this.transientMemory.uploadGpu(source, 16L, GpuBuffer.USAGE_COPY_SRC);
		int bytesPerRow = width * destination.getFormat().blockSize();
		MetalNative.blitBufferToTexture(this.ctx, ((MetalBuffer) staging.buffer()).handle, staging.offset(), bytesPerRow, (long) bytesPerRow * height,
				((MetalTexture) destination).handle, layer, mipLevel, x, y, width, height);
	}

	@Override
	public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, GpuTexture destination, int destX,
			int destY, int copyWidth, int copyHeight, int mipLevel, int layer) {
		int texelSize = destination.getFormat().blockSize();
		long skip = (sourceX + (long) sourceY * sourceWidth) * texelSize;
		long bytesPerRow = (long) sourceWidth * texelSize;
		MetalNative.blitBufferToTexture(this.ctx, ((MetalBuffer) source.buffer()).handle, source.offset() + skip, bytesPerRow, bytesPerRow * sourceHeight,
				((MetalTexture) destination).handle, layer, mipLevel, destX, destY, copyWidth, copyHeight);
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
		this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int x, int y, int width,
			int height) {
		GpuFormat format = source.getFormat();
		MetalNative.blitTextureToBuffer(this.ctx, ((MetalTexture) source).handle, mipLevel, x, y, width, height, ((MetalBuffer) destination).handle, offset,
				(long) width * format.blockSize());
		this.afterGpu(callback);
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY, int width,
			int height) {
		MetalNative.blitTextureToTexture(this.ctx, ((MetalTexture) source).handle, ((MetalTexture) destination).handle, mipLevel, destX, destY, sourceX,
				sourceY, width, height);
	}

	@Override
	public GpuFence createFence() {
		long index = this.recordingIndex;
		return new GpuFence() {
			@Override
			public boolean awaitCompletion(long timeoutNanos) {
				if (MetalNative.contextCompleted(MetalCommandEncoder.this.ctx) >= index) {
					return true;
				}
				if (timeoutNanos == 0L) {
					return false;
				}
				// Like glClientWaitSync with a flush: work still being recorded is committed so it can finish.
				if (index == MetalCommandEncoder.this.recordingIndex) {
					MetalCommandEncoder.this.commit();
				}
				MetalNative.contextWait(MetalCommandEncoder.this.ctx, index);
				return true;
			}

			@Override
			public void close() {
			}
		};
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
	}

	/** Schedules the frame's present; it happens when the frame is committed in {@link #submit}. */
	void present(long layer, MetalTextureView view) {
		MetalNative.present(this.ctx, layer, view.handle);
	}

	private record Deferred(long index, Runnable action) {
	}
}
