package dev.mine2metal.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.mine2metal.upscale.WorldUpscaler;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Everything renderLevel draws, the hand included, goes to {@code mainRenderTarget}; swapping it for the duration of
 * the call is what lets the world render at a lower resolution than the GUI.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Shadow
	@Final
	@Mutable
	private RenderTarget mainRenderTarget;

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void mine2metal$renderWorldScaled(CallbackInfo ci) {
		this.mainRenderTarget = WorldUpscaler.begin(this.mainRenderTarget);
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void mine2metal$upscaleWorld(CallbackInfo ci) {
		this.mainRenderTarget = WorldUpscaler.end(this.mainRenderTarget);
	}
}
