package dev.mine2metal.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.mine2metal.metal.MetalRenderer;
import dev.mine2metal.frame.WorldPass;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The world renders into {@code mainRenderTarget} during renderLevel; lending that target smaller textures until
 * render3dHud lets the world render at a lower resolution than the hand and the GUI. The textures are returned in a
 * finally block so a failed frame cannot leave the main target small.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@Shadow
	@Final
	private GameRenderState gameRenderState;

	@WrapMethod(method = "renderLevel")
	private void mine2metal$renderWorldScaled(Operation<Void> original) {
		WorldPass.begin(this.mainRenderTarget, this.gameRenderState.levelRenderState.cameraRenderState);
		try {
			original.call();
			WorldPass.upscale();
			WorldPass.capture();
		} finally {
			WorldPass.end();
		}
	}

	/** Records the world's final projection (with bobbing and nausea) for the temporal upscaler's motion vectors. */
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private GpuBufferSlice mine2metal$captureProjection(ProjectionMatrixBuffer buffer, Matrix4f projection, Operation<GpuBufferSlice> original) {
		WorldPass.captureProjection(projection);
		return original.call(buffer, projection);
	}

	/**
	 * The world is done; upscale it before the hand and screen effects draw at full resolution, and capture it for
	 * frame interpolation and EDR output without them.
	 */
	@Inject(method = "render3dHud", at = @At("HEAD"))
	private void mine2metal$upscaleBeforeHand(CallbackInfo ci) {
		MetalRenderer.markStage("upscaling");
		WorldPass.upscale();
		MetalRenderer.markStage("world capture");
		WorldPass.capture();
		MetalRenderer.markStage("hand, effects and GUI");
	}
}
