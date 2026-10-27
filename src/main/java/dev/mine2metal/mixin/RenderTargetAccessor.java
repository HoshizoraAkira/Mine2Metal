package dev.mine2metal.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the world upscaler copy the main target's formats and lend it a smaller set of textures. */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
	@Accessor("colorFormat")
	GpuFormat mine2metal$colorFormat();

	@Accessor("depthFormat")
	GpuFormat mine2metal$depthFormat();

	@Accessor("colorTexture")
	void mine2metal$setColorTexture(GpuTexture texture);

	@Accessor("colorTextureView")
	void mine2metal$setColorTextureView(GpuTextureView view);

	@Accessor("depthTexture")
	void mine2metal$setDepthTexture(GpuTexture texture);

	@Accessor("depthTextureView")
	void mine2metal$setDepthTextureView(GpuTextureView view);
}
