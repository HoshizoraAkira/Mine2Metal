package dev.mine2metal.frame;

import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.mine2metal.Mine2Metal;
import dev.mine2metal.config.MetalSettings;
import dev.mine2metal.metal.MetalRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * Frame generation: a generated frame is shown between each pair the game renders, by MetalFX frame interpolation or
 * by AMD's FSR 3.1 frame generation (whichever the settings choose; one at a time).
 * <p>
 * The world is captured once it is drawn and upscaled, before the hand, screen effects and the GUI. The frame's
 * present then shows the frame MetalFX generates between the previous capture and this one, followed by this frame;
 * what was drawn over the world is laid over the generated frame unmoved. Frames without a world (menus over no
 * level) are shown as they are.
 * <p>
 * Driven by {@link WorldPass}, which hands it each frame's world once it is done.
 */
final class FrameGeneration {
	/**
	 * Scale MetalFX applies to the motion vectors, which are in pixels of the world's depth. A system property so the
	 * game test can compare the alternatives; read again whenever it changes.
	 */
	private static final String MOTION_SCALE_PROPERTY = "mine2metal.interpolation.motionScale";
	private static final String DEFAULT_MOTION_SCALE = "1,1";

	private final CameraMotion motion = new CameraMotion();
	private static final SystemToast.SystemToastId PAUSE_TOAST = new SystemToast.SystemToastId(10_000L);
	private String motionScaleText = DEFAULT_MOTION_SCALE;
	private float[] motionScale = parseScale(DEFAULT_MOTION_SCALE);
	private long previousCaptureNanos;
	/** True while the native side holds textures for it. */
	private boolean active;
	private boolean warnedFailure;

	/**
	 * The guard: frame generation pays off only while it costs less than half the time between rendered frames (two
	 * images in T beat one in T - cost exactly when T > 2 cost). When it costs more for a few seconds in a row, it pauses
	 * for a while and then tries again; costing more than the whole time between frames, it pauses within half a
	 * second. Too heavy again on the retry, it stays off until the choice changes, rather than dragging the game down
	 * every half minute. -Dmine2metal.frameGenerationGuard=false turns this off.
	 */
	private static final String GUARD_PROPERTY = "mine2metal.frameGenerationGuard";
	private static final long HEAVY_NANOS = 3_000_000_000L;
	private static final long SEVERE_NANOS = 500_000_000L;
	private static final long PAUSE_NANOS = 30_000_000_000L;
	/** Light for this long after a pause, a retry has worked: a later pause starts counting again. */
	private static final long RECOVERED_NANOS = 10_000_000_000L;
	private static final long STOPPED = Long.MAX_VALUE;
	/** Smoothed seconds between captures. */
	private double frameInterval;
	/** When frame generation first cost too much in the current run of such frames; 0 if it does not now. */
	private long heavySince;
	/** While paused, until this System.nanoTime(). */
	private long pausedUntil;
	private boolean pausedBefore;
	/** Pauses since frame generation last ran light for a while; the second one stops it. */
	private int pausesInARow;
	/** Since when frame generation has cost little enough; 0 while it costs too much. */
	private long lightSince;
	private MetalSettings.FrameGeneration guardedEngine = MetalSettings.FrameGeneration.OFF;

	/**
	 * Standby: frame generation shows each generated frame for a refresh and holds the game to half the refresh rate,
	 * so it only helps while the game cannot reach the refresh rate by itself. Until then it stands by and frames are
	 * shown as rendered; it steps in once the game's own frame time stays over a refresh for a second, and steps back
	 * now and then to see whether the game has become fast enough again. Off along with the guard.
	 */
	private static final long ENGAGE_NANOS = 1_000_000_000L;
	private static final long RECHECK_NANOS = 30_000_000_000L;
	private boolean standby = true;
	/** Since when the game's frame time has been on the other side of the line; 0 if it is not. */
	private long crossingSince;
	private long engagedSince;
	/** Smoothed seconds the game spends on a frame, not counting the wait for the display. */
	private double workTime;
	private long previousFrameNanos;

	/** Frames on screen per second for the debug screen, counted like Minecraft's own fps: over about a second. */
	private long shownCount = -1L;
	private long shownSince;
	private int shownPerSecond;

	/** The engine that will generate frames next, after checking what this Mac supports. */
	static MetalSettings.FrameGeneration activeEngine() {
		return switch (MetalSettings.get().frameGeneration) {
			case OFF -> MetalSettings.FrameGeneration.OFF;
			case METALFX -> MetalRenderer.supportsFrameInterpolation() ? MetalSettings.FrameGeneration.METALFX : MetalSettings.FrameGeneration.OFF;
			case FSR3 -> MetalRenderer.supportsFsrFrameGeneration() ? MetalSettings.FrameGeneration.FSR3 : MetalSettings.FrameGeneration.OFF;
		};
	}

	/** Once the world is drawn into main (and upscaled): captures it with its depth and camera. */
	void capture(WorldFrame frame) {
		CameraRenderState camera = frame.camera;
		MetalSettings.FrameGeneration engine = activeEngine();
		if (engine != guardedEngine) {
			// A new choice starts without a pause.
			guardedEngine = engine;
			pausedUntil = 0L;
			heavySince = 0L;
			pausesInARow = 0;
			lightSince = 0L;
			standby = true;
			crossingSince = 0L;
			workTime = 0.0;
		}
		if (engine == MetalSettings.FrameGeneration.OFF) {
			if (active) {
				MetalRenderer.releaseFrameInterpolation();
				active = false;
			}
			motion.reset();
			return;
		}
		if (this.standsBy(System.nanoTime())) {
			motion.reset();
			return;
		}
		if (pausedUntil != 0L && System.nanoTime() < pausedUntil) {
			// Paused by the guard: frames are shown as rendered, and the engine keeps its state for the retry; turned
			// off, it gives its textures back.
			if (pausedUntil == STOPPED && active) {
				MetalRenderer.releaseFrameInterpolation();
				active = false;
			}
			motion.reset();
			return;
		}
		pausedUntil = 0L;
		Matrix4f projection = frame.projection();
		if (projection == null) {
			motion.reset();
			return;
		}

		GpuTexture depth = frame.depth();
		Matrix4f viewProjection = new Matrix4f(projection).mul(camera.viewRotationMatrix);
		boolean reset = motion.begin(viewProjection, camera.pos, depth.getWidth(0), depth.getHeight(0));
		String scaleText = System.getProperty(MOTION_SCALE_PROPERTY, DEFAULT_MOTION_SCALE);
		if (!scaleText.equals(motionScaleText)) {
			motionScaleText = scaleText;
			motionScale = parseScale(scaleText);
			reset = true;
		}
		long now = System.nanoTime();
		float deltaTime = reset ? 0.0F : (now - previousCaptureNanos) / 1.0e9F;
		previousCaptureNanos = now;
		this.guard(engine, deltaTime, now);

		float[] values = new float[9];
		CameraMotion.planes(projection, camera.depthFar, values);
		values[2] = (float) Math.toDegrees(2.0 * Math.atan(1.0 / projection.m11()));
		values[3] = projection.m11() / projection.m00();
		values[4] = frame.toldJitterX();
		values[5] = frame.toldJitterY();
		values[6] = deltaTime;
		values[7] = motionScale[0];
		values[8] = motionScale[1];

		GpuTexture color = Objects.requireNonNull(frame.main.getColorTexture(), "color texture");
		boolean done = MetalRenderer.captureForInterpolation(engine == MetalSettings.FrameGeneration.FSR3, color, depth, motion.matrices, values, reset);
		if (!done && !warnedFailure) {
			warnedFailure = true;
			Mine2Metal.LOGGER.warn("{} could not generate frames for {}x{} with {}x{} depth; showing frames as rendered", engine, color.getWidth(0),
					color.getHeight(0), depth.getWidth(0), depth.getHeight(0));
		}
		active |= done;
		motion.finish(done);
	}

	/** Measures the game's frame time and decides whether frame generation stands by this frame. */
	private boolean standsBy(long now) {
		if (!Boolean.parseBoolean(System.getProperty(GUARD_PROPERTY, "true"))) {
			standby = false;
			return false;
		}
		long previous = previousFrameNanos;
		previousFrameNanos = now;
		double interval = previous != 0L ? (now - previous) / 1.0e9 : 0.0;
		if (interval <= 0.0 || interval > 0.5) {
			return standby;
		}
		double work = Math.max(0.0, interval - MetalRenderer.lastPresentWaitSeconds());
		workTime = workTime > 0.0 ? workTime * 0.9 + work * 0.1 : work;
		double refresh = 1.0 / MetalRenderer.displayRefreshRate();
		if (!standby) {
			// Engaged, the game's own frame time cannot be told from frame generation's; step back to look again.
			if (now - engagedSince >= RECHECK_NANOS) {
				standby = true;
				crossingSince = 0L;
				workTime = 0.0;
			}
			return standby;
		}
		if (workTime <= refresh * 1.1) {
			crossingSince = 0L;
			return true;
		}
		if (crossingSince == 0L) {
			crossingSince = now;
			return true;
		}
		if (now - crossingSince < ENGAGE_NANOS) {
			return true;
		}
		standby = false;
		engagedSince = now;
		crossingSince = 0L;
		return false;
	}

	/**
	 * For the debug screen, next to Minecraft's fps, which counts only the frames the game renders: how many frames
	 * reach the screen per second, generated ones included, while frame generation is chosen; null otherwise.
	 */
	@Nullable String debugLine() {
		MetalSettings.FrameGeneration engine = activeEngine();
		long count = MetalRenderer.shownFrameCount();
		if (engine == MetalSettings.FrameGeneration.OFF || count < 0L) {
			shownCount = -1L;
			return null;
		}
		long now = System.nanoTime();
		if (shownCount < 0L || count < shownCount) {
			shownCount = count;
			shownSince = now;
		} else if (now - shownSince >= 1_000_000_000L) {
			shownPerSecond = (int) Math.round((count - shownCount) * 1.0e9 / (now - shownSince));
			shownCount = count;
			shownSince = now;
		}
		String name = engine == MetalSettings.FrameGeneration.METALFX ? "MetalFX" : "FSR 3";
		String state = pausedUntil == STOPPED ? " off: too heavy here"
				: standby ? " standing by: the game reaches the refresh rate by itself"
				: pausedUntil != 0L && now < pausedUntil ? " paused: costs more than it gains" : "";
		return String.format(Locale.ROOT, "%d fps on screen, %s frame generation%s", shownPerSecond, name, state);
	}

	private void guard(MetalSettings.FrameGeneration engine, float deltaTime, long now) {
		if (!Boolean.parseBoolean(System.getProperty(GUARD_PROPERTY, "true")) || deltaTime <= 0.0F || deltaTime > 0.5F) {
			return;
		}
		frameInterval = frameInterval > 0.0 ? frameInterval * 0.9 + deltaTime * 0.1 : deltaTime;
		double cost = MetalRenderer.frameGenerationGpuMillis() / 1000.0;
		if (cost <= frameInterval / 2.0) {
			heavySince = 0L;
			if (lightSince == 0L) {
				lightSince = now;
			} else if (now - lightSince >= RECOVERED_NANOS) {
				pausesInARow = 0;
			}
			return;
		}
		lightSince = 0L;
		if (heavySince == 0L) {
			heavySince = now;
			return;
		}
		if (now - heavySince < (cost > frameInterval ? SEVERE_NANOS : HEAVY_NANOS)) {
			return;
		}
		heavySince = 0L;
		boolean stop = ++pausesInARow >= 2;
		pausedUntil = stop ? STOPPED : now + PAUSE_NANOS;
		String millis = String.format(Locale.ROOT, "%.1f", cost * 1000.0);
		String interval = String.format(Locale.ROOT, "%.1f", frameInterval * 1000.0);
		Minecraft client = Minecraft.getInstance();
		if (stop) {
			Mine2Metal.LOGGER.warn("{} frame generation still costs {} ms of GPU time per frame with {} ms between rendered frames; turning it off until it is chosen again",
					engine, millis, interval);
			SystemToast.addOrUpdate(client.gui.toastManager(), PAUSE_TOAST, Component.translatable("mine2metal.toast.frame_generation_stopped"),
					Component.translatable("mine2metal.toast.frame_generation_stopped.detail", millis, interval));
			return;
		}
		Mine2Metal.LOGGER.warn("{} frame generation costs {} ms of GPU time per frame with {} ms between rendered frames, more than it gains; pausing it for 30 s",
				engine, millis, interval);
		if (!pausedBefore) {
			pausedBefore = true;
			SystemToast.add(client.gui.toastManager(), PAUSE_TOAST, Component.translatable("mine2metal.toast.frame_generation_paused"),
					Component.translatable("mine2metal.toast.frame_generation_paused.detail", millis, interval));
		}
	}

	private static float[] parseScale(String value) {
		String[] parts = value.split(",");
		try {
			return new float[] {Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim())};
		} catch (RuntimeException e) {
			Mine2Metal.LOGGER.warn("Ignoring {}={}", MOTION_SCALE_PROPERTY, value.toLowerCase(Locale.ROOT));
			return new float[] {1.0F, 1.0F};
		}
	}
}
