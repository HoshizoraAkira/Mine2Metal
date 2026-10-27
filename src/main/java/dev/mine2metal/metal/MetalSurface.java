package dev.mine2metal.metal;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import dev.mine2metal.Mine2Metal;
import dev.mine2metal.config.MetalSettings;
import org.lwjgl.sdl.SDLMetal;

import java.util.Collection;
import java.util.List;

/**
 * A CAMetalLayer on the SDL window. The drawable is taken at blit time, not at frame start: holding it for the whole
 * frame only adds latency.
 */
final class MetalSurface implements GpuSurfaceBackend {
	private final long ctx;
	private final MetalCommandEncoder encoder;
	private final long view;
	private final long layer;
	private final FrameDump frameDump = new FrameDump();

	MetalSurface(long ctx, MetalCommandEncoder encoder, long window) {
		this.ctx = ctx;
		this.encoder = encoder;
		this.view = SDLMetal.SDL_Metal_CreateView(window);
		this.layer = SDLMetal.SDL_Metal_GetLayer(this.view);
	}

	@Override
	public void configure(GpuSurface.Configuration configuration) {
		boolean vsync = configuration.presentMode() == GpuSurface.PresentMode.FIFO;
		Mine2Metal.LOGGER.debug("Configuring Metal surface {}x{} {}", configuration.width(), configuration.height(), configuration.presentMode());
		MetalNative.layerConfigure(this.ctx, this.layer, configuration.width(), configuration.height(), vsync);
		this.setPerformanceHud(MetalSettings.get().performanceHud);
	}

	void setPerformanceHud(boolean enabled) {
		MetalNative.layerSetHud(this.layer, enabled);
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() {
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		this.frameDump.onPresent(this.ctx, this.encoder, textureView.texture());
		this.encoder.present(this.layer, (MetalTextureView) textureView);
	}

	@Override
	public void present() {
		// Scheduled on the frame's command buffer in blitFromTexture; it happens when submit() commits.
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
	}

	@Override
	public void close() {
		// With vsync off a native thread presents to the layer; it has to stop before the view goes away.
		MetalNative.layerDetach(this.ctx);
		SDLMetal.SDL_Metal_DestroyView(this.view);
	}
}
