package dev.mine2metal.config;

import dev.mine2metal.metal.MetalRenderer;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** The same options as {@link SodiumOptionsPage}, for players without Sodium; opened from vanilla's video settings. */
public final class MetalSettingsScreen extends OptionsSubScreen {
	private static final int WIDGET_WIDTH = 150;
	private static final int WIDGET_HEIGHT = 20;

	public MetalSettingsScreen(Screen lastScreen, Options options) {
		super(lastScreen, options, Component.translatable("mine2metal.screen.title"));
	}

	@Override
	protected void addOptions() {
		MetalSettings settings = MetalSettings.get();
		boolean upscalingAvailable = MetalOptionTexts.upscalingAvailable();

		RenderScaleSlider scale = new RenderScaleSlider(settings);
		scale.active = upscalingAvailable && settings.upscaling != MetalSettings.Upscaling.OFF;

		CycleButton<MetalSettings.Upscaling> upscaling = CycleButton.builder(MetalOptionTexts::upscalingName, settings.upscaling)
				.withValues(MetalOptionTexts.upscalingModes())
				.withTooltip(value -> Tooltip.create(MetalOptionTexts.upscalingTooltip()))
				.create(Component.translatable("mine2metal.options.upscaling"), (button, value) -> {
					settings.upscaling = value;
					settings.save();
					scale.active = value != MetalSettings.Upscaling.OFF;
				});
		upscaling.active = upscalingAvailable;

		CycleButton<MetalSettings.FrameGeneration> interpolation = CycleButton.builder(MetalOptionTexts::frameGenerationName, settings.frameGeneration)
				.withValues(MetalOptionTexts.frameGenerationModes())
				.withTooltip(value -> Tooltip.create(MetalOptionTexts.frameGenerationTooltip()))
				.create(Component.translatable("mine2metal.options.frame_generation"), (button, value) -> {
					settings.frameGeneration = value;
					settings.save();
				});
		interpolation.active = MetalOptionTexts.frameGenerationAvailable();

		HdrBrightnessSlider hdrBrightness = new HdrBrightnessSlider(settings);
		hdrBrightness.active = MetalRenderer.supportsHdr() && settings.hdr;
		CycleButton<Boolean> hdr = CycleButton.onOffBuilder(settings.hdr)
				.withTooltip(value -> Tooltip.create(MetalOptionTexts.hdrTooltip()))
				.create(Component.translatable("mine2metal.options.hdr"), (button, value) -> {
					settings.hdr = value;
					settings.save();
					MetalRenderer.setHdr(value, settings.hdrBrightness);
					hdrBrightness.active = value;
				});
		hdr.active = MetalRenderer.supportsHdr();

		CycleButton<Boolean> performanceHud = CycleButton.onOffBuilder(settings.performanceHud)
				.withTooltip(value -> Tooltip.create(MetalOptionTexts.performanceHudTooltip()))
				.create(Component.translatable("mine2metal.options.performance_hud"), (button, value) -> {
					settings.performanceHud = value;
					settings.save();
					MetalRenderer.setPerformanceHud(value);
				});
		performanceHud.active = MetalRenderer.isActive();

		CycleButton<MetalSettings.MetalVersion> metalVersion = CycleButton.builder(MetalOptionTexts::metalVersionName, settings.metalVersion)
				.withValues(MetalSettings.MetalVersion.values())
				.withTooltip(value -> Tooltip.create(MetalOptionTexts.metalVersionTooltip()))
				.create(Component.translatable("mine2metal.options.metal_version"), (button, value) -> {
					settings.metalVersion = value;
					settings.save();
				});

		this.list.addHeader(Component.translatable("mine2metal.options.group.api"));
		this.list.addSmall(metalVersion, null);
		this.list.addHeader(Component.translatable("mine2metal.options.shaders_page"));
		this.addShaderOptions(settings);
		this.list.addHeader(Component.translatable("mine2metal.options.group.upscaling"));
		this.list.addSmall(upscaling, scale);
		this.list.addHeader(Component.translatable("mine2metal.options.group.frame_generation"));
		this.list.addSmall(interpolation, null);
		this.list.addHeader(Component.translatable("mine2metal.options.group.hdr"));
		this.list.addSmall(hdr, hdrBrightness);
		this.list.addHeader(Component.translatable("mine2metal.options.group.diagnostics"));
		this.list.addSmall(performanceHud, null);
	}

	private void addShaderOptions(MetalSettings settings) {
		boolean active = MetalRenderer.isActive();
		@SuppressWarnings("unchecked")
		CycleButton<MetalSettings.ShaderPreset>[] presetRef = new CycleButton[1];

		ShaderLightingScaleSlider lightingScale = new ShaderLightingScaleSlider(settings, () -> {
			if (presetRef[0] != null) {
				presetRef[0].setValue(settings.shaderPreset);
			}
		});
		lightingScale.active = active;

		CycleButton<MetalSettings.ShadowQuality> shadows = CycleButton.builder(MetalOptionTexts::shadowQualityName, settings.shaderShadows)
				.withValues(MetalSettings.ShadowQuality.values())
				.withTooltip(value -> Tooltip.create(MetalOptionTexts.shaderTooltip("mine2metal.options.shader_shadows.tooltip")))
				.create(Component.translatable("mine2metal.options.shader_shadows"), (button, value) -> {
					settings.shaderShadows = value;
					settings.syncShaderPreset();
					settings.save();
					if (presetRef[0] != null) {
						presetRef[0].setValue(settings.shaderPreset);
					}
				});
		shadows.active = active;

		CycleButton<MetalSettings.GiQuality> gi = CycleButton.builder(MetalOptionTexts::giQualityName, settings.shaderGi)
				.withValues(MetalSettings.GiQuality.values())
				.withTooltip(value -> Tooltip.create(MetalOptionTexts.shaderTooltip("mine2metal.options.shader_gi.tooltip")))
				.create(Component.translatable("mine2metal.options.shader_gi"), (button, value) -> {
					settings.shaderGi = value;
					settings.syncShaderPreset();
					settings.save();
					if (presetRef[0] != null) {
						presetRef[0].setValue(settings.shaderPreset);
					}
				});
		gi.active = active;

		CycleButton<MetalSettings.AoQuality> ao = CycleButton.builder(MetalOptionTexts::aoQualityName, settings.shaderAo)
				.withValues(MetalSettings.AoQuality.values())
				.withTooltip(value -> Tooltip.create(MetalOptionTexts.shaderTooltip("mine2metal.options.shader_ao.tooltip")))
				.create(Component.translatable("mine2metal.options.shader_ao"), (button, value) -> {
					settings.shaderAo = value;
					settings.syncShaderPreset();
					settings.save();
					if (presetRef[0] != null) {
						presetRef[0].setValue(settings.shaderPreset);
					}
				});
		ao.active = active;

		CycleButton<MetalSettings.ReflectionQuality> reflections = CycleButton.builder(MetalOptionTexts::reflectionQualityName, settings.shaderReflections)
				.withValues(MetalSettings.ReflectionQuality.values())
				.withTooltip(value -> Tooltip.create(MetalOptionTexts.shaderTooltip("mine2metal.options.shader_reflections.tooltip")))
				.create(Component.translatable("mine2metal.options.shader_reflections"), (button, value) -> {
					settings.shaderReflections = value;
					settings.syncShaderPreset();
					settings.save();
					if (presetRef[0] != null) {
						presetRef[0].setValue(settings.shaderPreset);
					}
				});
		reflections.active = active;

		CycleButton<MetalSettings.VolumetricQuality> volumetrics = CycleButton.builder(MetalOptionTexts::volumetricQualityName, settings.shaderVolumetrics)
				.withValues(MetalSettings.VolumetricQuality.values())
				.withTooltip(value -> Tooltip.create(MetalOptionTexts.shaderTooltip("mine2metal.options.shader_volumetrics.tooltip")))
				.create(Component.translatable("mine2metal.options.shader_volumetrics"), (button, value) -> {
					settings.shaderVolumetrics = value;
					settings.syncShaderPreset();
					settings.save();
					if (presetRef[0] != null) {
						presetRef[0].setValue(settings.shaderPreset);
					}
				});
		volumetrics.active = active;

		CycleButton<MetalSettings.BloomQuality> bloom = CycleButton.builder(MetalOptionTexts::bloomQualityName, settings.shaderBloom)
				.withValues(MetalSettings.BloomQuality.values())
				.withTooltip(value -> Tooltip.create(MetalOptionTexts.shaderTooltip("mine2metal.options.shader_bloom.tooltip")))
				.create(Component.translatable("mine2metal.options.shader_bloom"), (button, value) -> {
					settings.shaderBloom = value;
					settings.syncShaderPreset();
					settings.save();
					if (presetRef[0] != null) {
						presetRef[0].setValue(settings.shaderPreset);
					}
				});
		bloom.active = active;

		CycleButton<MetalSettings.ShaderPreset> preset = CycleButton.builder(MetalOptionTexts::shaderPresetName, settings.shaderPreset)
				.withValues(MetalSettings.ShaderPreset.values())
				.withTooltip(value -> Tooltip.create(MetalOptionTexts.shaderTooltip("mine2metal.options.shader_preset.tooltip")))
				.create(Component.translatable("mine2metal.options.shader_preset"), (button, value) -> {
					settings.applyShaderPreset(value);
					settings.save();
					shadows.setValue(settings.shaderShadows);
					gi.setValue(settings.shaderGi);
					ao.setValue(settings.shaderAo);
					reflections.setValue(settings.shaderReflections);
					volumetrics.setValue(settings.shaderVolumetrics);
					bloom.setValue(settings.shaderBloom);
					lightingScale.syncFromSettings();
				});
		preset.active = active;
		presetRef[0] = preset;

		this.list.addSmall(preset, lightingScale);
		this.list.addSmall(shadows, gi);
		this.list.addSmall(ao, reflections);
		this.list.addSmall(volumetrics, bloom);
	}

	private static final class HdrBrightnessSlider extends AbstractSliderButton {
		private final MetalSettings settings;

		HdrBrightnessSlider(MetalSettings settings) {
			super(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Component.empty(), toSlider(settings.hdrBrightness));
			this.settings = settings;
			this.setTooltip(Tooltip.create(Component.translatable("mine2metal.options.hdr_brightness.tooltip")));
			this.updateMessage();
		}

		private static double toSlider(int percent) {
			return (percent - MetalSettings.MIN_HDR_BRIGHTNESS) / (double) (MetalSettings.MAX_HDR_BRIGHTNESS - MetalSettings.MIN_HDR_BRIGHTNESS);
		}

		private int percent() {
			return MetalSettings.clampHdrBrightness((int) Math.round(
					MetalSettings.MIN_HDR_BRIGHTNESS + this.value * (MetalSettings.MAX_HDR_BRIGHTNESS - MetalSettings.MIN_HDR_BRIGHTNESS)));
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.translatable("options.generic_value", Component.translatable("mine2metal.options.hdr_brightness"),
					MetalOptionTexts.percent(this.percent())));
		}

		@Override
		protected void applyValue() {
			this.settings.hdrBrightness = this.percent();
			this.settings.save();
			MetalRenderer.setHdr(this.settings.hdr, this.settings.hdrBrightness);
		}
	}

	private static final class RenderScaleSlider extends AbstractSliderButton {
		private final MetalSettings settings;

		RenderScaleSlider(MetalSettings settings) {
			super(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Component.empty(), toSlider(settings.renderScale));
			this.settings = settings;
			this.setTooltip(Tooltip.create(Component.translatable("mine2metal.options.render_scale.tooltip")));
			this.updateMessage();
		}

		private static double toSlider(int percent) {
			return (percent - MetalSettings.MIN_RENDER_SCALE) / (double) (MetalSettings.MAX_RENDER_SCALE - MetalSettings.MIN_RENDER_SCALE);
		}

		private int percent() {
			return MetalSettings.clampScale((int) Math.round(
					MetalSettings.MIN_RENDER_SCALE + this.value * (MetalSettings.MAX_RENDER_SCALE - MetalSettings.MIN_RENDER_SCALE)));
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.translatable("options.generic_value", Component.translatable("mine2metal.options.render_scale"),
					MetalOptionTexts.percent(this.percent())));
		}

		@Override
		protected void applyValue() {
			this.settings.renderScale = this.percent();
			this.settings.save();
		}
	}

	private static final class ShaderLightingScaleSlider extends AbstractSliderButton {
		private final MetalSettings settings;
		private final Runnable onChanged;

		ShaderLightingScaleSlider(MetalSettings settings, Runnable onChanged) {
			super(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, Component.empty(), toSlider(settings.shaderLightingScale));
			this.settings = settings;
			this.onChanged = onChanged;
			this.setTooltip(Tooltip.create(MetalOptionTexts.shaderTooltip("mine2metal.options.shader_lighting_scale.tooltip")));
			this.updateMessage();
		}

		private static double toSlider(int percent) {
			return (percent - MetalSettings.MIN_SHADER_LIGHTING_SCALE)
					/ (double) (MetalSettings.MAX_SHADER_LIGHTING_SCALE - MetalSettings.MIN_SHADER_LIGHTING_SCALE);
		}

		private int percent() {
			return MetalSettings.clampShaderLightingScale((int) Math.round(
					MetalSettings.MIN_SHADER_LIGHTING_SCALE
							+ this.value * (MetalSettings.MAX_SHADER_LIGHTING_SCALE - MetalSettings.MIN_SHADER_LIGHTING_SCALE)));
		}

		void syncFromSettings() {
			this.value = toSlider(this.settings.shaderLightingScale);
			this.updateMessage();
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.translatable("options.generic_value", Component.translatable("mine2metal.options.shader_lighting_scale"),
					MetalOptionTexts.percent(this.percent())));
		}

		@Override
		protected void applyValue() {
			this.settings.shaderLightingScale = this.percent();
			this.settings.syncShaderPreset();
			this.settings.save();
			this.onChanged.run();
		}
	}
}
