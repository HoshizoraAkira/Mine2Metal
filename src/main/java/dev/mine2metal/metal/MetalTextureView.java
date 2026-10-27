package dev.mine2metal.metal;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

final class MetalTextureView extends BaseGpuTextureView {
	/** The MTLTexture to bind: the texture itself for a full view, otherwise a real MTLTexture view. */
	final long handle;
	private final boolean ownsHandle;
	private final MetalCommandEncoder encoder;
	private boolean closed;

	MetalTextureView(MetalCommandEncoder encoder, MetalTexture texture, int baseMipLevel, int mipLevels) {
		super(texture, baseMipLevel, mipLevels);
		this.encoder = encoder;
		this.ownsHandle = baseMipLevel != 0 || mipLevels != texture.getMipLevels();
		this.handle = this.ownsHandle ? MetalNative.textureViewCreate(texture.handle, baseMipLevel, mipLevels) : texture.handle;
	}

	MetalTexture metalTexture() {
		return (MetalTexture) this.texture();
	}

	@Override
	public boolean isClosed() {
		return this.closed || this.texture().isClosed();
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			if (this.ownsHandle) {
				this.encoder.releaseAfterGpu(this.handle);
			}
		}
	}
}
