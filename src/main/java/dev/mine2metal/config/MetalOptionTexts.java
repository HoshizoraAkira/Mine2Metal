package dev.mine2metal.config;

import dev.mine2metal.metal.MetalRenderer;
import net.minecraft.network.chat.Component;

import java.util.EnumSet;
import java.util.Locale;

/** Option labels and tooltips shared by the Sodium page and the vanilla screen. */
final class MetalOptionTexts {
	private MetalOptionTexts() {
	}

	static Component upscalingName(MetalSettings.Upscaling value) {
		return Component.translatable("mine2metal.options.upscaling." + value.name().toLowerCase(Locale.ROOT));
	}

	static Component upscalingTooltip() {
		if (!MetalRenderer.isActive()) {
			return Component.translatable("mine2metal.options.metal_inactive");
		}
		if (!upscalingAvailable()) {
			return Component.translatable("mine2metal.options.upscaling.unsupported");
		}
		return Component.translatable("mine2metal.options.upscaling.tooltip");
	}

	static boolean upscalingAvailable() {
		return MetalRenderer.supportsSpatialUpscaling() || MetalRenderer.supportsTemporalUpscaling();
	}

	/** The upscaling modes this Mac can run. */
	static EnumSet<MetalSettings.Upscaling> upscalingModes() {
		EnumSet<MetalSettings.Upscaling> modes = EnumSet.of(MetalSettings.Upscaling.OFF);
		if (MetalRenderer.supportsSpatialUpscaling()) {
			modes.add(MetalSettings.Upscaling.SPATIAL);
		}
		if (MetalRenderer.supportsTemporalUpscaling()) {
			modes.add(MetalSettings.Upscaling.TEMPORAL);
		}
		return modes;
	}

	static Component performanceHudTooltip() {
		if (!MetalRenderer.isActive()) {
			return Component.translatable("mine2metal.options.metal_inactive");
		}
		return Component.translatable("mine2metal.options.performance_hud.tooltip");
	}

	static Component percent(int value) {
		return Component.translatable("mine2metal.options.render_scale.value", value);
	}
}
