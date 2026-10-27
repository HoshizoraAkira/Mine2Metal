package dev.mine2metal.metal;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-submit scratch memory built on Mojang's {@link TransientBlockAllocator}. GPU blocks are shared MTLBuffers, so
 * "staging" and "GPU" allocations are the same thing; blocks return to the pool once their submit completes.
 */
final class MetalTransientMemory implements TransientMemory {
	private static final long BLOCK_SIZE = 1L << 20;

	private final MetalCommandEncoder encoder;
	private final TransientBlockAllocator<TransientBlockAllocator.Allocator.CpuBlock> cpu =
			new TransientBlockAllocator<>(BLOCK_SIZE, 16L, TransientBlockAllocator.Allocator.CpuBlock.memalloc());
	private final TransientBlockAllocator<Block> gpu;

	MetalTransientMemory(long ctx, MetalCommandEncoder encoder) {
		this.encoder = encoder;
		this.gpu = new TransientBlockAllocator<>(BLOCK_SIZE, 1L << 62, TransientBlockAllocator.Allocator.create(
				size -> new Block(MetalNative.bufferCreate(ctx, size), size),
				block -> MetalNative.resourceRelease(ctx, block.handle())));
	}

	/** Called once per frame before the commit; CPU blocks are free right away, GPU blocks once that submit completes. */
	void endSubmit() {
		this.cpu.rotate().run();
		this.encoder.afterGpu(this.gpu.rotate());
	}

	void close() {
		this.cpu.close();
		this.gpu.close();
	}

	@Override
	public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
		TransientBlockAllocator.Allocation<TransientBlockAllocator.Allocator.CpuBlock> allocation = this.cpu.allocate(size, alignment, minimumAllocation,
				elementSize);
		return MemoryUtil.memByteBuffer(allocation.block().address() + allocation.offset(), Math.toIntExact(allocation.size()));
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		TransientBlockAllocator.Allocation<Block> allocation = this.gpu.allocate(size, alignment, minimumAllocation, elementSize);
		TransientBuffer buffer = new TransientBuffer(allocation.block(), usage, this.encoder.recordingIndex());
		return new GpuBufferSlice.MappedView(new GpuBufferSlice(buffer, allocation.offset(), allocation.size()),
				MemoryUtil.memByteBuffer(buffer.address + allocation.offset(), Math.toIntExact(allocation.size())), () -> {
		});
	}

	@Override
	public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		return this.allocateStaging(size, alignment, usage, minimumAllocation, elementSize).slice();
	}

	@Override
	public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		return this.allocateStaging(size, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
		return this.uploadGpu(data, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
		long total = 0L;
		for (ByteBuffer buffer : data) {
			total = Mth.roundToward(total + buffer.remaining(), alignment);
		}

		GpuBufferSlice.MappedView mapped = this.allocateStaging(total, alignment, usage, minimumAllocation, elementSize);
		long destination = MemoryUtil.memAddress(mapped.data());
		long length = mapped.slice().length();
		long offset = 0L;
		for (ByteBuffer buffer : data) {
			if (offset >= length) {
				break;
			}
			MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), destination + offset, Math.min(length - offset, buffer.remaining()));
			offset = Mth.roundToward(offset + buffer.remaining(), alignment);
		}
		return mapped.slice();
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
		return this.multiUploadGpu(data, alignment, usage);
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
		List<GpuBufferSlice> slices = new ArrayList<>(data.size());
		for (ByteBuffer buffer : data) {
			slices.add(this.uploadGpu(List.of(buffer), alignment, usage, buffer.remaining(), 1L));
		}
		return slices;
	}

	private record Block(long handle, long size) implements TransientBlockAllocator.Allocator.Block {
		@Override
		public boolean suboptimal() {
			return false;
		}
	}

	/** A window onto a pooled block, valid only for the submit it was allocated in. */
	private final class TransientBuffer extends MetalBuffer {
		private final long submitIndex;

		TransientBuffer(Block block, int usage, long submitIndex) {
			super(MetalTransientMemory.this.encoder, block.handle(), usage, block.size());
			this.submitIndex = submitIndex;
		}

		@Override
		public boolean isClosed() {
			return this.submitIndex < MetalTransientMemory.this.encoder.recordingIndex();
		}

		@Override
		public void close() {
			// The pool owns the memory.
		}

		@Override
		boolean canRename() {
			// The memory belongs to the pool's block, shared with other allocations.
			return false;
		}

		@Override
		public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
			throw new IllegalStateException("Transient buffers cannot be mapped");
		}
	}
}
