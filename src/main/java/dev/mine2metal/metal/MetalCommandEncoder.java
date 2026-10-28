package dev.mine2metal.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import dev.mine2metal.Mine2Metal;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

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
	private final @Nullable FrameStats stats;
	private @Nullable InterpolationDump interpolationDump;
	private @Nullable HdrRead hdrRead;
	/** Names render encoders after their renderpearl pass, for Xcode captures and Instruments traces. */
	private final boolean labels;
	private long gpuErrorsSeen;
	/** GPU errors logged but not yet handed to {@link MetalDevice#getLastDebugMessages}. */
	private final List<String> gpuErrors = new ArrayList<>();
	private boolean warnedPartialClear;
	private final Map<MetalTexture, PendingClear> pendingClears = new IdentityHashMap<>();

	MetalCommandEncoder(long ctx, boolean labels) {
		this.ctx = ctx;
		this.labels = labels || Boolean.getBoolean("mine2metal.labels") || FrameStats.ENABLED;
		this.transientMemory = new MetalTransientMemory(ctx, this);
		this.stats = FrameStats.ENABLED ? new FrameStats(ctx) : null;
	}

	long recordingIndex() {
		return this.recordingIndex;
	}

	void releaseAfterGpu(long handle) {
		this.afterGpu(() -> MetalNative.resourceRelease(this.ctx, handle));
	}

	void afterGpu(Runnable action) {
		this.deferred.add(new Deferred(this.recordingIndex, action));
	}

	@Override
	public void submit() {
		this.transientMemory.endSubmit();
		this.commit();
		this.waitForGpu(this.recordingIndex - MAX_FRAMES_IN_FLIGHT);
		this.runCompleted();
		this.checkGpuErrors();
		if (this.stats != null) {
			this.stats.endFrame();
		}
	}

	/** Logs command buffers that failed on the GPU; a lost device ends the game with a crash report. */
	private void checkGpuErrors() {
		long count = MetalNative.contextErrorCount(this.ctx);
		if (count == this.gpuErrorsSeen) {
			return;
		}
		this.gpuErrorsSeen = count;
		MetalNative.GpuErrors errors = MetalNative.contextTakeErrors(this.ctx);
		for (String message : errors.messages()) {
			Mine2Metal.LOGGER.error("Metal: {}", message);
		}
		this.gpuErrors.addAll(errors.messages());
		if (errors.deviceLost()) {
			throw new GpuDeviceLossException("The Metal device was lost: " + String.join("; ", errors.messages()));
		}
	}

	List<String> takeGpuErrors() {
		List<String> messages = List.copyOf(this.gpuErrors);
		this.gpuErrors.clear();
		return messages;
	}

	private void waitForGpu(long index) {
		if (this.stats == null) {
			MetalNative.contextWait(this.ctx, index);
			return;
		}
		long start = System.nanoTime();
		MetalNative.contextWait(this.ctx, index);
		this.stats.addWait(System.nanoTime() - start);
	}

	/** Commits what has been recorded without ending the frame. */
	private void commit() {
		this.flushPendingClears();
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

	private @Nullable String timedPassLabel;

	@Override
	public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
		// Fold pending clears of this pass's attachments into its load actions; any other pending clear must land first.
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
		@Nullable Vector4fc[] colorClears = new Vector4fc[colors.size()];
		for (int i = 0; i < colors.size(); i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
			PendingClear pending = this.takePendingClear(attachment.textureView());
			colorClears[i] = attachment.clearValue().orElse(pending != null ? pending.color() : null);
		}
		RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth = descriptor.depthAttachment();
		double depthClear = Double.NaN;
		if (depth != null) {
			PendingClear pending = this.takePendingClear(depth.textureView());
			depthClear = depth.clearValue().orElse(pending != null ? pending.depth() : Double.NaN);
		}
		this.flushPendingClears();

		if (FrameStats.PASS_TIMES) {
			this.recordingIndex = MetalNative.contextCommit(this.ctx, "(between passes)") + 1;
			this.timedPassLabel = descriptor.label().get();
		}

		for (int i = 0; i < colors.size(); i++) {
			Vector4fc color = colorClears[i];
			MetalNative.passColor(this.ctx, i, ((MetalTextureView) colors.get(i).textureView()).handle, color != null,
					color == null ? 0.0F : color.x(), color == null ? 0.0F : color.y(), color == null ? 0.0F : color.z(), color == null ? 0.0F : color.w());
		}

		int depthFormat = 0;
		int stencilFormat = 0;
		if (depth != null) {
			MetalTextureView view = (MetalTextureView) depth.textureView();
			GpuFormat format = view.texture().getFormat();
			MetalNative.passDepth(this.ctx, view.handle, format.hasStencilAspect(), !Double.isNaN(depthClear), Double.isNaN(depthClear) ? 1.0 : depthClear);
			depthFormat = MetalFormats.pixelFormat(format);
			stencilFormat = format.hasStencilAspect() ? depthFormat : 0;
		}

		if (this.labels) {
			MetalNative.passOpen(this.ctx, descriptor.label().get());
		} else {
			MetalNative.passOpen(this.ctx);
		}
		return new MetalRenderPass(this.ctx, this, depthFormat, stencilFormat, descriptor.renderArea());
	}

	@Override
	public void submitRenderPass() {
		MetalNative.passClose(this.ctx);
		if (this.timedPassLabel != null) {
			this.recordingIndex = MetalNative.contextCommit(this.ctx, this.timedPassLabel) + 1;
			this.timedPassLabel = null;
		}
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
			return;
		}
		// The GUI item atlas clears one slot before redrawing it, for animated items such as compasses and clocks.
		this.flushPendingClears();
		MetalTexture target = (MetalTexture) colorTexture;
		MetalTexture depthTarget = (MetalTexture) depthTexture;
		boolean hasStencil = depthTarget != null && depthTarget.getFormat().hasStencilAspect();
		if (!MetalNative.clearRegion(this.ctx, target.handle, color.x(), color.y(), color.z(), color.w(), depthTarget == null ? 0L : depthTarget.handle, hasStencil,
				depth, x, y, width, height, mipLevel) && !this.warnedPartialClear) {
			this.warnedPartialClear = true;
			Mine2Metal.LOGGER.warn("Could not create the pipeline for partial texture clears; skipping them");
		}
	}

	@Override
	public void clearDepthTexture(GpuTexture texture, double depth) {
		this.clear(null, null, texture, depth);
	}

	/**
	 * Clears are deferred: a standalone clear pass writes the whole target only for the next pass to load it again, while
	 * folded into that pass's load action it is free on Apple's tile-based GPUs.
	 */
	private void clear(@Nullable GpuTexture colorTexture, @Nullable Vector4fc color, @Nullable GpuTexture depthTexture, double depth) {
		if (colorTexture != null && color != null) {
			this.pendingClears.put((MetalTexture) colorTexture, new PendingClear(new Vector4f(color), Double.NaN));
		}
		if (depthTexture != null) {
			this.pendingClears.put((MetalTexture) depthTexture, new PendingClear(null, depth));
		}
	}

	/** Removes and returns the pending clear a pass attaching this view can take over, if any. */
	private @Nullable PendingClear takePendingClear(GpuTextureView view) {
		if (this.pendingClears.isEmpty() || view.baseMipLevel() != 0) {
			return null;
		}
		return this.pendingClears.remove((MetalTexture) view.texture());
	}

	/** Runs every pending clear as its own pass; called before anything that could observe the cleared textures. */
	private void flushPendingClears() {
		if (this.pendingClears.isEmpty()) {
			return;
		}
		for (Map.Entry<MetalTexture, PendingClear> entry : this.pendingClears.entrySet()) {
			MetalTexture texture = entry.getKey();
			PendingClear clear = entry.getValue();
			if (texture.isClosed()) {
				continue;
			}
			Vector4fc color = clear.color();
			if (color != null) {
				MetalNative.passColor(this.ctx, 0, texture.handle, true, color.x(), color.y(), color.z(), color.w());
			} else {
				MetalNative.passDepth(this.ctx, texture.handle, texture.getFormat().hasStencilAspect(), true, clear.depth());
			}
			if (this.labels) {
				MetalNative.passOpen(this.ctx, "Clear");
			} else {
				MetalNative.passOpen(this.ctx);
			}
			MetalNative.passClose(this.ctx);
		}
		this.pendingClears.clear();
	}

	@Override
	public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
		MetalBuffer target = (MetalBuffer) destination.buffer();
		// Shared memory: a buffer no GPU work can still touch is written in place, which saves a blit encoder. A busy
		// buffer whose contents are replaced entirely moves to fresh memory first, which is just as cheap.
		boolean replacesAll = destination.offset() == 0L && data.remaining() == target.size();
		boolean idle = target.isIdle(MetalNative.contextCompleted(this.ctx));
		if (!idle && replacesAll && target.canRename()) {
			target.rename(this.ctx);
			idle = true;
		}
		if (idle) {
			MemoryUtil.memCopy(MemoryUtil.memAddress(data), target.address + destination.offset(), data.remaining());
			if (this.stats != null) {
				this.stats.addBufferWrite(target, data.remaining(), false);
			}
			return;
		}

		// Otherwise staged through a blit, so the write lands in command order after earlier GPU reads of the old contents.
		if (this.stats != null) {
			this.stats.addBufferWrite(target, data.remaining(), true);
		}
		GpuBufferSlice staging = this.transientMemory.uploadGpu(data, 4L, GpuBuffer.USAGE_COPY_SRC);
		MetalNative.blitBuffer(this.ctx, ((MetalBuffer) staging.buffer()).handle, staging.offset(), target.useForWrite(), destination.offset(), data.remaining());
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
		MetalBuffer from = (MetalBuffer) source.buffer();
		MetalBuffer to = (MetalBuffer) target.buffer();
		long completed = MetalNative.contextCompleted(this.ctx);
		if (from.isIdle(completed) && to.isIdle(completed)) {
			MemoryUtil.memCopy(from.address + source.offset(), to.address + target.offset(), source.length());
			if (this.stats != null) {
				this.stats.addBufferCopy(to, source.length(), false);
			}
			return;
		}

		if (this.stats != null) {
			this.stats.addBufferCopy(to, source.length(), true);
		}
		MetalNative.blitBuffer(this.ctx, from.use(), source.offset(), to.useForWrite(), target.offset(), source.length());
	}

	@Override
	public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int layer, int x, int y, int width, int height) {
		this.flushPendingClears();
		if (this.stats != null) {
			this.stats.addTextureWrite(source.remaining());
		}
		GpuBufferSlice staging = this.transientMemory.uploadGpu(source, 16L, GpuBuffer.USAGE_COPY_SRC);
		int bytesPerRow = width * destination.getFormat().blockSize();
		MetalNative.blitBufferToTexture(this.ctx, ((MetalBuffer) staging.buffer()).handle, staging.offset(), bytesPerRow, (long) bytesPerRow * height,
				((MetalTexture) destination).handle, layer, mipLevel, x, y, width, height);
	}

	@Override
	public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, GpuTexture destination, int destX,
			int destY, int copyWidth, int copyHeight, int mipLevel, int layer) {
		this.flushPendingClears();
		int texelSize = destination.getFormat().blockSize();
		long skip = (sourceX + (long) sourceY * sourceWidth) * texelSize;
		long bytesPerRow = (long) sourceWidth * texelSize;
		MetalNative.blitBufferToTexture(this.ctx, ((MetalBuffer) source.buffer()).use(), source.offset() + skip, bytesPerRow, bytesPerRow * sourceHeight,
				((MetalTexture) destination).handle, layer, mipLevel, destX, destY, copyWidth, copyHeight);
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
		this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int x, int y, int width,
			int height) {
		this.flushPendingClears();
		GpuFormat format = source.getFormat();
		MetalNative.blitTextureToBuffer(this.ctx, ((MetalTexture) source).handle, mipLevel, x, y, width, height, ((MetalBuffer) destination).useForWrite(), offset,
				(long) width * format.blockSize());
		this.afterGpu(callback);
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY, int width,
			int height) {
		this.flushPendingClears();
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
				MetalCommandEncoder.this.waitForGpu(index);
				return true;
			}

			@Override
			public void close() {
			}
		};
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
		this.flushPendingClears();
		MetalNative.timestampWrite(this.ctx, ((MetalTimestampQueryPool) pool).handle, index);
	}

	/** MetalFX temporal upscale of color into output, in command order; false if MetalFX rejected the sizes or formats. */
	boolean upscaleTemporal(MetalTexture color, MetalTexture depth, MetalTexture output, float[] matrices, float jitterX, float jitterY, boolean reset) {
		this.flushPendingClears();
		return MetalNative.metalFxTemporalUpscale(this.ctx, color.handle, depth.handle, output.handle, matrices, jitterX, jitterY, reset);
	}

	/** Captures the world for frame interpolation, in command order; false if MetalFX rejected the sizes or formats. */
	boolean captureForInterpolation(MetalTexture color, MetalTexture depth, float[] matrices, float[] camera, boolean reset) {
		this.flushPendingClears();
		return MetalNative.frameInterpolationCapture(this.ctx, color.handle, depth.handle, matrices, camera, reset);
	}

	void captureWorldForHdr(MetalTexture color) {
		this.flushPendingClears();
		MetalNative.hdrCapture(this.ctx, color.handle);
	}

	void readNextHdrFrame(int width, int height, Consumer<float @Nullable []> done) {
		this.hdrRead = new HdrRead(width, height, done);
	}

	void releaseFrameInterpolation() {
		MetalNative.frameInterpolationRelease(this.ctx);
	}

	void dumpNextInterpolatedFrame(Path file, int width, int height, Consumer<Boolean> done) {
		this.interpolationDump = new InterpolationDump(file, width, height, done);
	}

	/** MetalFX spatial upscale of input into output, in command order; false if MetalFX rejected the sizes or formats. */
	boolean upscaleSpatial(MetalTexture input, MetalTexture output) {
		this.flushPendingClears();
		return MetalNative.metalFxSpatialUpscale(this.ctx, input.handle, output.handle);
	}

	/** Schedules the frame's present; it happens when the frame is committed in {@link #submit}. */
	void present(long layer, MetalTextureView view) {
		this.flushPendingClears();
		if (this.stats == null) {
			MetalNative.present(this.ctx, layer, view.handle);
		} else {
			long start = System.nanoTime();
			MetalNative.present(this.ctx, layer, view.handle);
			this.stats.addPresent(System.nanoTime() - start);
		}
		if (this.interpolationDump != null) {
			this.writeInterpolationDump(this.interpolationDump);
			this.interpolationDump = null;
		}
		if (this.hdrRead != null) {
			this.readHdrFrame(this.hdrRead);
			this.hdrRead = null;
		}
	}

	/** Reads back the frame the present just composed for EDR; like the generated frame, kept for two presents. */
	private void readHdrFrame(HdrRead read) {
		long frame = MetalNative.hdrLastFrame(this.ctx);
		if (frame == 0L) {
			read.done().accept(null);
			return;
		}
		int pixels = read.width() * read.height();
		long size = pixels * 8L;
		MetalBuffer buffer = new MetalBuffer(this, MetalNative.bufferCreate(this.ctx, size), 0, size);
		MetalNative.blitTextureToBuffer(this.ctx, frame, 0, 0, 0, read.width(), read.height(), buffer.useForWrite(), 0L, read.width() * 8L);
		this.afterGpu(() -> {
			ByteBuffer halves = MemoryUtil.memByteBuffer(buffer.address, (int) size);
			float[] values = new float[pixels * 4];
			for (int i = 0; i < values.length; i++) {
				values[i] = Float.float16ToFloat(halves.getShort(i * 2));
			}
			buffer.close();
			read.done().accept(values);
		});
	}

	private record HdrRead(int width, int height, Consumer<float @Nullable []> done) {
	}

	/** Reads back the generated frame the present just showed; its slot is not reused until two presents later. */
	private void writeInterpolationDump(InterpolationDump dump) {
		long generated = MetalNative.frameInterpolationLastOutput(this.ctx);
		if (generated == 0L) {
			dump.done().accept(false);
			return;
		}
		long size = (long) dump.width() * dump.height() * 4;
		MetalBuffer buffer = new MetalBuffer(this, MetalNative.bufferCreate(this.ctx, size), 0, size);
		MetalNative.blitTextureToBuffer(this.ctx, generated, 0, 0, 0, dump.width(), dump.height(), buffer.useForWrite(), 0L, dump.width() * 4L);
		this.afterGpu(() -> {
			// Render targets are stored bottom-up, as in GL.
			STBImageWrite.stbi_flip_vertically_on_write(true);
			boolean written = STBImageWrite.stbi_write_png(dump.file().toString(), dump.width(), dump.height(), 4,
					MemoryUtil.memByteBuffer(buffer.address, (int) size), dump.width() * 4);
			buffer.close();
			dump.done().accept(written);
		});
	}

	private record InterpolationDump(Path file, int width, int height, Consumer<Boolean> done) {
	}

	private record Deferred(long index, Runnable action) {
	}

	/** Either a color (for a color target) or a depth value (for a depth target). */
	private record PendingClear(@Nullable Vector4fc color, double depth) {
	}
}
