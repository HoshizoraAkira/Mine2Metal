package dev.mine2metal.metal;

import com.mojang.renderpearl.api.commands.GpuQueryPool;

import java.util.Arrays;
import java.util.OptionalLong;

/** GPU timestamps are not sampled yet; every query reads as unavailable, which the profiler already handles. */
final class MetalTimestampQueryPool implements GpuQueryPool {
	private final int size;

	MetalTimestampQueryPool(int size) {
		this.size = size;
	}

	@Override
	public int size() {
		return this.size;
	}

	@Override
	public OptionalLong getValue(int index) {
		return OptionalLong.empty();
	}

	@Override
	public OptionalLong[] getValues(int index, int count) {
		OptionalLong[] values = new OptionalLong[count];
		Arrays.fill(values, OptionalLong.empty());
		return values;
	}

	@Override
	public void close() {
	}
}
