package dev.mine2metal.frame;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.mine2metal.Mine2Metal;
import dev.mine2metal.config.MetalSettings;
import dev.mine2metal.metal.MetalRenderer;
import dev.mine2metal.mixin.RenderTargetAccessor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

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
 * Driven by {@link WorldPass}; what it does to one frame is recorded in that frame's {@link WorldFrame}.
 */
final class WorldUpscaler {
	private static final Vector4f CLEAR_COLOR = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
	/**
	 * Sign applied to the jitter the scaler is told about, relative to how far the projection moved the image. A system
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

	private String jitterSignText = DEFAULT_JITTER_SIGN;
	private float[] jitterSign = parseJitterSign(DEFAULT_JITTER_SIGN);
	/** Owns the small textures; while they are lent to the main target it holds the main target's own. */
	private @Nullable TextureTarget scaled;
	private int jitterIndex;
	private final CameraMotion motion = new CameraMotion();
	/** Near and far plane, vertical field of view in radians and milliseconds since the previous frame, for FSR 3.1. */
	private final float[] cameraValues = new float[4];
	private long previousUpscaleNanos;
	private boolean warnedFailure;

	/** The upscaling that will run for the next frame, after checking what this Mac supports. */
	static MetalSettings.Upscaling activeMode() {
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
	void begin(WorldFrame frame) {
		if (frame.upscaling == MetalSettings.Upscaling.OFF) {
			this.release();
			this.motion.reset();
			return;
		}

		RenderTarget main = frame.main;
		float scale = MetalSettings.get().renderScale / 100.0F;
		int width = Math.max(1, Math.round(main.width * scale));
		int height = Math.max(1, Math.round(main.height * scale));
		TextureTarget target = this.scaled;
		if (target == null || target.getColorTexture() == null) {
			RenderTargetAccessor formats = (RenderTargetAccessor) main;
			target = new TextureTarget("mine2metal scaled world", width, height, formats.mine2metal$colorFormat(), formats.mine2metal$depthFormat());
			this.scaled = target;
		} else if (target.width != width || target.height != height) {
			target.resize(width, height);
		}

		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), CLEAR_COLOR, target.getDepthTexture(), 1.0);
		swapTextures(main, target);
		frame.scaled = target;
		frame.lent = true;

		if (temporal(frame.upscaling)) {
			this.jitter(frame, width, height, main.width / (float) width);
		} else {
			this.motion.reset();
		}
	}

	/**
	 * Shifts the projection by the next point of a Halton (2, 3) sequence. Apple suggests at least 8 phases times the
	 * square of the upscale ratio, so each output pixel is sampled at several positions; so does AMD for FSR.
	 */
	private void jitter(WorldFrame frame, int width, int height, float ratio) {
		String signText = System.getProperty(JITTER_SIGN_PROPERTY, DEFAULT_JITTER_SIGN);
		if (!signText.equals(this.jitterSignText)) {
			this.jitterSignText = signText;
			this.jitterSign = parseJitterSign(signText);
			frame.jitterSignChanged = true;
		}
		int phases = Math.max(8, (int) Math.ceil(8.0F * ratio * ratio));
		this.jitterIndex = this.jitterIndex % phases + 1;
		frame.jitterX = halton(this.jitterIndex, 2) - 0.5F;
		frame.jitterY = halton(this.jitterIndex, 3) - 0.5F;
		frame.jitterSignX = this.jitterSign[0];
		frame.jitterSignY = this.jitterSign[1];
		CameraRenderState camera = frame.camera;
		frame.savedProjection.set(camera.projectionMatrix);
		// In clip space, so it moves the image by the same number of pixels whatever the depth.
		camera.projectionMatrix.translateLocal(2.0F * frame.jitterX / width, 2.0F * frame.jitterY / height, 0.0F);
		frame.projectionJittered = true;
		frame.jittered = true;
	}

	/** The projection renderLevel uploads for the world; recorded unjittered for the motion vectors. */
	void captureProjection(WorldFrame frame, Matrix4f uploaded) {
		frame.projection.set(uploaded);
		TextureTarget target = frame.scaled;
		if (frame.projectionJittered && frame.lent && target != null) {
			frame.projection.translateLocal(-2.0F * frame.jitterX / target.width, -2.0F * frame.jitterY / target.height, 0.0F);
		}
		frame.projectionCaptured = true;
	}

	/** Before the hand is drawn: takes the textures back and upscales the world onto main. */
	void end(WorldFrame frame) {
		RenderTarget main = frame.main;
		TextureTarget target = frame.scaled;
		if (!frame.lent || target == null) {
			return;
		}
		swapTextures(main, target);
		frame.lent = false;
		boolean jittered = frame.projectionJittered;
		restoreProjection(frame);

		if (jittered && frame.projectionCaptured && this.upscaleTemporal(frame, target)) {
			return;
		}
		this.motion.reset();
		if (!jittered) {
			boolean upscaled = frame.upscaling == MetalSettings.Upscaling.FSR1
					? MetalRenderer.upscaleFsr1(target.getColorTexture(), main.getColorTexture(), FSR1_SHARPNESS)
					: MetalRenderer.upscaleSpatial(target.getColorTexture(), main.getColorTexture());
			if (upscaled) {
				return;
			}
		}
		if (!this.warnedFailure) {
			this.warnedFailure = true;
			Mine2Metal.LOGGER.warn("{} could not upscale {}x{} to {}x{}; falling back to a plain blit", frame.upscaling, target.width, target.height,
					main.width, main.height);
		}
		target.blitAndBlendToTexture(main.getColorTextureView(), main.getDepthTextureView());
	}

	private boolean upscaleTemporal(WorldFrame frame, TextureTarget target) {
		CameraRenderState camera = frame.camera;
		Matrix4f viewProjection = new Matrix4f(frame.projection).mul(camera.viewRotationMatrix);
		boolean reset = this.motion.begin(viewProjection, camera.pos, target.width, target.height) || frame.jitterSignChanged;
		long now = System.nanoTime();
		CameraMotion.planes(frame.projection, camera.depthFar, this.cameraValues);
		this.cameraValues[2] = (float) (2.0 * Math.atan(1.0 / frame.projection.m11()));
		this.cameraValues[3] = this.previousUpscaleNanos == 0L ? 16.7F : Math.min((now - this.previousUpscaleNanos) / 1.0e6F, 1000.0F);
		this.previousUpscaleNanos = now;

		boolean upscaled = MetalRenderer.upscaleTemporal(frame.upscaling == MetalSettings.Upscaling.FSR3, target.getColorTexture(),
				target.getDepthTexture(), frame.main.getColorTexture(), this.motion.matrices, this.cameraValues, frame.toldJitterX(), frame.toldJitterY(),
				reset, Boolean.parseBoolean(System.getProperty(REACTIVE_MASK_PROPERTY, "true")));
		this.motion.finish(upscaled);
		return upscaled;
	}

	/** Makes sure main has its own textures and the camera its own projection back, whether or not the world pass finished. */
	void abandon(WorldFrame frame) {
		TextureTarget target = frame.scaled;
		if (frame.lent && target != null) {
			swapTextures(frame.main, target);
			frame.scaled = null;
			this.motion.reset();
		}
		frame.lent = false;
		restoreProjection(frame);
	}

	private static void restoreProjection(WorldFrame frame) {
		if (frame.projectionJittered) {
			frame.camera.projectionMatrix.set(frame.savedProjection);
			frame.projectionJittered = false;
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

	private void release() {
		if (this.scaled != null) {
			this.scaled.destroyBuffers();
			this.scaled = null;
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
