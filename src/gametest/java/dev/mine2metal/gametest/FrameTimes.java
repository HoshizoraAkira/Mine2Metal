package dev.mine2metal.gametest;

import java.util.Arrays;
import java.util.Locale;

/** Every frame's duration between {@link #start} and {@link #stop}, as Minecraft logs it, for any graphics backend. */
public final class FrameTimes {
	private static long[] durations = new long[0];
	private static int count;
	private static boolean recording;

	private FrameTimes() {
	}

	public static synchronized void record(long nanos) {
		if (!recording) {
			return;
		}
		if (count == durations.length) {
			durations = Arrays.copyOf(durations, Math.max(4096, count * 2));
		}
		durations[count++] = nanos;
		if (nanos > 50_000_000L) {
			// To line up with GC and pipeline logs.
			System.out.printf(Locale.ROOT, "[%s] Slow frame: %.1f ms%n", java.time.LocalTime.now(), nanos / 1.0e6);
		}
	}

	public static synchronized void start() {
		count = 0;
		recording = true;
	}

	/** Frames, average frame rate, percentiles and how many frames took over 50, 100 and 250 ms. */
	public static synchronized String stop() {
		recording = false;
		if (count == 0) {
			return "no frames";
		}
		long[] sorted = Arrays.copyOf(durations, count);
		Arrays.sort(sorted);
		long total = 0;
		int over50 = 0;
		int over100 = 0;
		int over250 = 0;
		for (long duration : sorted) {
			total += duration;
			over50 += duration > 50_000_000L ? 1 : 0;
			over100 += duration > 100_000_000L ? 1 : 0;
			over250 += duration > 250_000_000L ? 1 : 0;
		}
		return String.format(Locale.ROOT, "%d frames, %.0f fps, p50 %.2f / p99 %.2f / p99.9 %.2f / max %.1f ms, >50 ms %d, >100 ms %d, >250 ms %d",
				count, count * 1.0e9 / total, ms(sorted, 0.5), ms(sorted, 0.99), ms(sorted, 0.999), sorted[count - 1] / 1.0e6, over50, over100, over250);
	}

	private static double ms(long[] sorted, double fraction) {
		return sorted[Math.min(sorted.length - 1, (int) (fraction * sorted.length))] / 1.0e6;
	}
}
