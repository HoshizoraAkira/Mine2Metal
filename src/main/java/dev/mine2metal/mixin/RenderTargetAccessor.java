package dev.mine2metal.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the scaled world target copy the main target's formats. */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
	@Accessor("colorFormat")
	GpuFormat mine2metal$colorFormat();

	@Accessor("depthFormat")
	GpuFormat mine2metal$depthFormat();
}
