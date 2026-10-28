package dev.mine2metal.mixin;

import dev.mine2metal.frame.WorldPass;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Below the debug screen's fps, which counts the frames the game renders: with frame generation, how many reach the
 * screen. The game then renders at most half the refresh rate, so the fps line alone looks like half of what is shown.
 */
@Mixin(DebugEntryFps.class)
abstract class DebugEntryFpsMixin {
	@Inject(method = "display", at = @At("TAIL"))
	private void mine2metal$showFramesOnScreen(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk,
			CallbackInfo ci) {
		String line = WorldPass.frameGenerationDebugLine();
		if (line != null) {
			displayer.addPriorityLine(line);
		}
	}
}
