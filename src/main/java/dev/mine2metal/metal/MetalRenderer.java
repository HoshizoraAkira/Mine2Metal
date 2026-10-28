package dev.mine2metal.metal;

import com.mojang.renderpearl.api.textures.GpuTexture;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.function.Consumer;

/** What the rest of the mod may ask of the Metal backend, without reaching into its internals. */
public final class MetalRenderer {
	private static @Nullable MetalDevice device;

	private MetalRenderer() {
	}

	static void deviceCreated(MetalDevice created) {
		device = created;
	}

	static void deviceClosed(MetalDevice closed) {
		if (device == closed) {
			device = null;
		}
	}

	/** True while the game renders through the Metal backend (and not Mojang's OpenGL or Vulkan one). */
	public static boolean isActive() {
		return device != null;
	}

	/** The Metal version the game renders with (3 or 4), or 0 when Metal is not active. */
	public static int metalVersion() {
		MetalDevice current = device;
		return current != null ? current.metalVersion : 0;
	}

	public static boolean supportsSpatialUpscaling() {
		MetalDevice current = device;
		return current != null && current.spatialUpscalingSupported;
	}

	public static boolean supportsTemporalUpscaling() {
		MetalDevice current = device;
		return current != null && current.temporalUpscalingSupported;
	}

	/** True when the temporal scaler accepts upscaling by this output/input size ratio. */
	public static boolean supportsTemporalRatio(float ratio) {
		MetalDevice current = device;
		return current != null && current.temporalUpscalingSupported && ratio >= current.temporalMinScale - 1.0e-3F
				&& ratio <= current.temporalMaxScale + 1.0e-3F;
	}

	/**
	 * Upscales color onto output with MetalFX temporal scaling; see {@code m2m_metalfx_temporal_upscale} for the matrices.
	 * False if Metal is not active or MetalFX refused these textures.
	 */
	public static boolean upscaleTemporal(GpuTexture color, GpuTexture depth, GpuTexture output, float[] matrices, float jitterX, float jitterY,
			boolean reset) {
		MetalDevice current = device;
		return current != null && current.encoder().upscaleTemporal((MetalTexture) color, (MetalTexture) depth, (MetalTexture) output, matrices, jitterX,
				jitterY, reset);
	}

	public static boolean supportsFrameInterpolation() {
		MetalDevice current = device;
		return current != null && current.frameInterpolationSupported;
	}

	/**
	 * Captures this frame's world for frame interpolation; see {@code m2m_frame_interpolation_capture} for the
	 * arguments. False if Metal is not active or MetalFX refused these textures.
	 */
	public static boolean captureForInterpolation(GpuTexture color, GpuTexture depth, float[] matrices, float[] camera, boolean reset) {
		MetalDevice current = device;
		return current != null && current.encoder().captureForInterpolation((MetalTexture) color, (MetalTexture) depth, matrices, camera, reset);
	}

	/** Frees what frame interpolation holds, once it is switched off. */
	public static void releaseFrameInterpolation() {
		MetalDevice current = device;
		if (current != null) {
			current.encoder().releaseFrameInterpolation();
		}
	}

	/**
	 * For the game test: writes the generated frame of the next present (width by height RGBA8, the main target's size)
	 * to a PNG, flipped upright like a screenshot. done gets whether there was one, once the file is written.
	 */
	public static void dumpNextInterpolatedFrame(Path file, int width, int height, Consumer<Boolean> done) {
		MetalDevice current = device;
		if (current == null) {
			done.accept(false);
			return;
		}
		current.encoder().dumpNextInterpolatedFrame(file, width, height, done);
	}

	/** Shows or hides the Metal Performance HUD over the game window; does nothing when Metal is not active. */
	public static void setPerformanceHud(boolean enabled) {
		MetalDevice current = device;
		MetalSurface surface = current != null ? current.surface : null;
		if (surface != null) {
			surface.setPerformanceHud(enabled);
		}
	}

	/** Upscales input onto output with MetalFX; false if Metal is not active or MetalFX refused these textures. */
	public static boolean upscaleSpatial(GpuTexture input, GpuTexture output) {
		MetalDevice current = device;
		return current != null && current.encoder().upscaleSpatial((MetalTexture) input, (MetalTexture) output);
	}
}
