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

		CycleButton<Boolean> interpolation = CycleButton.onOffBuilder(settings.frameInterpolation)
				.withTooltip(value -> Tooltip.create(Component.translatable("mine2metal.options.frame_interpolation.tooltip")))
				.create(Component.translatable("mine2metal.options.frame_interpolation"), (button, value) -> {
				});
		interpolation.active = false;

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
		this.list.addHeader(Component.translatable("mine2metal.options.group.upscaling"));
		this.list.addSmall(upscaling, scale);
		this.list.addHeader(Component.translatable("mine2metal.options.group.frame_generation"));
		this.list.addSmall(interpolation, null);
		this.list.addHeader(Component.translatable("mine2metal.options.group.diagnostics"));
		this.list.addSmall(performanceHud, null);
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
}
