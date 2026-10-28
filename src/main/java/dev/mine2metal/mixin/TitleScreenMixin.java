package dev.mine2metal.mixin;

import dev.mine2metal.MetalAvailability;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Says on the title screen, once, why the game is not rendering with Metal when it would have. */
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin {
	@Inject(method = "init", at = @At("TAIL"))
	private void mine2metal$explainFallback(CallbackInfo ci) {
		MetalAvailability.showOnce(Minecraft.getInstance());
	}
}
