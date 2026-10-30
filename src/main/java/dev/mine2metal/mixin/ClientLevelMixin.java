package dev.mine2metal.mixin;

import dev.mine2metal.frame.WorldPass;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Placed and broken blocks reach the world shader's voxel grid at once, so their shadows follow in the same frame. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void mine2metal$updateVoxel(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
		WorldPass.blockChanged(pos, newState);
	}
}
