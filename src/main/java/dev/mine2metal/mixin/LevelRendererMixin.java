package dev.mine2metal.mixin;

import dev.mine2metal.frame.WorldPass;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The world shader's points in the main pass: its render pass gets the G-buffer, once the opaque terrain and the solid
 * entities are drawn (before water and particles) they are lit from it, and the translucent terrain is drawn with the
 * G-buffer again so its water can be redrawn.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
	private void mine2metal$attachGbuffer(CallbackInfo ci) {
		WorldPass.armGbuffer();
	}

	@Inject(method = "executeSolid", at = @At("TAIL"))
	private void mine2metal$lightTerrain(CallbackInfo ci) {
		WorldPass.lightTerrain();
	}

	@Inject(method = "executeClassicTransparency", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"))
	private void mine2metal$beginWater(CallbackInfo ci) {
		WorldPass.beginWater();
	}

	@Inject(method = "executeClassicTransparency", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V",
			shift = At.Shift.AFTER))
	private void mine2metal$endWater(CallbackInfo ci) {
		WorldPass.endWater();
	}
}
