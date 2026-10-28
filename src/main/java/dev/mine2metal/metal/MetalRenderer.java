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
	 * Upscales color onto output with MetalFX temporal scaling, or with FSR 3.1; see {@code m2m_temporal_upscale} for the
	 * matrices and the camera values. With reactive, moving entities and particles are marked so the scaler does not
	 * smear them. False if Metal is not active or the scaler refused these textures.
	 */
	public static boolean upscaleTemporal(boolean fsr, GpuTexture color, GpuTexture depth, GpuTexture output, float[] matrices, float[] camera,
			float jitterX, float jitterY, boolean reset, boolean reactive) {
		MetalDevice current = device;
		return current != null && current.encoder().upscaleTemporal(fsr, (MetalTexture) color, (MetalTexture) depth, (MetalTexture) output, matrices,
				camera, jitterX, jitterY, reset, reactive);
	}

	public static boolean supportsFrameInterpolation() {
		MetalDevice current = device;
		return current != null && current.frameInterpolationSupported;
	}

	/** Where the frame moves on to another stage, for the GPU time per stage in the stats; nothing otherwise. */
	public static void markStage(String next) {
		MetalDevice current = device;
		if (current != null) {
			current.encoder().markStage(next);
		}
	}

	/** Smoothed GPU milliseconds frame generation took per frame recently; 0 when Metal is not active. */
	public static double frameGenerationGpuMillis() {
		MetalDevice current = device;
		return current != null ? current.encoder().frameGenerationGpuMillis() : 0.0;
	}

	/** How long the last present held the render thread, in seconds, waiting for the display; 0 without Metal. */
	public static double lastPresentWaitSeconds() {
		MetalDevice current = device;
		return current != null ? current.encoder().lastPresentNanos() / 1.0e9 : 0.0;
	}

	/** The main display's refresh rate in Hz. */
	public static int displayRefreshRate() {
		return Math.max(1, MetalNative.displayRefreshRate());
	}

	/** How many frames have reached the screen so far, generated ones included; -1 when Metal is not active. */
	public static long shownFrameCount() {
		MetalDevice current = device;
		return current != null ? current.encoder().shownFrameCount() : -1L;
	}

	/** AMD FSR 3.1 frame generation through the Metal backend (Apple GPUs from the M1 on). */
	public static boolean supportsFsrFrameGeneration() {
		MetalDevice current = device;
		return current != null && current.fsrFrameGenerationSupported;
	}

	/** AMD's upscalers run on the same GPUs as its frame generation. */
	public static boolean supportsFsrUpscaling() {
		return supportsFsrFrameGeneration();
	}

	/**
	 * Captures this frame's world for frame generation by MetalFX or FSR 3.1; see {@code m2m_frame_interpolation_capture}
	 * for the arguments. False if Metal is not active or the engine refused these textures.
	 */
	public static boolean captureForInterpolation(boolean fsr, GpuTexture color, GpuTexture depth, float[] matrices, float[] camera, boolean reset) {
		MetalDevice current = device;
		return current != null
				&& current.encoder().captureForInterpolation(fsr ? 2 : 1, (MetalTexture) color, (MetalTexture) depth, matrices, camera, reset);
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

	/** Whether the main display can show brighter than SDR white (it may lack the headroom at its current brightness). */
	public static boolean supportsHdr() {
		return device != null && MetalNative.hdrSupported();
	}

	/** How many times SDR white the main display can show right now; 1 when it has no headroom left. */
	public static float hdrHeadroom() {
		return device != null ? MetalNative.hdrHeadroom() : 1.0F;
	}

	/** Switches EDR output; does nothing when Metal is not active. */
	public static void setHdr(boolean enabled, int brightnessPercent) {
		MetalDevice current = device;
		MetalSurface surface = current != null ? current.surface : null;
		if (surface != null) {
			surface.setHdr(enabled, brightnessPercent);
		}
	}

	/** With EDR on: this frame's world before the hand and the GUI, so they can be kept at SDR brightness. */
	public static void captureWorldForHdr(GpuTexture color) {
		MetalDevice current = device;
		if (current != null) {
			current.encoder().captureWorldForHdr((MetalTexture) color);
		}
	}

	/**
	 * For the game test: the RGBA16Float frame the next present composes (width by height, the main target's size), as
	 * RGBA floats in bottom-up rows, or null if it composed none.
	 */
	public static void readNextHdrFrame(int width, int height, Consumer<float @Nullable []> done) {
		MetalDevice current = device;
		if (current == null) {
			done.accept(null);
			return;
		}
		current.encoder().readNextHdrFrame(width, height, done);
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

	/** Upscales input onto output with FSR 1, sharpening by sharpness (0 sharpest to 2); false as for {@link #upscaleSpatial}. */
	public static boolean upscaleFsr1(GpuTexture input, GpuTexture output, float sharpness) {
		MetalDevice current = device;
		return current != null && current.encoder().upscaleFsr1((MetalTexture) input, (MetalTexture) output, sharpness);
	}
}
