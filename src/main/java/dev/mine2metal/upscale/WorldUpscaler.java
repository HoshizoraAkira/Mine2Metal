package dev.mine2metal.upscale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.mine2metal.Mine2Metal;
import dev.mine2metal.config.MetalSettings;
import dev.mine2metal.metal.MetalRenderer;
import dev.mine2metal.mixin.RenderTargetAccessor;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Renders the world (and the hand) into a smaller target and upscales it onto the window-sized main target with
 * MetalFX before post effects and the GUI, which stay at full resolution. Driven from {@code GameRendererMixin}.
 */
public final class WorldUpscaler {
	private static final Vector4f CLEAR_COLOR = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);

	private static @Nullable TextureTarget scaled;
	private static @Nullable RenderTarget nativeTarget;
	private static boolean warnedFailure;

	private WorldUpscaler() {
	}

	/** True when spatial upscaling is selected below 100% and the Metal backend can run it. */
	public static boolean isActive() {
		MetalSettings settings = MetalSettings.get();
		return settings.upscaling == MetalSettings.Upscaling.SPATIAL && settings.renderScale < MetalSettings.MAX_RENDER_SCALE
				&& MetalRenderer.supportsSpatialUpscaling();
	}

	/** At the start of the world pass: returns the target to render into instead of main. */
	public static RenderTarget begin(RenderTarget main) {
		if (!isActive()) {
			release();
			return main;
		}

		float scale = MetalSettings.get().renderScale / 100.0F;
		int width = Math.max(1, Math.round(main.width * scale));
		int height = Math.max(1, Math.round(main.height * scale));
		TextureTarget target = scaled;
		if (target == null) {
			RenderTargetAccessor formats = (RenderTargetAccessor) main;
			target = new TextureTarget("mine2metal scaled world", width, height, formats.mine2metal$colorFormat(), formats.mine2metal$depthFormat());
			scaled = target;
		} else if (target.width != width || target.height != height) {
			target.resize(width, height);
		}

		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), CLEAR_COLOR, target.getDepthTexture(), 1.0);
		nativeTarget = main;
		return target;
	}

	/** At the end of the world pass: upscales onto the main target and returns it. */
	public static RenderTarget end(RenderTarget current) {
		RenderTarget main = nativeTarget;
		TextureTarget target = scaled;
		if (main == null || target == null) {
			return current;
		}
		nativeTarget = null;

		if (!MetalRenderer.upscaleSpatial(target.getColorTexture(), main.getColorTexture())) {
			if (!warnedFailure) {
				warnedFailure = true;
				Mine2Metal.LOGGER.warn("MetalFX could not upscale {}x{} to {}x{}; falling back to a plain blit", target.width, target.height, main.width,
						main.height);
			}
			target.blitAndBlendToTexture(main.getColorTextureView(), main.getDepthTextureView());
		}
		return main;
	}

	private static void release() {
		if (scaled != null) {
			scaled.destroyBuffers();
			scaled = null;
		}
	}
}
