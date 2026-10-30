package dev.mine2metal.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import dev.mine2metal.metal.GbufferShaderPatch;
import net.minecraft.client.renderer.ShaderDefines;
import org.spongepowered.asm.mixin.Mixin;

/** Adds the world shader's G-buffer outputs to Sodium's terrain shader before it is compiled (see {@link GbufferShaderPatch}). */
@Mixin(GlslCompiler.class)
abstract class GlslCompilerMixin {
	@WrapMethod(method = "compileToSpv")
	private SpvModule mine2metal$patchTerrain(String name, String source, ShaderType type, ShaderDefines defines, ShaderSource shaderSource,
			Operation<SpvModule> original) {
		return original.call(name, GbufferShaderPatch.patch(name, type, source), type, defines, shaderSource);
	}
}
