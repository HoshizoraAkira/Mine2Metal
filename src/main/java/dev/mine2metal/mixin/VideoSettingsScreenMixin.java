package dev.mine2metal.mixin;

import dev.mine2metal.Mine2Metal;
import dev.mine2metal.config.MetalSettingsScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a "Metal Settings..." button to vanilla's video settings; with Sodium installed its own page is used instead. */
@Mixin(VideoSettingsScreen.class)
abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
	private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At("RETURN"))
	private void mine2metal$addMetalButton(CallbackInfo ci) {
		if (!Mine2Metal.isSupportedPlatform()) {
			return;
		}
		this.list.addBig(Button.builder(Component.translatable("mine2metal.options.button"),
				button -> this.minecraft.gui.setScreen(new MetalSettingsScreen(this, this.options))).build());
	}
}
