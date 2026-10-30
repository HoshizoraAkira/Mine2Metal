package dev.mine2metal.frame;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.mine2metal.config.MetalSettings;
import dev.mine2metal.metal.MetalRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * What the backend does around Minecraft's world pass, in order: the world is drawn smaller (and jittered) while
 * upscaling, upscaled onto the main target before the hand, then captured for frame generation and EDR output before
 * the hand, screen effects and the GUI are drawn over it. Each pass is a {@link WorldFrame}.
 * <p>
 * Driven from {@code GameRendererMixin}: {@link #begin} at the start of renderLevel, {@link #captureProjection} when it
 * uploads the world's projection, {@link #upscale} and {@link #capture} once the world is done (at the start of
 * render3dHud, or after renderLevel if it never got there), and always {@link #end} last.
 */
public final class WorldPass {
	private static final WorldShader worldShader = new WorldShader();
	private static final WorldUpscaler upscaler = new WorldUpscaler();
	private static final FrameGeneration frameGeneration = new FrameGeneration();
	private static final HdrOutput hdrOutput = new HdrOutput();
	private static boolean lastShaderLit;
	private static @Nullable WorldFrame frame;

	private WorldPass() {
	}

	public static void begin(RenderTarget main, LevelRenderState levelState) {
		boolean shaded = MetalSettings.get().shadersEnabled() && MetalRenderer.isActive()
				&& levelState.skyRenderState.skybox == DimensionType.Skybox.OVERWORLD;
		if (shaded) {
			levelState.cloudColor = 0;
			levelState.skyRenderState.shouldRenderDarkDisc = false;
		}
		begin(main, levelState.cameraRenderState, levelState.skyRenderState);
		frame.underwater = shaded && levelState.cameraRenderState.fogType == FogType.WATER;
	}

	/**
	 * Whether this frame's built-in shaders draw the water fog themselves (the camera is under water in the overworld);
	 * vanilla's own water fog is then left out of the fog every shader reads.
	 */
	public static boolean replacesWaterFog() {
		WorldFrame current = frame;
		return current != null && current.underwater;
	}

	public static void begin(RenderTarget main, CameraRenderState camera) {
		begin(main, camera, null);
	}

	private static void begin(RenderTarget main, CameraRenderState camera, @Nullable SkyRenderState sky) {
		WorldFrame previous = frame;
		if (previous != null && previous.lent) {
			throw new IllegalStateException("The previous world pass did not return the main render target's textures");
		}
		WorldFrame current = new WorldFrame(main, camera, sky, WorldUpscaler.activeMode());
		frame = current;
		upscaler.begin(current);
	}

	/** The projection renderLevel uploads for the world. */
	public static void captureProjection(Matrix4f uploaded) {
		WorldFrame current = frame;
		if (current != null) {
			upscaler.captureProjection(current, uploaded);
		}
	}

	/** The main world pass is about to open: it gets the G-buffer when the built-in shaders will light it. */
	public static void armGbuffer() {
		WorldFrame current = frame;
		MetalRenderer.armWorldGbuffer(current != null && worldShader.wantsGbuffer(current));
	}

	/** The opaque terrain and solid entities are drawn into the main world pass: the built-in shaders light them. */
	public static void lightTerrain() {
		MetalRenderer.armWorldGbuffer(false);
		WorldFrame current = frame;
		if (current != null) {
			worldShader.lightTerrain(current);
		}
	}

	/** Whether the built-in shaders lit the last finished world pass from the G-buffer (for the game tests). */
	public static boolean lastFrameShaderLit() {
		return lastShaderLit;
	}

	/** The translucent terrain of the main world pass is about to draw. */
	public static void beginWater() {
		WorldFrame current = frame;
		if (current != null) {
			worldShader.beginWater(current);
		}
	}

	/** The translucent terrain of the main world pass is drawn. */
	public static void endWater() {
		WorldFrame current = frame;
		if (current != null) {
			worldShader.endWater(current);
		}
	}

	/** A block changed in the client's world. */
	public static void blockChanged(BlockPos pos, BlockState state) {
		worldShader.blockChanged(pos, state);
	}

	/** Before the hand is drawn: the built-in shaders' post pass on the world, then main gets its textures back, upscaled. */
	public static void upscale() {
		WorldFrame current = frame;
		if (current != null && !current.shaded) {
			current.shaded = true;
			worldShader.post(current);
			lastShaderLit = current.shaderLit;
			upscaler.end(current);
		}
	}

	/** Once the world is upscaled: hands it to frame generation and EDR output, at most once a frame. */
	public static void capture() {
		WorldFrame current = frame;
		if (current == null || current.captured) {
			return;
		}
		current.captured = true;
		// EDR first: frame generation expands its copy of the world with the levels and peak the EDR capture sets.
		hdrOutput.capture(current);
		frameGeneration.capture(current);
	}

	/** For the debug screen: frames on screen per second while frame generation is chosen; null otherwise. */
	public static @Nullable String frameGenerationDebugLine() {
		return frameGeneration.debugLine();
	}

	/** Makes sure main has its own textures and the camera its own projection back, whether or not the world pass finished. */
	public static void end() {
		WorldFrame current = frame;
		if (current != null) {
			upscaler.abandon(current);
		}
	}
}
