package dev.mine2metal.metal;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import dev.mine2metal.Mine2Metal;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Translated shaders plus fixed-function state. The MTLRenderPipelineState itself is created per depth/stencil
 * attachment format on first use, because renderpearl only learns those formats when a pass begins.
 */
final class MetalRenderPipeline implements BackendRenderPipeline {
	private static final long SLOW_STATE_MILLIS = 20;
	/** Uniform slots are 0..25; the push constant block and vertex buffers sit above them. */
	static final int PUSH_CONSTANTS_SLOT = 26;
	static final int VERTEX_BUFFER_BASE_SLOT = 27;
	static final int MAX_UNIFORMS = PUSH_CONSTANTS_SLOT;

	private static final int VERTEX_STEP_PER_VERTEX = 1;
	private static final int VERTEX_STEP_PER_INSTANCE = 2;
	private static final int COMPARE_ALWAYS = 7;
	private static final int PIXEL_FORMAT_RGBA8_UNORM = 70;
	private static final int COLOR_TARGET_INTS = 9;
	private static final int GBUFFER_STATE_BIT = 1 << 30;

	final String name;
	final List<BindGroupLayout.UniformDescription> uniforms;
	final long vertexUniformMask;
	final long fragmentUniformMask;
	final long texelBufferMask;
	final PrimitiveTopology topology;
	final int primitive;
	final boolean cull;
	final boolean wireframe;
	final float depthBias;
	final float depthBiasSlope;
	/** 0 when compilation failed; draws with this pipeline are then skipped. */
	final long depthState;
	/** Sodium's terrain shader with {@link GbufferShaderPatch}: it writes the G-buffer when the pass has one. */
	final boolean writesGbuffer;

	private final long ctx;
	private final MetalCommandEncoder encoder;
	private final long vertexLibrary;
	private final String vertexEntry;
	private final long fragmentLibrary;
	private final String fragmentEntry;
	private final int[] layout;
	private int @Nullable [] gbufferLayout;
	/** For a shader that does not write the G-buffer: its MSL, until the marker variant below is compiled from it. */
	private @Nullable String fragmentSource;
	/**
	 * That shader with a zero written to G-buffer attachment 1 (material 0: "leave this pixel as drawn"), so whatever
	 * it covers in the G-buffer is not lit over it; 0 until needed, -1 if it cannot be made.
	 */
	private long markerFragmentLibrary;
	private final Int2LongMap states = new Int2LongOpenHashMap();
	private boolean closed;

	private MetalRenderPipeline(long ctx, MetalCommandEncoder encoder, CreateInfo info, int[] layout, long vertexLibrary, String vertexEntry,
			long vertexUniformMask, long fragmentLibrary, String fragmentEntry, long fragmentUniformMask, long depthState,
			boolean writesGbuffer) {
		this.ctx = ctx;
		this.encoder = encoder;
		this.name = info.name();
		this.uniforms = info.uniforms();
		this.vertexUniformMask = vertexUniformMask;
		this.fragmentUniformMask = fragmentUniformMask;
		long texelMask = 0L;
		for (int i = 0; i < this.uniforms.size(); i++) {
			if (this.uniforms.get(i).type() == UniformType.TEXEL_BUFFER) {
				texelMask |= 1L << i;
			}
		}
		this.texelBufferMask = texelMask;
		this.topology = info.primitiveTopology();
		this.primitive = MetalFormats.primitive(info.primitiveTopology());
		this.cull = info.cull();
		this.wireframe = info.polygonMode() == PolygonMode.WIREFRAME;
		DepthStencilState depth = info.depthStencilState();
		this.depthBias = depth != null ? depth.depthBiasConstant() : 0.0F;
		this.depthBiasSlope = depth != null ? depth.depthBiasScaleFactor() : 0.0F;
		this.vertexLibrary = vertexLibrary;
		this.vertexEntry = vertexEntry;
		this.fragmentLibrary = fragmentLibrary;
		this.fragmentEntry = fragmentEntry;
		this.depthState = depthState;
		this.layout = layout;
		this.writesGbuffer = writesGbuffer;
	}

	private MetalRenderPipeline withFragmentSource(String source) {
		this.fragmentSource = this.writesGbuffer ? null : source;
		return this;
	}

	static MetalRenderPipeline compile(long ctx, MetalCommandEncoder encoder, CreateInfo info) {
		long vertexLibrary = 0L;
		long fragmentLibrary = 0L;
		try {
			if (info.uniforms().size() > MAX_UNIFORMS) {
				throw new IllegalStateException(info.uniforms().size() + " uniforms; Metal slots allow " + MAX_UNIFORMS);
			}
			int[] layout = layout(info);
			CreateInfo.Shader vertex = shader(info, ShaderType.VERTEX);
			CreateInfo.Shader fragment = shader(info, ShaderType.FRAGMENT);
			long start = System.nanoTime();
			ShaderTranslator.Translated vertexMsl = ShaderTranslator.translate(vertex.module(), vertex.entryPoint(), info.uniforms().size());
			ShaderTranslator.Translated fragmentMsl = ShaderTranslator.translate(fragment.module(), fragment.entryPoint(), info.uniforms().size());
			long translated = System.nanoTime();
			vertexLibrary = MetalNative.libraryCreate(ctx, vertexMsl.source());
			fragmentLibrary = MetalNative.libraryCreate(ctx, fragmentMsl.source());
			Mine2Metal.LOGGER.debug("Compiled pipeline {}: SPIR-V to MSL {} us, MSL to library {} us", info.name(), (translated - start) / 1000,
					(System.nanoTime() - translated) / 1000);

			DepthStencilState depth = info.depthStencilState();
			long depthState = depth != null
					? MetalNative.depthStateCreate(ctx, MetalFormats.compare(depth.depthTest()), depth.writeDepth())
					: MetalNative.depthStateCreate(ctx, COMPARE_ALWAYS, false);
			return new MetalRenderPipeline(ctx, encoder, info, layout, vertexLibrary, vertexMsl.entryPoint(), vertexMsl.usedUniformMask(),
					fragmentLibrary, fragmentMsl.entryPoint(), fragmentMsl.usedUniformMask(), depthState,
					fragmentMsl.source().contains(GbufferShaderPatch.MARKER)).withFragmentSource(fragmentMsl.source());
		} catch (RuntimeException e) {
			Mine2Metal.LOGGER.error("Could not compile pipeline {} for Metal; its draws will be skipped", info.name(), e);
			MetalNative.release(vertexLibrary);
			MetalNative.release(fragmentLibrary);
			return new MetalRenderPipeline(ctx, encoder, info, new int[0], 0L, "", 0L, 0L, "", 0L, 0L, false);
		}
	}

	private static CreateInfo.Shader shader(CreateInfo info, ShaderType type) {
		return info.shaders().stream()
				.filter(shader -> shader.module().type() == type)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(info.name() + " has no " + type + " shader"));
	}

	/** Encodes vertex input and color targets in the layout m2m_pipeline_create reads. */
	private static int[] layout(CreateInfo info) {
		IntArrayList out = new IntArrayList();
		out.add(info.vertexBuffers().size());
		for (CreateInfo.VertexBuffer buffer : info.vertexBuffers()) {
			boolean perInstance = buffer.stepRate() > 0;
			out.add(VERTEX_BUFFER_BASE_SLOT + buffer.bufferSlot());
			out.add(buffer.stride());
			out.add(perInstance ? VERTEX_STEP_PER_INSTANCE : VERTEX_STEP_PER_VERTEX);
			out.add(perInstance ? buffer.stepRate() : 1);
		}

		out.add(info.attribBindings().size());
		for (CreateInfo.AttribBinding attribute : info.attribBindings()) {
			out.add(attribute.location());
			out.add(VERTEX_BUFFER_BASE_SLOT + attribute.bufferSlot());
			out.add(attribute.offset());
			out.add(MetalFormats.vertexFormat(attribute.format()));
		}

		out.add(info.colorTargetStates().size());
		for (@Nullable ColorTargetState target : info.colorTargetStates()) {
			if (target == null) {
				out.addElements(out.size(), new int[9]);
				continue;
			}
			out.add(MetalFormats.pixelFormat(target.format()));
			out.add((target.writeRed() ? 8 : 0) | (target.writeGreen() ? 4 : 0) | (target.writeBlue() ? 2 : 0) | (target.writeAlpha() ? 1 : 0));
			BlendFunction blend = target.blendFunction().orElse(null);
			out.add(blend != null ? 1 : 0);
			addBlend(out, blend != null ? blend.color() : null);
			addBlend(out, blend != null ? blend.alpha() : null);
		}
		return out.toIntArray();
	}

	private static void addBlend(IntArrayList out, @Nullable BlendEquation equation) {
		if (equation == null) {
			// Metal's defaults: one, zero, add.
			out.add(1);
			out.add(0);
			out.add(0);
			return;
		}
		out.add(MetalFormats.blendFactor(equation.sourceFactor()));
		out.add(MetalFormats.blendFactor(equation.destFactor()));
		out.add(MetalFormats.blendOp(equation.op()));
	}

	boolean isUsable() {
		return this.vertexLibrary != 0L && !this.closed;
	}

	@Nullable UniformType uniformType(int index) {
		return index < this.uniforms.size() ? this.uniforms.get(index).type() : null;
	}

	/**
	 * The pipeline state for a pass with these depth/stencil formats (0 for none), and the world shader's G-buffer as
	 * color attachments 1 and 2 when gbuffer is set; 0 if Metal rejected it.
	 */
	long state(int depthFormat, int stencilFormat, boolean gbuffer) {
		int key = depthFormat << 16 | stencilFormat | (gbuffer ? GBUFFER_STATE_BIT : 0);
		if (this.states.containsKey(key)) {
			return this.states.get(key);
		}

		long state = 0L;
		long start = System.nanoTime();
		try {
			int[] colors = gbuffer ? this.gbufferLayout() : this.layout;
			long fragment = gbuffer && this.markerLibrary() > 0L ? this.markerFragmentLibrary : this.fragmentLibrary;
			state = MetalNative.pipelineCreate(this.ctx, this.vertexLibrary, this.vertexEntry, fragment, this.fragmentEntry, colors,
					depthFormat, stencilFormat, this.name);
		} catch (RuntimeException e) {
			Mine2Metal.LOGGER.error("Metal rejected pipeline {}; its draws will be skipped", this.name, e);
		}
		long millis = (System.nanoTime() - start) / 1_000_000L;
		// Made on first use, on the render thread: a slow one is a hitch.
		if (millis >= SLOW_STATE_MILLIS) {
			Mine2Metal.LOGGER.info("Creating the pipeline state for {} took {} ms", this.name, millis);
		}
		this.states.put(key, state);
		return state;
	}

	/** The marker variant's library (see {@link #markerFragmentLibrary}), compiled on first use; -1 if there is none. */
	private long markerLibrary() {
		if (this.markerFragmentLibrary == 0L) {
			String marked = this.fragmentSource != null ? withGbufferMarker(this.fragmentSource) : null;
			long library = -1L;
			if (marked != null) {
				try {
					library = MetalNative.libraryCreate(this.ctx, marked);
				} catch (RuntimeException e) {
					Mine2Metal.LOGGER.warn("Could not mark pipeline {} in the G-buffer; it may be lit as what it covers", this.name, e);
				}
			}
			this.markerFragmentLibrary = library;
			this.fragmentSource = null;
		}
		return this.markerFragmentLibrary;
	}

	/**
	 * SPIRV-Cross's MSL with one more output, at color 1; its output struct starts zeroed, so that output is 0. Null
	 * for a shader without color outputs.
	 */
	private static @Nullable String withGbufferMarker(String msl) {
		int struct = msl.indexOf("struct main0_out");
		int end = struct < 0 ? -1 : msl.indexOf("};", struct);
		if (end < 0 || msl.substring(struct, end).contains("[[color(1)]]")) {
			return null;
		}
		String marked = msl.substring(0, end) + "    float4 m2m_gbufferMarker [[color(1)]];\n" + msl.substring(end);
		if (!marked.contains("main0_out out = {};")) {
			if (!marked.contains("main0_out out;")) {
				return null;
			}
			marked = marked.replace("main0_out out;", "main0_out out = {};");
		}
		return marked;
	}

	/**
	 * The layout with the G-buffer's two RGBA8 targets after the first color target: written by the patched shaders,
	 * marked (attachment 1 only) by any other drawn into that pass.
	 */
	private int[] gbufferLayout() {
		int[] cached = this.gbufferLayout;
		if (cached != null) {
			return cached;
		}
		int cursor = 0;
		int bufferCount = this.layout[cursor];
		cursor += 1 + bufferCount * 4;
		int attributeCount = this.layout[cursor];
		cursor += 1 + attributeCount * 4;
		int colorCount = this.layout[cursor];
		int[] out = this.layout;
		if (colorCount <= 1) {
			out = new int[cursor + 1 + 3 * COLOR_TARGET_INTS];
			System.arraycopy(this.layout, 0, out, 0, cursor + 1 + colorCount * COLOR_TARGET_INTS);
			out[cursor] = 3;
			for (int target = 1; target <= 2; target++) {
				int base = cursor + 1 + target * COLOR_TARGET_INTS;
				out[base] = PIXEL_FORMAT_RGBA8_UNORM;
				out[base + 1] = this.writesGbuffer || target == 1 && this.markerLibrary() > 0L ? 0xF : 0;
				// No blending; the factors are Metal's defaults (one, zero, add).
				out[base + 3] = 1;
				out[base + 6] = 1;
			}
		}
		this.gbufferLayout = out;
		return out;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.encoder.releaseAfterGpu(this.vertexLibrary);
		this.encoder.releaseAfterGpu(this.fragmentLibrary);
		if (this.markerFragmentLibrary > 0L) {
			this.encoder.releaseAfterGpu(this.markerFragmentLibrary);
		}
		this.encoder.releaseAfterGpu(this.depthState);
		for (long state : this.states.values()) {
			this.encoder.releaseAfterGpu(state);
		}
	}
}
