package dev.mine2metal.metal;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayDeque;

/**
 * A shared-storage MTLBuffer. On Apple silicon the CPU mapping is the GPU memory, so {@link #map} is a pointer offset.
 * Mapping is unsynchronized, as in Mojang's Vulkan backend.
 */
class MetalBuffer extends BaseGpuBuffer {
	/** The current memory; {@link #rename} can swap it. */
	long handle;
	long address;
	/** Only kept when frame stats are on, to name buffers in the transfer breakdown. */
	@Nullable String label;
	private final MetalCommandEncoder encoder;
	/** Submit index of the last recorded GPU command that reads or writes this buffer; 0 if none. */
	private long lastUse;
	/** Submit index of the last recorded GPU command that writes this buffer; 0 if none. */
	private long lastWrite;
	private boolean closed;
	/** A mapped view keeps pointing at the memory it was made for, so a mapped buffer never moves. */
	private boolean mapped;
	/** Memory this buffer moved away from that the GPU is done with, ready for the next rename. */
	private final ArrayDeque<Long> spares = new ArrayDeque<>();

	MetalBuffer(MetalCommandEncoder encoder, long handle, int usage, long size) {
		super(usage, size);
		this.encoder = encoder;
		this.handle = handle;
		this.address = MetalNative.bufferContents(handle);
	}

	/** The handle, for a GPU command being recorded now; marks the buffer busy until that submit completes. */
	long use() {
		this.lastUse = this.encoder.recordingIndex();
		return this.handle;
	}

	/** As {@link #use}, for a GPU command that writes the buffer. */
	long useForWrite() {
		this.lastWrite = this.encoder.recordingIndex();
		return this.use();
	}

	/** True when every recorded GPU write has landed, so the CPU reads the current contents. */
	boolean isReadableOnCpu(long completedIndex) {
		return this.lastWrite <= completedIndex;
	}

	/** True when no recorded GPU command can still touch the buffer, so the CPU may read or write it directly. */
	boolean isIdle(long completedIndex) {
		return this.lastUse <= completedIndex;
	}

	boolean canRename() {
		return !this.mapped;
	}

	/**
	 * Moves the buffer to other memory for a write that replaces all of it while the GPU may still read the old
	 * contents: commands already recorded keep reading those, and the old memory becomes a spare once they finish.
	 */
	void rename(long ctx) {
		long old = this.handle;
		Long spare = this.spares.poll();
		this.handle = spare != null ? spare : MetalNative.bufferCreate(ctx, this.size());
		this.address = MetalNative.bufferContents(this.handle);
		this.lastUse = 0L;
		this.lastWrite = 0L;
		this.encoder.afterGpu(() -> {
			if (this.closed) {
				MetalNative.resourceRelease(ctx, old);
			} else {
				this.spares.add(old);
			}
		});
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
			for (long spare : this.spares) {
				this.encoder.releaseAfterGpu(spare);
			}
			this.spares.clear();
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
		this.mapped = true;
		return new GpuBufferSlice.MappedView(this.slice(offset, length), MemoryUtil.memByteBuffer(this.address + offset, Math.toIntExact(length)), () -> {
		});
	}
}
