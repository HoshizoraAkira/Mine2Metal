package dev.mine2metal.upscale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.mine2metal.Mine2Metal;
import dev.mine2metal.config.MetalSettings;
import dev.mine2metal.metal.MetalRenderer;
import dev.mine2metal.mixin.RenderTargetAccessor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Renders the world at a lower resolution and upscales it with MetalFX onto the window-sized main target before the
 * hand, post effects and the GUI, which all stay at full resolution.
 * <p>
 * The main target object itself stays in place; only its textures are swapped for smaller ones while the world renders.
 * Minecraft keeps references to that object across frames (the sky renderer captures it when it is rebuilt), so
 * swapping the object would leave them drawing into, and later reading from, a target that no longer exists.
 * <p>
 * Temporal upscaling also shifts the world's projection by a different subpixel offset each frame. MetalFX combines
 * those samples over time, following each pixel with motion vectors derived from depth and the camera matrices.
 * <p>
 * Driven from {@code GameRendererMixin}: {@link #begin} at the start of renderLevel, {@link #captureProjection} when
 * it uploads the world's projection, {@link #end} before the hand is drawn, and always {@link #abandonFrame} last.
 */
public final class WorldUpscaler {
	private static final Vector4f CLEAR_COLOR = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
	/** A camera that moves further than this between frames has teleported; its history is useless. */
	private static final double TELEPORT_DISTANCE = 16.0;
	/**
	 * Sign applied to the jitter MetalFX is told about, relative to how far the projection moved the image. A system
	 * property so the game test can compare the alternatives; read again whenever it changes.
	 */
	private static final String JITTER_SIGN_PROPERTY = "mine2metal.temporal.jitterSign";
	/** Measured against native resolution in the game test: the other three signs are visibly softer. */
	private static final String DEFAULT_JITTER_SIGN = "1,1";
	private static String jitterSignText = DEFAULT_JITTER_SIGN;
	private static float[] jitterSign = parseJitterSign(DEFAULT_JITTER_SIGN);

	/** Owns the small textures; while they are lent to the main target it holds the main target's own. */
	private static @Nullable TextureTarget scaled;
	/** The main target while it is rendering with the small textures. */
	private static @Nullable RenderTarget lentTo;
	private static boolean warnedFailure;

	/** The camera whose projection is jittered for this frame; null when the frame is not temporal. */
	private static @Nullable CameraRenderState jitteredCamera;
	private static final Matrix4f savedProjection = new Matrix4f();
	/** How far the world was shifted this frame, in pixels of the small target (rows in GL's bottom-up order). */
	private static float jitterX;
	private static float jitterY;
	private static int jitterIndex;
	/** The world's final projection (bobbing and nausea included) with the jitter taken out again. */
	private static final Matrix4f projection = new Matrix4f();
	private static boolean projectionCaptured;

	private static boolean hasHistory;
	private static final Matrix4f previousViewProjection = new Matrix4f();
	private static @Nullable Vec3 previousCameraPos;
	private static int previousWidth;
	private static int previousHeight;

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
		};
	}

	/** At the start of the world pass: gives main the smaller textures and jitters the camera if upscaling is on. */
	public static void begin(RenderTarget main, CameraRenderState camera) {
		if (lentTo != null) {
			throw new IllegalStateException("The previous world pass did not return the main render target's textures");
		}
		MetalSettings.Upscaling mode = activeMode();
		if (mode == MetalSettings.Upscaling.OFF) {
			release();
			hasHistory = false;
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

		if (mode == MetalSettings.Upscaling.TEMPORAL) {
			jitter(camera, width, height, main.width / (float) width);
		} else {
			hasHistory = false;
		}
	}

	/**
	 * Shifts the projection by the next point of a Halton (2, 3) sequence. Apple suggests at least 8 phases times the
	 * square of the upscale ratio, so each output pixel is sampled at several positions.
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
		projectionCaptured = false;
	}

	/** The projection renderLevel uploads for the world; recorded unjittered for the motion vectors. */
	public static void captureProjection(Matrix4f uploaded) {
		TextureTarget target = scaled;
		if (jitteredCamera == null || lentTo == null || target == null) {
			return;
		}
		projection.set(uploaded).translateLocal(-2.0F * jitterX / target.width, -2.0F * jitterY / target.height, 0.0F);
		projectionCaptured = true;
	}

	/** Before the hand is drawn: takes the textures back and upscales the world onto main. */
	public static void end() {
		RenderTarget main = lentTo;
		TextureTarget target = scaled;
		if (main == null || target == null) {
			return;
		}
		swapTextures(main, target);
		lentTo = null;
		CameraRenderState camera = jitteredCamera;
		restoreProjection();

		if (camera != null && projectionCaptured && upscaleTemporal(camera, target, main)) {
			return;
		}
		hasHistory = false;
		if (camera == null && MetalRenderer.upscaleSpatial(target.getColorTexture(), main.getColorTexture())) {
			return;
		}
		if (!warnedFailure) {
			warnedFailure = true;
			Mine2Metal.LOGGER.warn("MetalFX could not upscale {}x{} to {}x{}; falling back to a plain blit", target.width, target.height, main.width,
					main.height);
		}
		target.blitAndBlendToTexture(main.getColorTextureView(), main.getDepthTextureView());
	}

	private static boolean upscaleTemporal(CameraRenderState camera, TextureTarget target, RenderTarget main) {
		Matrix4f viewProjection = new Matrix4f(projection).mul(camera.viewRotationMatrix);
		Vec3 cameraPos = camera.pos;
		boolean reset = !hasHistory || previousCameraPos == null || target.width != previousWidth || target.height != previousHeight
				|| cameraPos.distanceToSqr(previousCameraPos) > TELEPORT_DISTANCE * TELEPORT_DISTANCE;
		Matrix4f previous = reset ? viewProjection : previousViewProjection;
		Vec3 delta = reset ? Vec3.ZERO : cameraPos.subtract(previousCameraPos);

		float[] matrices = new float[36];
		new Matrix4f(viewProjection).invert().get(matrices, 0);
		previous.get(matrices, 16);
		matrices[32] = (float) delta.x;
		matrices[33] = (float) delta.y;
		matrices[34] = (float) delta.z;

		String signText = System.getProperty(JITTER_SIGN_PROPERTY, DEFAULT_JITTER_SIGN);
		if (!signText.equals(jitterSignText)) {
			jitterSignText = signText;
			jitterSign = parseJitterSign(signText);
			reset = true;
		}
		boolean upscaled = MetalRenderer.upscaleTemporal(target.getColorTexture(), target.getDepthTexture(), main.getColorTexture(), matrices,
				jitterSign[0] * jitterX, jitterSign[1] * jitterY, reset);
		previousViewProjection.set(viewProjection);
		previousCameraPos = cameraPos;
		previousWidth = target.width;
		previousHeight = target.height;
		hasHistory = upscaled;
		return upscaled;
	}

	/** Makes sure main has its own textures and the camera its own projection back, whether or not the world pass finished. */
	public static void abandonFrame() {
		RenderTarget main = lentTo;
		TextureTarget target = scaled;
		if (main != null && target != null) {
			swapTextures(main, target);
			hasHistory = false;
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
