package dev.mine2metal.frame;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.mine2metal.config.MetalSettings;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * One frame's world pass, from the start of renderLevel until the hand is drawn: what the world was drawn with. The
 * upscaler fills it in; frame generation and EDR output read it. A new one is made each frame, so nothing carries over
 * by accident; what must carry over (history, the jitter phase, the small target) stays with each stage.
 */
final class WorldFrame {
	final RenderTarget main;
	final CameraRenderState camera;
	final @Nullable SkyRenderState sky;
	/** The upscaling this world pass began with. */
	final MetalSettings.Upscaling upscaling;

	/** The small target the world was drawn into, or null when it was drawn at full size (or the pass was abandoned). */
	@Nullable TextureTarget scaled;
	/** While main holds the small target's textures. */
	boolean lent;

	/** Whether the world was drawn with a jittered projection. */
	boolean jittered;
	/** The jitter, in pixels of the small target (rows in GL's bottom-up order), and the sign the scaler is told it with. */
	float jitterX;
	float jitterY;
	float jitterSignX = 1.0F;
	float jitterSignY = 1.0F;
	/** The camera's own projection, put back once the world is drawn; meaningful while {@link #projectionJittered}. */
	final Matrix4f savedProjection = new Matrix4f();
	boolean projectionJittered;
	/** The jitter sign changed since the previous frame, so the scaler's history no longer fits. */
	boolean jitterSignChanged;

	/** The world's final projection (bobbing and nausea included) with any jitter taken out again. */
	final Matrix4f projection = new Matrix4f();
	boolean projectionCaptured;

	/** Whether the world shader's per-frame data is gathered, and whether it lit the terrain from the G-buffer. */
	boolean shaderPrepared;
	boolean shaderLit;
	/** The camera is under water and the world shader draws the water fog, in place of vanilla's. */
	boolean underwater;
	/** Between the world shader's two water calls, while the translucent terrain draws with the G-buffer. */
	boolean waterOpen;
	/** Whether the world shader's post pass and the upscaler have run on this frame's world yet; at most once a frame. */
	boolean shaded;
	/** Whether frame generation and EDR output have taken the world yet; at most once a frame. */
	boolean captured;

	WorldFrame(RenderTarget main, CameraRenderState camera, @Nullable SkyRenderState sky, MetalSettings.Upscaling upscaling) {
		this.main = main;
		this.camera = camera;
		this.sky = sky;
		this.upscaling = upscaling;
	}

	/** The unjittered world projection, or null if renderLevel did not upload one. */
	@Nullable Matrix4f projection() {
		return this.projectionCaptured ? this.projection : null;
	}

	/** The depth the world was drawn with: the small target's once upscaled, otherwise main's. */
	GpuTexture depth() {
		TextureTarget target = this.scaled;
		GpuTexture depth = target != null && !this.lent ? target.getDepthTexture() : this.main.getDepthTexture();
		return Objects.requireNonNull(depth, "depth texture");
	}

	/** How far the world was shifted, in pixels of {@link #depth}, as the scaler is told; 0 unless jittered. */
	float toldJitterX() {
		return this.jittered ? this.jitterSignX * this.jitterX : 0.0F;
	}

	float toldJitterY() {
		return this.jittered ? this.jitterSignY * this.jitterY : 0.0F;
	}
}
