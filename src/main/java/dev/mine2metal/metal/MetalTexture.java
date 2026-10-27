package dev.mine2metal.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;

final class MetalTexture extends BaseGpuTexture {
	final long handle;
	private final MetalCommandEncoder encoder;
	private boolean closed;

	MetalTexture(MetalCommandEncoder encoder, long handle, int usage, String label, GpuFormat format, int width, int height, int layers, int mipLevels) {
		super(usage, label, format, width, height, layers, mipLevels);
		this.encoder = encoder;
		this.handle = handle;
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
