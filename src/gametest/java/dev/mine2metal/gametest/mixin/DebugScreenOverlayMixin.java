package dev.mine2metal.gametest.mixin;

import dev.mine2metal.gametest.FrameTimes;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft reports every frame's duration here, whatever the graphics backend; the frame time test records them. */
@Mixin(DebugScreenOverlay.class)
abstract class DebugScreenOverlayMixin {
	@Inject(method = "logFrameDuration", at = @At("HEAD"))
	private void mine2metal$recordFrame(long nanos, CallbackInfo ci) {
		FrameTimes.record(nanos);
	}
}
