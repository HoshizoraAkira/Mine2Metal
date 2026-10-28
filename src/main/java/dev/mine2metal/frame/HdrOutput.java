package dev.mine2metal.frame;

import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.mine2metal.config.MetalSettings;
import dev.mine2metal.metal.MetalRenderer;

/**
 * EDR output: the world's highlights shown brighter than SDR white. The world is captured once it is drawn, before the
 * hand and the GUI, so the present can tell them apart and keep them at SDR brightness (see {@code hdr.m}).
 * <p>
 * Driven by {@link WorldPass}, which hands it each frame's world once it is done.
 */
final class HdrOutput {
	/** With EDR on. */
	void capture(WorldFrame frame) {
		GpuTexture color = frame.main.getColorTexture();
		if (MetalSettings.get().hdr && color != null) {
			MetalRenderer.captureWorldForHdr(color);
		}
	}
}
