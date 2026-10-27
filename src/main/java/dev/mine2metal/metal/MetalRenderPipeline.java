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
	/** Uniform slots are 0..25; the push constant block and vertex buffers sit above them. */
	static final int PUSH_CONSTANTS_SLOT = 26;
	static final int VERTEX_BUFFER_BASE_SLOT = 27;
	static final int MAX_UNIFORMS = PUSH_CONSTANTS_SLOT;

	private static final int VERTEX_STEP_PER_VERTEX = 1;
	private static final int VERTEX_STEP_PER_INSTANCE = 2;
	private static final int COMPARE_ALWAYS = 7;

	final String name;
	final List<BindGroupLayout.UniformDescription> uniforms;
	final PrimitiveTopology topology;
	final int primitive;
	final boolean cull;
	final boolean wireframe;
	final float depthBias;
	final float depthBiasSlope;
	/** 0 when compilation failed; draws with this pipeline are then skipped. */
	final long depthState;

	private final long ctx;
	private final MetalCommandEncoder encoder;
	private final long vertexLibrary;
	private final String vertexEntry;
	private final long fragmentLibrary;
	private final String fragmentEntry;
	private final int[] layout;
	private final Int2LongMap states = new Int2LongOpenHashMap();
	private boolean closed;

	private MetalRenderPipeline(long ctx, MetalCommandEncoder encoder, CreateInfo info, int[] layout, long vertexLibrary, String vertexEntry,
			long fragmentLibrary, String fragmentEntry, long depthState) {
		this.ctx = ctx;
		this.encoder = encoder;
		this.name = info.name();
		this.uniforms = info.uniforms();
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
			return new MetalRenderPipeline(ctx, encoder, info, layout, vertexLibrary, vertexMsl.entryPoint(), fragmentLibrary, fragmentMsl.entryPoint(),
					depthState);
		} catch (RuntimeException e) {
			Mine2Metal.LOGGER.error("Could not compile pipeline {} for Metal; its draws will be skipped", info.name(), e);
			MetalNative.release(vertexLibrary);
			MetalNative.release(fragmentLibrary);
			return new MetalRenderPipeline(ctx, encoder, info, new int[0], 0L, "", 0L, "", 0L);
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

	/** The pipeline state for a pass with these depth/stencil formats (0 for none), or 0 if Metal rejected it. */
	long state(int depthFormat, int stencilFormat) {
		int key = depthFormat << 16 | stencilFormat;
		if (this.states.containsKey(key)) {
			return this.states.get(key);
		}

		long state = 0L;
		try {
			state = MetalNative.pipelineCreate(this.ctx, this.vertexLibrary, this.vertexEntry, this.fragmentLibrary, this.fragmentEntry, this.layout,
					depthFormat, stencilFormat, this.name);
		} catch (RuntimeException e) {
			Mine2Metal.LOGGER.error("Metal rejected pipeline {}; its draws will be skipped", this.name, e);
		}
		this.states.put(key, state);
		return state;
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
		this.encoder.releaseAfterGpu(this.depthState);
		for (long state : this.states.values()) {
			this.encoder.releaseAfterGpu(state);
		}
	}
}
