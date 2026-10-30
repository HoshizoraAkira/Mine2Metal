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
	private static final Identifier FRAME_GENERATION = Identifier.fromNamespaceAndPath("mine2metal", "frame_generation");
	private static final Identifier HDR = Identifier.fromNamespaceAndPath("mine2metal", "hdr");
	private static final Identifier HDR_BRIGHTNESS = Identifier.fromNamespaceAndPath("mine2metal", "hdr_brightness");
	private static final Identifier PERFORMANCE_HUD = Identifier.fromNamespaceAndPath("mine2metal", "performance_hud");
	private static final Identifier SHADER_PRESET = Identifier.fromNamespaceAndPath("mine2metal", "shader_preset");
	private static final Identifier SHADER_LIGHTING_SCALE = Identifier.fromNamespaceAndPath("mine2metal", "shader_lighting_scale");
	private static final Identifier SHADER_SHADOWS = Identifier.fromNamespaceAndPath("mine2metal", "shader_shadows");
	private static final Identifier SHADER_AO = Identifier.fromNamespaceAndPath("mine2metal", "shader_ao");
	private static final Identifier SHADER_REFLECTIONS = Identifier.fromNamespaceAndPath("mine2metal", "shader_reflections");
	private static final Identifier SHADER_GI = Identifier.fromNamespaceAndPath("mine2metal", "shader_gi");
	private static final Identifier SHADER_VOLUMETRICS = Identifier.fromNamespaceAndPath("mine2metal", "shader_volumetrics");
	private static final Identifier SHADER_BLOOM = Identifier.fromNamespaceAndPath("mine2metal", "shader_bloom");

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
								.addOption(builder.createEnumOption(FRAME_GENERATION, MetalSettings.FrameGeneration.class)
										.setName(Component.translatable("mine2metal.options.frame_generation"))
										.setTooltip(value -> MetalOptionTexts.frameGenerationTooltip())
										.setElementNameProvider(MetalOptionTexts::frameGenerationName)
										.setAllowedValues(MetalOptionTexts.frameGenerationModes())
										.setImpact(OptionImpact.MEDIUM)
										.setEnabled(MetalOptionTexts.frameGenerationAvailable())
										.setStorageHandler(settings::save)
										.setDefaultValue(MetalSettings.FrameGeneration.OFF)
										.setBinding(value -> settings.frameGeneration = value, () -> settings.frameGeneration)))
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
										}, () -> settings.performanceHud))))
				.addPage(builder.createOptionPage()
						.setName(Component.translatable("mine2metal.options.shaders_page"))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.shader_general"))
								.addOption(builder.createEnumOption(SHADER_PRESET, MetalSettings.ShaderPreset.class)
										.setName(Component.translatable("mine2metal.options.shader_preset"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_preset.tooltip"))
										.setElementNameProvider(MetalOptionTexts::shaderPresetName)
										.setImpact(OptionImpact.HIGH)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(settings::save)
										.setDefaultValue(MetalSettings.ShaderPreset.OFF)
										.setBinding(settings::applyShaderPreset, () -> settings.shaderPreset))
								.addOption(builder.createIntegerOption(SHADER_LIGHTING_SCALE)
										.setName(Component.translatable("mine2metal.options.shader_lighting_scale"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_lighting_scale.tooltip"))
										.setRange(MetalSettings.MIN_SHADER_LIGHTING_SCALE, MetalSettings.MAX_SHADER_LIGHTING_SCALE,
												MetalSettings.SHADER_LIGHTING_SCALE_STEP)
										.setValueFormatter(MetalOptionTexts::percent)
										.setImpact(OptionImpact.HIGH)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(settings::save)
										.setDefaultValue(75)
										.setBinding(value -> {
											settings.shaderLightingScale = MetalSettings.clampShaderLightingScale(value);
											settings.syncShaderPreset();
										}, () -> settings.shaderLightingScale)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.shader_lighting"))
								.addOption(builder.createEnumOption(SHADER_SHADOWS, MetalSettings.ShadowQuality.class)
										.setName(Component.translatable("mine2metal.options.shader_shadows"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_shadows.tooltip"))
										.setElementNameProvider(MetalOptionTexts::shadowQualityName)
										.setImpact(OptionImpact.HIGH)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(settings::save)
										.setDefaultValue(MetalSettings.ShadowQuality.OFF)
										.setBinding(value -> {
											settings.shaderShadows = value;
											settings.syncShaderPreset();
										}, () -> settings.shaderShadows))
								.addOption(builder.createEnumOption(SHADER_GI, MetalSettings.GiQuality.class)
										.setName(Component.translatable("mine2metal.options.shader_gi"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_gi.tooltip"))
										.setElementNameProvider(MetalOptionTexts::giQualityName)
										.setImpact(OptionImpact.HIGH)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(settings::save)
										.setDefaultValue(MetalSettings.GiQuality.OFF)
										.setBinding(value -> {
											settings.shaderGi = value;
											settings.syncShaderPreset();
										}, () -> settings.shaderGi))
								.addOption(builder.createEnumOption(SHADER_AO, MetalSettings.AoQuality.class)
										.setName(Component.translatable("mine2metal.options.shader_ao"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_ao.tooltip"))
										.setElementNameProvider(MetalOptionTexts::aoQualityName)
										.setImpact(OptionImpact.MEDIUM)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(settings::save)
										.setDefaultValue(MetalSettings.AoQuality.OFF)
										.setBinding(value -> {
											settings.shaderAo = value;
											settings.syncShaderPreset();
										}, () -> settings.shaderAo)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.shader_atmosphere"))
								.addOption(builder.createEnumOption(SHADER_REFLECTIONS, MetalSettings.ReflectionQuality.class)
										.setName(Component.translatable("mine2metal.options.shader_reflections"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_reflections.tooltip"))
										.setElementNameProvider(MetalOptionTexts::reflectionQualityName)
										.setImpact(OptionImpact.HIGH)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(settings::save)
										.setDefaultValue(MetalSettings.ReflectionQuality.OFF)
										.setBinding(value -> {
											settings.shaderReflections = value;
											settings.syncShaderPreset();
										}, () -> settings.shaderReflections))
								.addOption(builder.createEnumOption(SHADER_VOLUMETRICS, MetalSettings.VolumetricQuality.class)
										.setName(Component.translatable("mine2metal.options.shader_volumetrics"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_volumetrics.tooltip"))
										.setElementNameProvider(MetalOptionTexts::volumetricQualityName)
										.setImpact(OptionImpact.MEDIUM)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(settings::save)
										.setDefaultValue(MetalSettings.VolumetricQuality.OFF)
										.setBinding(value -> {
											settings.shaderVolumetrics = value;
											settings.syncShaderPreset();
										}, () -> settings.shaderVolumetrics))
								.addOption(builder.createEnumOption(SHADER_BLOOM, MetalSettings.BloomQuality.class)
										.setName(Component.translatable("mine2metal.options.shader_bloom"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_bloom.tooltip"))
										.setElementNameProvider(MetalOptionTexts::bloomQualityName)
										.setImpact(OptionImpact.LOW)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(settings::save)
										.setDefaultValue(MetalSettings.BloomQuality.OFF)
										.setBinding(value -> {
											settings.shaderBloom = value;
											settings.syncShaderPreset();
										}, () -> settings.shaderBloom))));
	}
}
