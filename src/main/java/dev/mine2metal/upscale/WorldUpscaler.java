package dev.mine2metal.upscale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.mine2metal.Mine2Metal;
import dev.mine2metal.config.MetalSettings;
import dev.mine2metal.metal.MetalRenderer;
import dev.mine2metal.mixin.RenderTargetAccessor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * Renders the world at a lower resolution and upscales it onto the window-sized main target before the hand, post
 * effects and the GUI, which all stay at full resolution: with MetalFX, or with AMD's FSR 1 or FSR 3.1.
 * <p>
 * The main target object itself stays in place; only its textures are swapped for smaller ones while the world renders.
 * Minecraft keeps references to that object across frames (the sky renderer captures it when it is rebuilt), so
 * swapping the object would leave them drawing into, and later reading from, a target that no longer exists.
 * <p>
 * Temporal upscaling also shifts the world's projection by a different subpixel offset each frame. The scaler combines
 * those samples over time, following each pixel with motion vectors derived from depth and the camera matrices.
 * <p>
 * Driven from {@code GameRendererMixin}: {@link #begin} at the start of renderLevel, {@link #captureProjection} when
 * it uploads the world's projection, {@link #end} before the hand is drawn, and always {@link #abandonFrame} last.
 */
public final class WorldUpscaler {
	private static final Vector4f CLEAR_COLOR = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
	/**
	 * Sign applied to the jitter MetalFX is told about, relative to how far the projection moved the image. A system
	 * property so the game test can compare the alternatives; read again whenever it changes.
	 */
	private static final String JITTER_SIGN_PROPERTY = "mine2metal.temporal.jitterSign";
	/** Measured against native resolution in the game test: the other three signs are visibly softer. */
	private static final String DEFAULT_JITTER_SIGN = "1,1";
	/**
	 * Whether the temporal scaler gets a reactive mask for surfaces that moved by themselves (entities), so their
	 * history does not trail behind them. A system property so the game test can compare with and without.
	 */
	private static final String REACTIVE_MASK_PROPERTY = "mine2metal.temporal.reactiveMask";
	/** RCAS sharpening after FSR 1, from 0 (sharpest) to 2; AMD's sample uses 0.2. */
	private static final float FSR1_SHARPNESS = 0.2F;
	private static String jitterSignText = DEFAULT_JITTER_SIGN;
	private static float[] jitterSign = parseJitterSign(DEFAULT_JITTER_SIGN);

	/** Owns the small textures; while they are lent to the main target it holds the main target's own. */
	private static @Nullable TextureTarget scaled;
	/** The main target while it is rendering with the small textures. */
	private static @Nullable RenderTarget lentTo;
	/** Whether this frame's world was drawn into the small textures. */
	private static boolean scaledFrame;
	/** The upscaling this frame's world pass began with. */
	private static MetalSettings.Upscaling frameMode = MetalSettings.Upscaling.OFF;
	private static boolean warnedFailure;

	/** The camera whose projection is jittered for this frame; null when the frame is not temporal. */
	private static @Nullable CameraRenderState jitteredCamera;
	private static final Matrix4f savedProjection = new Matrix4f();
	/** How far the world was shifted this frame, in pixels of the small target (rows in GL's bottom-up order). */
	private static float jitterX;
	private static float jitterY;
	private static int jitterIndex;
	private static boolean jitteredFrame;
	/** The world's final projection (bobbing and nausea included) with any jitter taken out again. */
	private static final Matrix4f projection = new Matrix4f();
	private static boolean projectionCaptured;

	private static final CameraMotion motion = new CameraMotion();
	/** Near and far plane, vertical field of view in radians and milliseconds since the previous frame, for FSR 3.1. */
	private static final float[] cameraValues = new float[4];
	private static long previousUpscaleNanos;

	private WorldUpscaler() {
	}

	/** The upscaling that will run for the next frame, after checking what this Mac supports. */
	public static MetalSettings.Upscaling activeMode() {
		MetalSettings settings = MetalSettings.get();
		return switch (settings.upscaling) {
			case OFF -> MetalSettings.Upscaling.OFF;
			case SPATIAL -> settings.renderScale < MetalSettings.MAX_RENDER_SCALE && MetalRenderer.supportsSpatialUpscaling()
					? MetalSettings.Upscaling.SPATIAL : MetalSettings.Upscaling.OFF;
			// At 100% the temporal scaler still runs, as anti-aliasing, when the device allows a ratio of 1.
			case TEMPORAL -> MetalRenderer.supportsTemporalRatio(MetalSettings.MAX_RENDER_SCALE / (float) settings.renderScale)
					? MetalSettings.Upscaling.TEMPORAL : MetalSettings.Upscaling.OFF;
			case FSR1 -> settings.renderScale < MetalSettings.MAX_RENDER_SCALE && MetalRenderer.supportsFsrUpscaling()
					? MetalSettings.Upscaling.FSR1 : MetalSettings.Upscaling.OFF;
			// FSR 3.1 at 100% is AMD's native anti-aliasing mode.
			case FSR3 -> MetalRenderer.supportsFsrUpscaling() ? MetalSettings.Upscaling.FSR3 : MetalSettings.Upscaling.OFF;
		};
	}

	private static boolean temporal(MetalSettings.Upscaling mode) {
		return mode == MetalSettings.Upscaling.TEMPORAL || mode == MetalSettings.Upscaling.FSR3;
	}

	/** At the start of the world pass: gives main the smaller textures and jitters the camera if upscaling is on. */
	public static void begin(RenderTarget main, CameraRenderState camera) {
		if (lentTo != null) {
			throw new IllegalStateException("The previous world pass did not return the main render target's textures");
		}
		projectionCaptured = false;
		scaledFrame = false;
		jitteredFrame = false;
		MetalSettings.Upscaling mode = activeMode();
		frameMode = mode;
		if (mode == MetalSettings.Upscaling.OFF) {
			release();
			motion.reset();
			return;
		}

		float scale = MetalSettings.get().renderScale / 100.0F;
		int width = Math.max(1, Math.round(main.width * scale));
		int height = Math.max(1, Math.round(main.height * scale));
		TextureTarget target = scaled;
		if (target == null || target.getColorTexture() == null) {
			RenderTargetAccessor formats = (RenderTargetAccessor) main;
			target = new TextureTarget("mine2metal scaled world", width, height, formats.mine2metal$colorFormat(), formats.mine2metal$depthFormat());
			scaled = target;
		} else if (target.width != width || target.height != height) {
			target.resize(width, height);
		}

		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), CLEAR_COLOR, target.getDepthTexture(), 1.0);
		swapTextures(main, target);
		lentTo = main;
		scaledFrame = true;

		if (temporal(mode)) {
			jitter(camera, width, height, main.width / (float) width);
		} else {
			motion.reset();
		}
	}

	/**
	 * Shifts the projection by the next point of a Halton (2, 3) sequence. Apple suggests at least 8 phases times the
	 * square of the upscale ratio, so each output pixel is sampled at several positions; so does AMD for FSR.
	 */
	private static void jitter(CameraRenderState camera, int width, int height, float ratio) {
		int phases = Math.max(8, (int) Math.ceil(8.0F * ratio * ratio));
		jitterIndex = jitterIndex % phases + 1;
		jitterX = halton(jitterIndex, 2) - 0.5F;
		jitterY = halton(jitterIndex, 3) - 0.5F;
		savedProjection.set(camera.projectionMatrix);
		// In clip space, so it moves the image by the same number of pixels whatever the depth.
		camera.projectionMatrix.translateLocal(2.0F * jitterX / width, 2.0F * jitterY / height, 0.0F);
		jitteredCamera = camera;
		jitteredFrame = true;
	}

	/** The projection renderLevel uploads for the world; recorded unjittered for the motion vectors. */
	public static void captureProjection(Matrix4f uploaded) {
		projection.set(uploaded);
		TextureTarget target = scaled;
		if (jitteredCamera != null && lentTo != null && target != null) {
			projection.translateLocal(-2.0F * jitterX / target.width, -2.0F * jitterY / target.height, 0.0F);
		}
		projectionCaptured = true;
	}

	/** This frame's unjittered world projection, or null if renderLevel did not upload one. */
	public static @Nullable Matrix4f worldProjection() {
		return projectionCaptured ? projection : null;
	}

	/** The depth this frame's world was drawn with: the small target's while upscaling, otherwise main's. */
	public static GpuTexture worldDepth(RenderTarget main) {
		TextureTarget target = scaled;
		GpuTexture depth = scaledFrame && lentTo == null && target != null ? target.getDepthTexture() : main.getDepthTexture();
		return Objects.requireNonNull(depth, "depth texture");
	}

	/** How far this frame's world was shifted, in pixels of {@link #worldDepth}, as told to the scaler; 0 unless temporal. */
	public static float worldJitterX() {
		return jitteredFrame ? jitterSign[0] * jitterX : 0.0F;
	}

	public static float worldJitterY() {
		return jitteredFrame ? jitterSign[1] * jitterY : 0.0F;
	}

	/** Before the hand is drawn: takes the textures back and upscales the world onto main. */
	public static void end() {
		MetalSettings.Upscaling mode = frameMode;
		RenderTarget main = lentTo;
		TextureTarget target = scaled;
		if (main == null || target == null) {
			return;
		}
		swapTextures(main, target);
		lentTo = null;
		CameraRenderState camera = jitteredCamera;
		restoreProjection();

		if (camera != null && projectionCaptured && upscaleTemporal(mode == MetalSettings.Upscaling.FSR3, camera, target, main)) {
			return;
		}
		motion.reset();
		if (camera == null) {
			boolean upscaled = mode == MetalSettings.Upscaling.FSR1
					? MetalRenderer.upscaleFsr1(target.getColorTexture(), main.getColorTexture(), FSR1_SHARPNESS)
					: MetalRenderer.upscaleSpatial(target.getColorTexture(), main.getColorTexture());
			if (upscaled) {
				return;
			}
		}
		if (!warnedFailure) {
			warnedFailure = true;
			Mine2Metal.LOGGER.warn("{} could not upscale {}x{} to {}x{}; falling back to a plain blit", mode, target.width, target.height, main.width,
					main.height);
		}
		target.blitAndBlendToTexture(main.getColorTextureView(), main.getDepthTextureView());
	}

	private static boolean upscaleTemporal(boolean fsr, CameraRenderState camera, TextureTarget target, RenderTarget main) {
		Matrix4f viewProjection = new Matrix4f(projection).mul(camera.viewRotationMatrix);
		boolean reset = motion.begin(viewProjection, camera.pos, target.width, target.height);
		long now = System.nanoTime();
		CameraMotion.planes(projection, camera.depthFar, cameraValues);
		cameraValues[2] = (float) (2.0 * Math.atan(1.0 / projection.m11()));
		cameraValues[3] = previousUpscaleNanos == 0L ? 16.7F : Math.min((now - previousUpscaleNanos) / 1.0e6F, 1000.0F);
		previousUpscaleNanos = now;

		String signText = System.getProperty(JITTER_SIGN_PROPERTY, DEFAULT_JITTER_SIGN);
		if (!signText.equals(jitterSignText)) {
			jitterSignText = signText;
			jitterSign = parseJitterSign(signText);
			reset = true;
		}
		boolean upscaled = MetalRenderer.upscaleTemporal(fsr, target.getColorTexture(), target.getDepthTexture(), main.getColorTexture(), motion.matrices,
				cameraValues, jitterSign[0] * jitterX, jitterSign[1] * jitterY, reset, Boolean.parseBoolean(System.getProperty(REACTIVE_MASK_PROPERTY, "true")));
		motion.finish(upscaled);
		return upscaled;
	}

	/** Makes sure main has its own textures and the camera its own projection back, whether or not the world pass finished. */
	public static void abandonFrame() {
		RenderTarget main = lentTo;
		TextureTarget target = scaled;
		if (main != null && target != null) {
			swapTextures(main, target);
			scaledFrame = false;
			motion.reset();
		}
		lentTo = null;
		restoreProjection();
	}

	private static void restoreProjection() {
		CameraRenderState camera = jitteredCamera;
		if (camera != null) {
			camera.projectionMatrix.set(savedProjection);
			jitteredCamera = null;
		}
	}

	/** Exchanges size and textures between two targets with the same formats. */
	private static void swapTextures(RenderTarget a, RenderTarget b) {
		int width = a.width;
		int height = a.height;
		var color = a.getColorTexture();
		var colorView = a.getColorTextureView();
		var depth = a.getDepthTexture();
		var depthView = a.getDepthTextureView();

		a.width = b.width;
		a.height = b.height;
		RenderTargetAccessor first = (RenderTargetAccessor) a;
		first.mine2metal$setColorTexture(b.getColorTexture());
		first.mine2metal$setColorTextureView(b.getColorTextureView());
		first.mine2metal$setDepthTexture(b.getDepthTexture());
		first.mine2metal$setDepthTextureView(b.getDepthTextureView());

		b.width = width;
		b.height = height;
		RenderTargetAccessor second = (RenderTargetAccessor) b;
		second.mine2metal$setColorTexture(color);
		second.mine2metal$setColorTextureView(colorView);
		second.mine2metal$setDepthTexture(depth);
		second.mine2metal$setDepthTextureView(depthView);
	}

	private static void release() {
		if (scaled != null) {
			scaled.destroyBuffers();
			scaled = null;
		}
	}

	private static float halton(int index, int base) {
		float result = 0.0F;
		float fraction = 1.0F;
		for (int i = index; i > 0; i /= base) {
			fraction /= base;
			result += fraction * (i % base);
		}
		return result;
	}

	private static float[] parseJitterSign(String value) {
		String[] parts = value.split(",");
		try {
			return new float[] {Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim())};
		} catch (RuntimeException e) {
			Mine2Metal.LOGGER.warn("Ignoring {}={}", JITTER_SIGN_PROPERTY, value.toLowerCase(Locale.ROOT));
			return new float[] {1.0F, 1.0F};
		}
	}
}
