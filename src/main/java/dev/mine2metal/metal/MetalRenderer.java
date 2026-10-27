package dev.mine2metal.metal;

import com.mojang.renderpearl.api.textures.GpuTexture;
import org.jspecify.annotations.Nullable;

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

	public static boolean supportsSpatialUpscaling() {
		MetalDevice current = device;
		return current != null && current.spatialUpscalingSupported;
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
