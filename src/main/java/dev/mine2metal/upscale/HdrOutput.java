package dev.mine2metal.upscale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.mine2metal.config.MetalSettings;
import dev.mine2metal.metal.MetalRenderer;

/**
 * EDR output: the world's highlights shown brighter than SDR white. The world is captured once it is drawn, before the
 * hand and the GUI, so the present can tell them apart and keep them at SDR brightness (see {@code hdr.m}).
 * <p>
 * Driven from {@code GameRendererMixin}: {@link #beginFrame} at the start of renderLevel and {@link #capture} once the
 * world is done.
 */
public final class HdrOutput {
	private static boolean captured;

	private HdrOutput() {
	}

	public static void beginFrame() {
		captured = false;
	}

	/** At most once a frame, with EDR on. */
	public static void capture(RenderTarget main) {
		if (captured) {
			return;
		}
		captured = true;
		GpuTexture color = main.getColorTexture();
		if (MetalSettings.get().hdr && color != null) {
			MetalRenderer.captureWorldForHdr(color);
		}
	}
}
