package dev.mine2metal.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.mine2metal.upscale.WorldUpscaler;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Everything renderLevel draws, the hand included, goes to {@code mainRenderTarget}; lending that target smaller
 * textures for the duration of the call lets the world render at a lower resolution than the GUI. The textures are
 * returned in a finally block so a failed frame cannot leave the main target small.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@WrapMethod(method = "renderLevel")
	private void mine2metal$renderWorldScaled(Operation<Void> original) {
		WorldUpscaler.begin(this.mainRenderTarget);
		try {
			original.call();
			WorldUpscaler.end();
		} finally {
			WorldUpscaler.abandonFrame();
		}
	}
}
