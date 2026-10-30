package dev.mine2metal.mixin;

import dev.mine2metal.frame.WorldPass;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Under water the built-in shaders draw the water fog over the whole world, so the fog every vanilla shader reads
 * leaves out its environmental part. Only the copy sent to the GPU changes: the camera keeps the real distances, which
 * the shaders use for how far one can see.
 */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
	@ModifyVariable(method = "updateBuffer(Lnet/minecraft/client/renderer/fog/FogData;)V", at = @At("HEAD"), argsOnly = true)
	private FogData mine2metal$dropWaterFog(FogData fog) {
		if (!WorldPass.replacesWaterFog()) {
			return fog;
		}
		FogData copy = new FogData();
		copy.environmentalStart = Float.MAX_VALUE;
		copy.environmentalEnd = Float.MAX_VALUE;
		copy.renderDistanceStart = fog.renderDistanceStart;
		copy.renderDistanceEnd = fog.renderDistanceEnd;
		copy.skyEnd = fog.skyEnd;
		copy.cloudEnd = fog.cloudEnd;
		copy.color = fog.color;
		return copy;
	}
}
