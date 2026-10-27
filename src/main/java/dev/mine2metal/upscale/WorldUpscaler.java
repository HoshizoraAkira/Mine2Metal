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
 * Renders the world (and the hand) at a lower resolution and upscales it onto the window-sized main target with
 * MetalFX before post effects and the GUI, which stay at full resolution.
 * <p>
 * The main target object itself stays in place; only its textures are swapped for smaller ones while renderLevel
 * runs. Minecraft keeps references to that object across frames (the sky renderer captures it when it is rebuilt),
 * so swapping the object would leave them drawing into, and later reading from, a target that no longer exists.
 * Driven from {@code GameRendererMixin}: {@link #begin}, then {@link #end} if the world pass finished, then always
 * {@link #abandonFrame}.
 */
public final class WorldUpscaler {
	private static final Vector4f CLEAR_COLOR = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);

	/** Owns the small textures; while they are lent to the main target it holds the main target's own. */
	private static @Nullable TextureTarget scaled;
	/** The main target while it is rendering with the small textures. */
	private static @Nullable RenderTarget lentTo;
	private static boolean warnedFailure;

	private WorldUpscaler() {
	}

	/** True when spatial upscaling is selected below 100% and the Metal backend can run it. */
	public static boolean isActive() {
		MetalSettings settings = MetalSettings.get();
		return settings.upscaling == MetalSettings.Upscaling.SPATIAL && settings.renderScale < MetalSettings.MAX_RENDER_SCALE
				&& MetalRenderer.supportsSpatialUpscaling();
	}

	/** At the start of the world pass: gives main the smaller textures if upscaling is on. */
	public static void begin(RenderTarget main) {
		if (lentTo != null) {
			throw new IllegalStateException("The previous world pass did not return the main render target's textures");
		}
		if (!isActive()) {
			release();
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
	}

	/** At the end of the world pass: takes the textures back and upscales the world onto main. */
	public static void end() {
		RenderTarget main = lentTo;
		TextureTarget target = scaled;
		if (main == null || target == null) {
			return;
		}
		swapTextures(main, target);
		lentTo = null;

		if (!MetalRenderer.upscaleSpatial(target.getColorTexture(), main.getColorTexture())) {
			if (!warnedFailure) {
				warnedFailure = true;
				Mine2Metal.LOGGER.warn("MetalFX could not upscale {}x{} to {}x{}; falling back to a plain blit", target.width, target.height, main.width,
						main.height);
			}
			target.blitAndBlendToTexture(main.getColorTextureView(), main.getDepthTextureView());
		}
	}

	/** Makes sure main has its own textures back, whether or not the world pass finished. */
	public static void abandonFrame() {
		RenderTarget main = lentTo;
		TextureTarget target = scaled;
		if (main != null && target != null) {
			swapTextures(main, target);
		}
		lentTo = null;
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
}
