package dev.mine2metal.metal;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * A shared-storage MTLBuffer. On Apple silicon the CPU mapping is the GPU memory, so {@link #map} is a pointer offset.
 * Mapping is unsynchronized, as in Mojang's Vulkan backend.
 */
class MetalBuffer extends BaseGpuBuffer {
	final long handle;
	final long address;
	private final MetalCommandEncoder encoder;
	private boolean closed;

	MetalBuffer(MetalCommandEncoder encoder, long handle, int usage, long size) {
		super(usage, size);
		this.encoder = encoder;
		this.handle = handle;
		this.address = MetalNative.bufferContents(handle);
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

	@Override
	public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
		if (this.isClosed()) {
			throw new IllegalStateException("Buffer already closed");
		}
		if (offset < 0 || length < 0 || offset + length > this.size()) {
			throw new IllegalArgumentException("Mapping " + offset + "+" + length + " is outside a buffer of " + this.size() + " bytes");
		}
		return new GpuBufferSlice.MappedView(this.slice(offset, length), MemoryUtil.memByteBuffer(this.address + offset, Math.toIntExact(length)), () -> {
		});
	}
}
