package dev.mine2metal.frame;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.state.level.CameraRenderState;
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
	private static final WorldUpscaler upscaler = new WorldUpscaler();
	private static final FrameGeneration frameGeneration = new FrameGeneration();
	private static final HdrOutput hdrOutput = new HdrOutput();
	private static @Nullable WorldFrame frame;

	private WorldPass() {
	}

	public static void begin(RenderTarget main, CameraRenderState camera) {
		WorldFrame previous = frame;
		if (previous != null && previous.lent) {
			throw new IllegalStateException("The previous world pass did not return the main render target's textures");
		}
		WorldFrame current = new WorldFrame(main, camera, WorldUpscaler.activeMode());
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

	/** Before the hand is drawn: gives main its textures back, with the world upscaled onto them. */
	public static void upscale() {
		WorldFrame current = frame;
		if (current != null) {
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
		frameGeneration.capture(current);
		hdrOutput.capture(current);
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
