package dev.mine2metal.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.mine2metal.upscale.WorldUpscaler;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Everything renderLevel draws, the hand included, goes to {@code mainRenderTarget}; swapping it for the duration of
 * the call is what lets the world render at a lower resolution than the GUI. The real target is put back in a finally
 * block: if a frame failed half way with the scaled target left in place, turning upscaling off would later destroy the
 * game's main target.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Shadow
	@Final
	@Mutable
	private RenderTarget mainRenderTarget;

	@WrapMethod(method = "renderLevel")
	private void mine2metal$renderWorldScaled(Operation<Void> original) {
		RenderTarget main = this.mainRenderTarget;
		this.mainRenderTarget = WorldUpscaler.begin(main);
		try {
			original.call();
			WorldUpscaler.end();
		} finally {
			WorldUpscaler.abandonFrame();
			this.mainRenderTarget = main;
		}
	}
}
