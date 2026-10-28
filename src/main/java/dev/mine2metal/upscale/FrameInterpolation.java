package dev.mine2metal.upscale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.mine2metal.Mine2Metal;
import dev.mine2metal.config.MetalSettings;
import dev.mine2metal.metal.MetalRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

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
 * Driven from {@code GameRendererMixin}: {@link #beginFrame} at the start of renderLevel and {@link #capture} once the
 * world is done.
 */
public final class FrameInterpolation {
	/**
	 * Scale MetalFX applies to the motion vectors, which are in pixels of the world's depth. A system property so the
	 * game test can compare the alternatives; read again whenever it changes.
	 */
	private static final String MOTION_SCALE_PROPERTY = "mine2metal.interpolation.motionScale";
	private static final String DEFAULT_MOTION_SCALE = "1,1";
	/** Minecraft's near plane, for a projection whose planes cannot be read back. */
	private static final float NEAR_PLANE = 0.05F;

	private static final CameraMotion motion = new CameraMotion();
	private static final SystemToast.SystemToastId PAUSE_TOAST = new SystemToast.SystemToastId(10_000L);
	private static String motionScaleText = DEFAULT_MOTION_SCALE;
	private static float[] motionScale = parseScale(DEFAULT_MOTION_SCALE);
	private static long previousCaptureNanos;
	private static boolean captured;
	/** True while the native side holds textures for it. */
	private static boolean active;
	private static boolean warnedFailure;

	/**
	 * The guard: frame generation pays off only while it costs less than half the time between rendered frames (two
	 * images in T beat one in T - cost exactly when T > 2 cost). When it costs more for a few seconds in a row, it pauses
	 * for a while and then tries again. -Dmine2metal.frameGenerationGuard=false turns this off.
	 */
	private static final String GUARD_PROPERTY = "mine2metal.frameGenerationGuard";
	private static final long HEAVY_NANOS = 3_000_000_000L;
	private static final long PAUSE_NANOS = 30_000_000_000L;
	/** Smoothed seconds between captures. */
	private static double frameInterval;
	/** When frame generation first cost too much in the current run of such frames; 0 if it does not now. */
	private static long heavySince;
	/** While paused, until this System.nanoTime(). */
	private static long pausedUntil;
	private static boolean pausedBefore;
	private static MetalSettings.FrameGeneration guardedEngine = MetalSettings.FrameGeneration.OFF;

	private FrameInterpolation() {
	}

	/** The engine that will generate frames next, after checking what this Mac supports. */
	public static MetalSettings.FrameGeneration activeEngine() {
		return switch (MetalSettings.get().frameGeneration) {
			case OFF -> MetalSettings.FrameGeneration.OFF;
			case METALFX -> MetalRenderer.supportsFrameInterpolation() ? MetalSettings.FrameGeneration.METALFX : MetalSettings.FrameGeneration.OFF;
			case FSR3 -> MetalRenderer.supportsFsrFrameGeneration() ? MetalSettings.FrameGeneration.FSR3 : MetalSettings.FrameGeneration.OFF;
		};
	}

	public static boolean enabled() {
		return activeEngine() != MetalSettings.FrameGeneration.OFF;
	}

	public static void beginFrame() {
		captured = false;
	}

	/** Once the world is drawn into main (and upscaled): captures it with its depth and camera. At most once a frame. */
	public static void capture(RenderTarget main, CameraRenderState camera) {
		if (captured) {
			return;
		}
		captured = true;
		MetalSettings.FrameGeneration engine = activeEngine();
		if (engine != guardedEngine) {
			// A new choice starts without a pause.
			guardedEngine = engine;
			pausedUntil = 0L;
			heavySince = 0L;
		}
		if (engine == MetalSettings.FrameGeneration.OFF) {
			if (active) {
				MetalRenderer.releaseFrameInterpolation();
				active = false;
			}
			motion.reset();
			return;
		}
		if (pausedUntil != 0L && System.nanoTime() < pausedUntil) {
			// Paused by the guard: frames are shown as rendered, and the engine keeps its state for the retry.
			motion.reset();
			return;
		}
		pausedUntil = 0L;
		Matrix4f projection = WorldUpscaler.worldProjection();
		if (projection == null) {
			motion.reset();
			return;
		}

		GpuTexture depth = WorldUpscaler.worldDepth(main);
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
		guard(engine, deltaTime, now);

		float[] values = new float[9];
		planes(projection, camera, values);
		values[2] = (float) Math.toDegrees(2.0 * Math.atan(1.0 / projection.m11()));
		values[3] = projection.m11() / projection.m00();
		values[4] = WorldUpscaler.worldJitterX();
		values[5] = WorldUpscaler.worldJitterY();
		values[6] = deltaTime;
		values[7] = motionScale[0];
		values[8] = motionScale[1];

		GpuTexture color = Objects.requireNonNull(main.getColorTexture(), "color texture");
		boolean done = MetalRenderer.captureForInterpolation(engine == MetalSettings.FrameGeneration.FSR3, color, depth, motion.matrices, values, reset);
		if (!done && !warnedFailure) {
			warnedFailure = true;
			Mine2Metal.LOGGER.warn("{} could not generate frames for {}x{} with {}x{} depth; showing frames as rendered", engine, color.getWidth(0),
					color.getHeight(0), depth.getWidth(0), depth.getHeight(0));
		}
		active |= done;
		motion.finish(done);
	}

	private static void guard(MetalSettings.FrameGeneration engine, float deltaTime, long now) {
		if (!Boolean.parseBoolean(System.getProperty(GUARD_PROPERTY, "true")) || deltaTime <= 0.0F || deltaTime > 0.5F) {
			return;
		}
		frameInterval = frameInterval > 0.0 ? frameInterval * 0.9 + deltaTime * 0.1 : deltaTime;
		double cost = MetalRenderer.frameGenerationGpuMillis() / 1000.0;
		if (cost <= frameInterval / 2.0) {
			heavySince = 0L;
			return;
		}
		if (heavySince == 0L) {
			heavySince = now;
			return;
		}
		if (now - heavySince < HEAVY_NANOS) {
			return;
		}
		heavySince = 0L;
		pausedUntil = now + PAUSE_NANOS;
		String millis = String.format(Locale.ROOT, "%.1f", cost * 1000.0);
		String interval = String.format(Locale.ROOT, "%.1f", frameInterval * 1000.0);
		Mine2Metal.LOGGER.warn("{} frame generation costs {} ms of GPU time per frame with {} ms between rendered frames, more than it gains; pausing it for 30 s",
				engine, millis, interval);
		if (!pausedBefore) {
			pausedBefore = true;
			Minecraft client = Minecraft.getInstance();
			SystemToast.add(client.gui.toastManager(), PAUSE_TOAST, Component.translatable("mine2metal.toast.frame_generation_paused"),
					Component.translatable("mine2metal.toast.frame_generation_paused.detail", millis, interval));
		}
	}

	/**
	 * Near and far plane into values[0..1]. The Metal device reports zero-to-one depth, so the projection has
	 * m22 = f / (n - f) and m32 = n f / (n - f).
	 */
	private static void planes(Matrix4f projection, CameraRenderState camera, float[] values) {
		float near = projection.m32() / projection.m22();
		float far = projection.m32() / (projection.m22() + 1.0F);
		if (!(near > 0.0F && far > near && Float.isFinite(far))) {
			near = NEAR_PLANE;
			far = camera.depthFar;
		}
		values[0] = near;
		values[1] = far;
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
