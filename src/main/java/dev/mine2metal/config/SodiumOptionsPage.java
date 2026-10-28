package dev.mine2metal.config;

import dev.mine2metal.metal.MetalRenderer;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;


/**
 * The "Metal" page in Sodium's video settings, registered through the {@code sodium:config_api_user} entrypoint. Only
 * loaded when Sodium is installed; without it {@link MetalSettingsScreen} offers the same options.
 */
public final class SodiumOptionsPage implements ConfigEntryPoint {
	private static final Identifier METAL_VERSION = Identifier.fromNamespaceAndPath("mine2metal", "metal_version");
	private static final Identifier UPSCALING = Identifier.fromNamespaceAndPath("mine2metal", "upscaling");
	private static final Identifier RENDER_SCALE = Identifier.fromNamespaceAndPath("mine2metal", "render_scale");
	private static final Identifier FRAME_INTERPOLATION = Identifier.fromNamespaceAndPath("mine2metal", "frame_interpolation");
	private static final Identifier HDR = Identifier.fromNamespaceAndPath("mine2metal", "hdr");
	private static final Identifier HDR_BRIGHTNESS = Identifier.fromNamespaceAndPath("mine2metal", "hdr_brightness");
	private static final Identifier PERFORMANCE_HUD = Identifier.fromNamespaceAndPath("mine2metal", "performance_hud");

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		MetalSettings settings = MetalSettings.get();

		builder.registerOwnModOptions()
				.setName("mine2metal")
				.addPage(builder.createOptionPage()
						.setName(Component.translatable("mine2metal.options.page"))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.api"))
								.addOption(builder.createEnumOption(METAL_VERSION, MetalSettings.MetalVersion.class)
										.setName(Component.translatable("mine2metal.options.metal_version"))
										.setTooltip(value -> MetalOptionTexts.metalVersionTooltip())
										.setElementNameProvider(MetalOptionTexts::metalVersionName)
										.setImpact(OptionImpact.LOW)
										.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
										.setStorageHandler(settings::save)
										.setDefaultValue(MetalSettings.MetalVersion.AUTO)
										.setBinding(value -> settings.metalVersion = value, () -> settings.metalVersion)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.upscaling"))
								.addOption(builder.createEnumOption(UPSCALING, MetalSettings.Upscaling.class)
										.setName(Component.translatable("mine2metal.options.upscaling"))
										.setTooltip(value -> MetalOptionTexts.upscalingTooltip())
										.setElementNameProvider(MetalOptionTexts::upscalingName)
										.setAllowedValues(MetalOptionTexts.upscalingModes())
										.setImpact(OptionImpact.VARIES)
										.setEnabled(MetalOptionTexts.upscalingAvailable())
										.setStorageHandler(settings::save)
										.setDefaultValue(MetalSettings.Upscaling.OFF)
										.setBinding(value -> settings.upscaling = value, () -> settings.upscaling))
								.addOption(builder.createIntegerOption(RENDER_SCALE)
										.setName(Component.translatable("mine2metal.options.render_scale"))
										.setTooltip(Component.translatable("mine2metal.options.render_scale.tooltip"))
										.setRange(MetalSettings.MIN_RENDER_SCALE, MetalSettings.MAX_RENDER_SCALE, MetalSettings.RENDER_SCALE_STEP)
										.setValueFormatter(MetalOptionTexts::percent)
										.setImpact(OptionImpact.HIGH)
										.setEnabledProvider(state -> MetalOptionTexts.upscalingAvailable()
												&& state.readEnumOption(UPSCALING, MetalSettings.Upscaling.class) != MetalSettings.Upscaling.OFF, UPSCALING)
										.setStorageHandler(settings::save)
										.setDefaultValue(75)
										.setBinding(value -> settings.renderScale = MetalSettings.clampScale(value), () -> settings.renderScale)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.frame_generation"))
								.addOption(builder.createBooleanOption(FRAME_INTERPOLATION)
										.setName(Component.translatable("mine2metal.options.frame_interpolation"))
										.setTooltip(value -> MetalOptionTexts.frameInterpolationTooltip())
										.setImpact(OptionImpact.MEDIUM)
										.setEnabled(MetalRenderer.supportsFrameInterpolation())
										.setStorageHandler(settings::save)
										.setDefaultValue(false)
										.setBinding(value -> settings.frameInterpolation = value, () -> settings.frameInterpolation)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.hdr"))
								.addOption(builder.createBooleanOption(HDR)
										.setName(Component.translatable("mine2metal.options.hdr"))
										.setTooltip(value -> MetalOptionTexts.hdrTooltip())
										.setImpact(OptionImpact.LOW)
										.setEnabled(MetalRenderer.supportsHdr())
										.setStorageHandler(settings::save)
										.setDefaultValue(false)
										.setBinding(value -> {
											settings.hdr = value;
											MetalRenderer.setHdr(value, settings.hdrBrightness);
										}, () -> settings.hdr))
								.addOption(builder.createIntegerOption(HDR_BRIGHTNESS)
										.setName(Component.translatable("mine2metal.options.hdr_brightness"))
										.setTooltip(Component.translatable("mine2metal.options.hdr_brightness.tooltip"))
										.setRange(MetalSettings.MIN_HDR_BRIGHTNESS, MetalSettings.MAX_HDR_BRIGHTNESS, MetalSettings.HDR_BRIGHTNESS_STEP)
										.setValueFormatter(MetalOptionTexts::percent)
										.setImpact(OptionImpact.LOW)
										.setEnabledProvider(state -> MetalRenderer.supportsHdr() && state.readBooleanOption(HDR), HDR)
										.setStorageHandler(settings::save)
										.setDefaultValue(200)
										.setBinding(value -> {
											settings.hdrBrightness = MetalSettings.clampHdrBrightness(value);
											MetalRenderer.setHdr(settings.hdr, settings.hdrBrightness);
										}, () -> settings.hdrBrightness)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.diagnostics"))
								.addOption(builder.createBooleanOption(PERFORMANCE_HUD)
										.setName(Component.translatable("mine2metal.options.performance_hud"))
										.setTooltip(value -> MetalOptionTexts.performanceHudTooltip())
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(settings::save)
										.setDefaultValue(false)
										.setBinding(value -> {
											settings.performanceHud = value;
											MetalRenderer.setPerformanceHud(value);
										}, () -> settings.performanceHud))));
	}
}
