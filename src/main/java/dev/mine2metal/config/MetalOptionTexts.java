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

	static Component metalVersionName(MetalSettings.MetalVersion value) {
		return Component.translatable("mine2metal.options.metal_version." + value.name().toLowerCase(Locale.ROOT));
	}

	/** Explains the choice and names the version running now, which only changes after a restart. */
	static Component metalVersionTooltip() {
		int running = MetalRenderer.metalVersion();
		Component current = running == 0 ? Component.translatable("mine2metal.options.metal_inactive")
				: Component.translatable("mine2metal.options.metal_version.running", running);
		return Component.translatable("mine2metal.options.metal_version.tooltip").append("\n\n").append(current);
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

	static Component frameInterpolationTooltip() {
		if (!MetalRenderer.isActive()) {
			return Component.translatable("mine2metal.options.metal_inactive");
		}
		if (!MetalRenderer.supportsFrameInterpolation()) {
			return Component.translatable("mine2metal.options.frame_interpolation.unsupported");
		}
		return Component.translatable("mine2metal.options.frame_interpolation.tooltip");
	}

	/** Explains EDR and says how much brighter than SDR white the display can go at its current brightness. */
	static Component hdrTooltip() {
		if (!MetalRenderer.isActive()) {
			return Component.translatable("mine2metal.options.metal_inactive");
		}
		if (!MetalRenderer.supportsHdr()) {
			return Component.translatable("mine2metal.options.hdr.unsupported");
		}
		String headroom = String.format(Locale.ROOT, "%.1f", MetalRenderer.hdrHeadroom());
		return Component.translatable("mine2metal.options.hdr.tooltip").append("\n\n")
				.append(Component.translatable("mine2metal.options.hdr.headroom", headroom));
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
