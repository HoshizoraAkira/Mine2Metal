package dev.mine2metal.metal;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;

import java.util.OptionalDouble;

final class MetalSampler implements GpuSampler {
	/** Same rule as Mojang's Vulkan backend: a max LOD at or below this means "base level only". */
	private static final double BASE_LEVEL_ONLY_LOD = 0.25;

	final long handle;
	private final AddressMode addressModeU;
	private final AddressMode addressModeV;
	private final FilterMode minFilter;
	private final FilterMode magFilter;
	private final int maxAnisotropy;
	private final OptionalDouble maxLod;
	private final MetalCommandEncoder encoder;
	private boolean closed;

	MetalSampler(long ctx, MetalCommandEncoder encoder, AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter,
			int maxAnisotropy, OptionalDouble maxLod) {
		this.encoder = encoder;
		this.addressModeU = addressModeU;
		this.addressModeV = addressModeV;
		this.minFilter = minFilter;
		this.magFilter = magFilter;
		this.maxAnisotropy = maxAnisotropy;
		this.maxLod = maxLod;

		double lod = maxLod.orElse(1000.0);
		int mipFilter = lod > BASE_LEVEL_ONLY_LOD ? 2 : 0;
		this.handle = MetalNative.samplerCreate(ctx, addressMode(addressModeU), addressMode(addressModeV), filter(minFilter), filter(magFilter), mipFilter,
				maxAnisotropy, (float) Math.max(BASE_LEVEL_ONLY_LOD, lod));
	}

	private static int addressMode(AddressMode mode) {
		return switch (mode) {
			case REPEAT -> 0;
			case CLAMP_TO_EDGE -> 1;
		};
	}

	private static int filter(FilterMode mode) {
		return switch (mode) {
			case NEAREST -> 0;
			case LINEAR -> 1;
		};
	}

	@Override
	public AddressMode getAddressModeU() {
		return this.addressModeU;
	}

	@Override
	public AddressMode getAddressModeV() {
		return this.addressModeV;
	}

	@Override
	public FilterMode getMinFilter() {
		return this.minFilter;
	}

	@Override
	public FilterMode getMagFilter() {
		return this.magFilter;
	}

	@Override
	public int getMaxAnisotropy() {
		return this.maxAnisotropy;
	}

	@Override
	public OptionalDouble getMaxLod() {
		return this.maxLod;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.encoder.releaseAfterGpu(this.handle);
		}
	}
}
