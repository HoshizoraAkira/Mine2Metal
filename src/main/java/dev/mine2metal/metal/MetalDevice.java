package dev.mine2metal.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class MetalDevice implements GpuDeviceBackend {
	private static final int TEXTURE_RENDER_TARGET = 1;
	private static final int TEXTURE_CUBE = 2;

	private final long ctx;
	private final MetalCommandEncoder encoder;
	private final DeviceInfo info;
	private final boolean validation;
	final boolean spatialUpscalingSupported;

	/** Takes ownership of ctx. */
	MetalDevice(long ctx, String name, boolean validation, boolean labels) {
		this.ctx = ctx;
		this.validation = validation;
		this.encoder = new MetalCommandEncoder(ctx, labels);
		this.spatialUpscalingSupported = MetalNative.metalFxSpatialSupported(ctx);
		this.info = new DeviceInfo(
				name,
				"Apple",
				"Metal 3 (mine2metal)",
				true,
				"Metal",
				1.0F,
				new DeviceLimits(16, 256, 16384, MetalNative.contextMaxBufferLength(ctx), Integer.MAX_VALUE, 8, Integer.MAX_VALUE),
				// Shader draw parameters stay off until gl_DrawID is emulated; interleaved (VK_EXT_multi_draw style) multi-draw is not offered.
				new DeviceFeatures(true, false, false, true, true, true, true, true),
				Set.of(),
				// Mojang's Vulkan backend requires explicit depth on Apple silicon; keep the same contract.
				new HintsAndWorkarounds(false, false, true, false),
				DeviceType.INTEGRATED);
	}

	@Override
	public GpuSurfaceBackend createSurface(long window, BooleanSupplier isIconified) {
		return new MetalSurface(this.ctx, this.encoder, window);
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		return this.encoder;
	}

	@Override
	public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter, int maxAnisotropy,
			OptionalDouble maxLod) {
		return new MetalSampler(this.ctx, this.encoder, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(@Nullable String label, int usage, GpuFormat format, int width, int height, int layers, int mipLevels) {
		int flags = 0;
		if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0 || format.hasDepthAspect() || format.hasStencilAspect()) {
			flags |= TEXTURE_RENDER_TARGET;
		}
		if ((usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) {
			flags |= TEXTURE_CUBE;
		}

		long handle = MetalNative.textureCreate(this.ctx, MetalFormats.pixelFormat(format), width, height, layers, mipLevels, flags);
		if (handle == 0L) {
			throw new IllegalStateException("Could not create " + width + "x" + height + " " + format + " texture " + label);
		}
		return new MetalTexture(this.encoder, handle, usage, label == null ? "" : label, format, width, height, layers, mipLevels);
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
		return new MetalTextureView(this.encoder, (MetalTexture) texture, baseMipLevel, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size) {
		MetalBuffer buffer = new MetalBuffer(this.encoder, this.newBuffer(size), usage, size);
		if (FrameStats.ENABLED && label != null) {
			buffer.label = label.get();
		}
		return buffer;
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data) {
		MetalBuffer buffer = new MetalBuffer(this.encoder, this.newBuffer(data.remaining()), usage, data.remaining());
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), buffer.address, data.remaining());
		return buffer;
	}

	private long newBuffer(long size) {
		long handle = MetalNative.bufferCreate(this.ctx, size);
		if (handle == 0L) {
			throw new IllegalStateException("Could not allocate a Metal buffer of " + size + " bytes");
		}
		return handle;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return this.encoder.takeGpuErrors();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return this.validation;
	}

	@Override
	public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo info) {
		MetalRenderPipeline pipeline = MetalRenderPipeline.compile(this.ctx, this.encoder, info);
		return () -> pipeline;
	}

	MetalCommandEncoder encoder() {
		return this.encoder;
	}

	@Override
	public void close() {
		MetalRenderer.deviceClosed(this);
		this.encoder.close();
		// The present thread holds the context; a no-op if the surface already stopped it.
		MetalNative.layerDetach(this.ctx);
		MetalNative.release(this.ctx);
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(int size) {
		return new MetalTimestampQueryPool(size);
	}

	@Override
	public long getTimestampCalibrationOffset() {
		return 0L;
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return this.info;
	}
}
