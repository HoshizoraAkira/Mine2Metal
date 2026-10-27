package dev.mine2metal.metal;

import dev.mine2metal.Mine2Metal;

/**
 * Development aid: {@code -Dmine2metal.stats=true} logs, every few seconds, frames per second, GPU time per frame
 * (from Metal's command buffer timestamps) and how long the render thread blocked waiting for the GPU.
 */
final class FrameStats {
	/**
	 * {@code -Dmine2metal.passTimes=true} additionally commits every render pass in its own command buffer and reports GPU
	 * time per pass label. The extra commits cost performance, so use it only to find heavy passes.
	 */
	static final boolean PASS_TIMES = Boolean.getBoolean("mine2metal.passTimes");
	static final boolean ENABLED = PASS_TIMES || Boolean.getBoolean("mine2metal.stats");
	private static final int TOP_PASSES = 8;
	private static final long INTERVAL_NANOS = 5_000_000_000L;

	private final long ctx;
	private long windowStart = System.nanoTime();
	private double gpuSecondsAtStart;
	private long[] encodersAtStart = {0L, 0L, 0L};
	private long waitNanos;
	private long presentNanos;
	private int frames;
	private long copyBytes;
	private int copies;
	private int cpuCopies;
	private long writeBytes;
	private int writes;
	private int cpuWrites;
	private long textureBytes;
	/** Buffers that still needed a blit, by label, to show what keeps blit encoders alive. */
	private final java.util.Map<String, Integer> blitTargets = new java.util.TreeMap<>();

	FrameStats(long ctx) {
		this.ctx = ctx;
	}

	void addPresent(long nanos) {
		this.presentNanos += nanos;
	}

	void addWait(long nanos) {
		this.waitNanos += nanos;
	}

	void addBufferCopy(MetalBuffer target, long bytes, boolean blit) {
		this.copyBytes += bytes;
		this.copies++;
		this.countPath(target, blit, "copy");
		if (!blit) {
			this.cpuCopies++;
		}
	}

	void addBufferWrite(MetalBuffer target, long bytes, boolean blit) {
		this.writeBytes += bytes;
		this.writes++;
		this.countPath(target, blit, "write");
		if (!blit) {
			this.cpuWrites++;
		}
	}

	private void countPath(MetalBuffer target, boolean blit, String kind) {
		if (blit) {
			this.blitTargets.merge(kind + " to " + (target.label != null ? target.label : "?"), 1, Integer::sum);
		}
	}

	void addTextureWrite(long bytes) {
		this.textureBytes += bytes;
	}

	void endFrame() {
		this.frames++;
		long now = System.nanoTime();
		long elapsed = now - this.windowStart;
		if (elapsed < INTERVAL_NANOS) {
			return;
		}

		double gpuSeconds = MetalNative.contextGpuSeconds(this.ctx);
		long[] encoders = MetalNative.contextEncoderCounts(this.ctx);
		Mine2Metal.LOGGER.info("Metal frame stats: {} fps, GPU {} ms/frame, CPU waiting on GPU {} ms/frame, present {} ms/frame, {} render + {} blit "
						+ "encoders/frame, {} merged passes/frame",
				Math.round(this.frames * 1e9 / elapsed),
				String.format("%.3f", (gpuSeconds - this.gpuSecondsAtStart) * 1000.0 / this.frames),
				String.format("%.3f", this.waitNanos / 1e6 / this.frames),
				String.format("%.3f", this.presentNanos / 1e6 / this.frames),
				String.format("%.1f", (double) (encoders[0] - this.encodersAtStart[0]) / this.frames),
				String.format("%.1f", (double) (encoders[1] - this.encodersAtStart[1]) / this.frames),
				String.format("%.1f", (double) (encoders[2] - this.encodersAtStart[2]) / this.frames));

		Mine2Metal.LOGGER.info("Metal transfers per frame: {} buffer copies ({} on CPU, {} KB), {} buffer writes ({} on CPU, {} KB), {} KB texture writes; "
						+ "blits per {} frames: {}",
				String.format("%.1f", (double) this.copies / this.frames), String.format("%.1f", (double) this.cpuCopies / this.frames),
				this.copyBytes / 1024 / this.frames,
				String.format("%.1f", (double) this.writes / this.frames), String.format("%.1f", (double) this.cpuWrites / this.frames),
				this.writeBytes / 1024 / this.frames, this.textureBytes / 1024 / this.frames, this.frames, this.blitTargets);
		if (PASS_TIMES) {
			this.logPassTimes();
		}

		this.windowStart = now;
		this.gpuSecondsAtStart = gpuSeconds;
		this.encodersAtStart = encoders;
		this.waitNanos = 0L;
		this.presentNanos = 0L;
		this.frames = 0;
		this.copyBytes = 0L;
		this.copies = 0;
		this.cpuCopies = 0;
		this.writeBytes = 0L;
		this.writes = 0;
		this.cpuWrites = 0;
		this.blitTargets.clear();
		this.textureBytes = 0L;
	}

	private void logPassTimes() {
		StringBuilder line = new StringBuilder("GPU ms/frame by pass:");
		String[] entries = MetalNative.contextTakeLabelTimes(this.ctx).split("\n");
		for (int i = 0; i < Math.min(TOP_PASSES, entries.length); i++) {
			int separator = entries[i].lastIndexOf('=');
			if (separator < 0) {
				continue;
			}
			double seconds = Double.parseDouble(entries[i].substring(separator + 1));
			line.append(' ').append(entries[i], 0, separator).append('=').append(String.format("%.3f", seconds * 1000.0 / this.frames));
		}
		Mine2Metal.LOGGER.info(line.toString());
	}
}
