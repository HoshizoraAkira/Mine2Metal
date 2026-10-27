package dev.mine2metal.metal;

import com.mojang.renderpearl.api.commands.GpuQueryPool;

import java.util.OptionalLong;

/**
 * GPU timestamps in nanoseconds of host time, filled in when the command buffer before each one completes. Used by
 * the frame timer behind the debug screen's GPU utilization and by the Tracy GPU profiler.
 */
final class MetalTimestampQueryPool implements GpuQueryPool {
	private final int size;
	final long handle;

	MetalTimestampQueryPool(int size) {
		this.size = size;
		this.handle = MetalNative.timestampPoolCreate(size);
	}

	@Override
	public int size() {
		return this.size;
	}

	@Override
	public OptionalLong getValue(int index) {
		return this.getValues(index, 1)[0];
	}

	@Override
	public OptionalLong[] getValues(int index, int count) {
		if (index < 0 || count < 0 || index + count > this.size) {
			throw new IndexOutOfBoundsException("Queries " + index + ".." + (index + count) + " out of " + this.size);
		}
		long[] raw = MetalNative.timestampPoolRead(this.handle, index, count);
		OptionalLong[] values = new OptionalLong[count];
		for (int i = 0; i < count; i++) {
			values[i] = raw[i] < 0L ? OptionalLong.empty() : OptionalLong.of(raw[i]);
		}
		return values;
	}

	@Override
	public void close() {
		// Pending writes keep the native pool alive until they complete.
		MetalNative.release(this.handle);
	}
}
