package dev.mine2metal.metal;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.mine2metal.Mine2Metal;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

/** Metal implementation of renderpearl's backend entry point, tried before Mojang's OpenGL and Vulkan backends. */
public final class MetalBackend implements GpuBackend {
	@Override
	public String getName() {
		return "Metal";
	}

	@Override
	public void loadLibrary() throws BackendCreationException {
		MetalNative.load();
	}

	@Override
	public void unloadLibrary() {
		// The dylib stays mapped for the life of the JVM; there is nothing to undo.
	}

	@Override
	public long createWindow(@Nullable String title, int width, int height, long flags) {
		return SDLVideo.SDL_CreateWindow(title, width, height, flags | SDLVideo.SDL_WINDOW_METAL);
	}

	/** The configured version, or Metal 4 by default, where this Mac has it. */
	private static boolean useMetal4(long device, String name) {
		boolean supported = MetalNative.deviceSupportsMetal4(device);
		return switch (Mine2Metal.requestedMetalVersion()) {
			case AUTO -> supported;
			case METAL_3 -> false;
			case METAL_4 -> {
				if (!supported) {
					Mine2Metal.LOGGER.warn("Metal 4 was requested, but {} on this macOS only supports Metal 3", name);
				}
				yield supported;
			}
		};
	}

	@Override
	public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
		long device = MetalNative.deviceCreate(debugOptions.useValidationLayers());
		if (device == 0L) {
			throw new BackendCreationException("No Metal device available", BackendCreationException.Reason.PLATFORM_ERROR);
		}

		try {
			String name = MetalNative.deviceName(device);
			if (!MetalNative.deviceSupportsMetal3(device)) {
				throw new BackendCreationException(name + " does not support Metal 3", BackendCreationException.Reason.PLATFORM_ERROR);
			}
			boolean validation = MetalNative.deviceValidationActive(device);
			if (debugOptions.useValidationLayers() && !validation) {
				Mine2Metal.LOGGER.warn("Metal API validation was requested but could not be enabled; set MTL_DEBUG_LAYER=1 before launching instead");
			}
			boolean debug = validation || debugOptions.logLevel() > 0;
			boolean metal4 = useMetal4(device, name);
			long ctx;
			try {
				ctx = MetalNative.contextCreate(device, debug, metal4);
			} catch (BackendCreationException e) {
				if (!metal4) {
					throw e;
				}
				Mine2Metal.LOGGER.warn("Could not set up Metal 4, using Metal 3: {}", e.getMessage());
				metal4 = false;
				ctx = MetalNative.contextCreate(device, debug, false);
			}
			int version = metal4 ? 4 : 3;
			Mine2Metal.LOGGER.info("Rendering with Metal {} on {}{}", version, name, validation ? " with API validation" : "");
			MetalDevice metal = new MetalDevice(ctx, name, validation, debugOptions.useLabels(), version);
			MetalRenderer.deviceCreated(metal);
			return new FrontendGpuDevice(metal);
		} finally {
			// The context holds its own reference to the device.
			MetalNative.release(device);
		}
	}
}
