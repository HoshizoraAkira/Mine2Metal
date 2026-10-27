package dev.mine2metal.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.api.device.GpuBackend;
import dev.mine2metal.Mine2Metal;
import dev.mine2metal.metal.MetalBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Puts Metal first in whatever order the player's graphics API setting produces. No enum value is added: Sodium's
 * options screen labels exactly the three vanilla values.
 */
@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
	@ModifyReturnValue(method = "getBackendsToTry", at = @At("RETURN"))
	private GpuBackend[] mine2metal$preferMetal(GpuBackend[] backends) {
		if (!Mine2Metal.isEnabled() || !Mine2Metal.isSupportedPlatform()) {
			return backends;
		}

		GpuBackend[] withMetal = new GpuBackend[backends.length + 1];
		withMetal[0] = new MetalBackend();
		System.arraycopy(backends, 0, withMetal, 1, backends.length);
		return withMetal;
	}
}
