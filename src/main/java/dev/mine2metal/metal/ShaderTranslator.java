package dev.mine2metal.metal;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.util.spvc.Spvc.*;

/**
 * SPIR-V to MSL with SPIRV-Cross (the copy LWJGL ships with Minecraft). Mojang's pipeline builder already rewrote every
 * descriptor to set 0, binding = uniform index, so uniform {@code i} becomes Metal buffer/texture/sampler slot {@code i}.
 */
final class ShaderTranslator {
	private static final int MSL_VERSION_3_0 = 30000;

	private ShaderTranslator() {
	}

	record Translated(String source, String entryPoint) {
	}

	static Translated translate(SpvModule module, String entryPoint, int uniformCount) {
		boolean vertex = module.type() == ShaderType.VERTEX;
		int model = vertex ? Spv.SpvExecutionModelVertex : Spv.SpvExecutionModelFragment;

		ByteBuffer spirv = module.spv();
		ByteBuffer words = MemoryUtil.memAlloc(spirv.remaining());
		try (MemoryStack stack = MemoryStack.stackPush()) {
			words.put(spirv.duplicate()).flip();
			IntBuffer code = words.asIntBuffer();

			PointerBuffer out = stack.callocPointer(1);
			check(0L, spvc_context_create(out), "create context");
			long context = out.get(0);
			try {
				check(context, spvc_context_parse_spirv(context, code, code.remaining(), out), "parse");
				long ir = out.get(0);
				check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "create compiler");
				long compiler = out.get(0);

				check(context, spvc_compiler_create_compiler_options(compiler, out), "create options");
				long options = out.get(0);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_3_0);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
				// Keeps GL's bottom-up framebuffer layout, so scissor rects, gl_FragCoord and readbacks match the GL backend.
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, vertex);
				check(context, spvc_compiler_install_compiler_options(compiler, options), "install options");

				for (int i = 0; i < uniformCount; i++) {
					bind(stack, compiler, model, 0, i, i);
				}
				bind(stack, compiler, model, SPVC_MSL_PUSH_CONSTANT_DESC_SET, SPVC_MSL_PUSH_CONSTANT_BINDING, MetalRenderPipeline.PUSH_CONSTANTS_SLOT);

				check(context, spvc_compiler_compile(compiler, out), "compile");
				String source = MemoryUtil.memUTF8(out.get(0));
				return new Translated(source, spvc_compiler_get_cleansed_entry_point_name(compiler, entryPoint, model));
			} finally {
				spvc_context_destroy(context);
			}
		} finally {
			MemoryUtil.memFree(words);
		}
	}

	private static void bind(MemoryStack stack, long compiler, int model, int set, int binding, int slot) {
		SpvcMslResourceBinding resource = SpvcMslResourceBinding.calloc(stack);
		spvc_msl_resource_binding_init(resource);
		resource.stage(model).desc_set(set).binding(binding).msl_buffer(slot).msl_texture(slot).msl_sampler(slot);
		spvc_compiler_msl_add_resource_binding(compiler, resource);
	}

	private static void check(long context, int result, String step) {
		if (result != SPVC_SUCCESS) {
			String detail = context != 0L ? spvc_context_get_last_error_string(context) : "error " + result;
			throw new IllegalStateException("SPIRV-Cross could not " + step + ": " + detail);
		}
	}
}
