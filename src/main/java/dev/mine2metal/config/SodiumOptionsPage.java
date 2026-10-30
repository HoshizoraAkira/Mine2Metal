package dev.mine2metal.config;

import dev.mine2metal.metal.MetalRenderer;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
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
		// The bindings look the settings up on each call rather than keeping the instance from registration, so they
		// keep working after MetalSettings.reload() (the game tests reload while checking the config file).
		StorageEventHandler save = () -> MetalSettings.get().save();

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
										.setStorageHandler(save)
										.setDefaultValue(MetalSettings.MetalVersion.AUTO)
										.setBinding(value -> MetalSettings.get().metalVersion = value, () -> MetalSettings.get().metalVersion)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.upscaling"))
								.addOption(builder.createEnumOption(UPSCALING, MetalSettings.Upscaling.class)
										.setName(Component.translatable("mine2metal.options.upscaling"))
										.setTooltip(value -> MetalOptionTexts.upscalingTooltip())
										.setElementNameProvider(MetalOptionTexts::upscalingName)
										.setAllowedValues(MetalOptionTexts.upscalingModes())
										.setImpact(OptionImpact.VARIES)
										.setEnabled(MetalOptionTexts.upscalingAvailable())
										.setStorageHandler(save)
										.setDefaultValue(MetalSettings.Upscaling.OFF)
										.setBinding(value -> MetalSettings.get().upscaling = value, () -> MetalSettings.get().upscaling))
								.addOption(builder.createIntegerOption(RENDER_SCALE)
										.setName(Component.translatable("mine2metal.options.render_scale"))
										.setTooltip(Component.translatable("mine2metal.options.render_scale.tooltip"))
										.setRange(MetalSettings.MIN_RENDER_SCALE, MetalSettings.MAX_RENDER_SCALE, MetalSettings.RENDER_SCALE_STEP)
										.setValueFormatter(MetalOptionTexts::percent)
										.setImpact(OptionImpact.HIGH)
										.setEnabledProvider(state -> MetalOptionTexts.upscalingAvailable()
												&& state.readEnumOption(UPSCALING, MetalSettings.Upscaling.class) != MetalSettings.Upscaling.OFF, UPSCALING)
										.setStorageHandler(save)
										.setDefaultValue(75)
										.setBinding(value -> MetalSettings.get().renderScale = MetalSettings.clampScale(value), () -> MetalSettings.get().renderScale)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.frame_generation"))
								.addOption(builder.createEnumOption(FRAME_GENERATION, MetalSettings.FrameGeneration.class)
										.setName(Component.translatable("mine2metal.options.frame_generation"))
										.setTooltip(value -> MetalOptionTexts.frameGenerationTooltip())
										.setElementNameProvider(MetalOptionTexts::frameGenerationName)
										.setAllowedValues(MetalOptionTexts.frameGenerationModes())
										.setImpact(OptionImpact.MEDIUM)
										.setEnabled(MetalOptionTexts.frameGenerationAvailable())
										.setStorageHandler(save)
										.setDefaultValue(MetalSettings.FrameGeneration.OFF)
										.setBinding(value -> MetalSettings.get().frameGeneration = value, () -> MetalSettings.get().frameGeneration)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.hdr"))
								.addOption(builder.createBooleanOption(HDR)
										.setName(Component.translatable("mine2metal.options.hdr"))
										.setTooltip(value -> MetalOptionTexts.hdrTooltip())
										.setImpact(OptionImpact.LOW)
										.setEnabled(MetalRenderer.supportsHdr())
										.setStorageHandler(save)
										.setDefaultValue(false)
										.setBinding(value -> {
											MetalSettings.get().hdr = value;
											MetalRenderer.setHdr(value, MetalSettings.get().hdrBrightness);
										}, () -> MetalSettings.get().hdr))
								.addOption(builder.createIntegerOption(HDR_BRIGHTNESS)
										.setName(Component.translatable("mine2metal.options.hdr_brightness"))
										.setTooltip(Component.translatable("mine2metal.options.hdr_brightness.tooltip"))
										.setRange(MetalSettings.MIN_HDR_BRIGHTNESS, MetalSettings.MAX_HDR_BRIGHTNESS, MetalSettings.HDR_BRIGHTNESS_STEP)
										.setValueFormatter(MetalOptionTexts::percent)
										.setImpact(OptionImpact.LOW)
										.setEnabledProvider(state -> MetalRenderer.supportsHdr() && state.readBooleanOption(HDR), HDR)
										.setStorageHandler(save)
										.setDefaultValue(200)
										.setBinding(value -> {
											MetalSettings.get().hdrBrightness = MetalSettings.clampHdrBrightness(value);
											MetalRenderer.setHdr(MetalSettings.get().hdr, MetalSettings.get().hdrBrightness);
										}, () -> MetalSettings.get().hdrBrightness)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.diagnostics"))
								.addOption(builder.createBooleanOption(PERFORMANCE_HUD)
										.setName(Component.translatable("mine2metal.options.performance_hud"))
										.setTooltip(value -> MetalOptionTexts.performanceHudTooltip())
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(save)
										.setDefaultValue(false)
										.setBinding(value -> {
											MetalSettings.get().performanceHud = value;
											MetalRenderer.setPerformanceHud(value);
										}, () -> MetalSettings.get().performanceHud))))
				.addPage(builder.createOptionPage()
						.setName(Component.translatable("mine2metal.options.shaders_page"))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.shader_general"))
								.addOption(builder.createEnumOption(SHADER_PRESET, MetalSettings.ShaderPreset.class)
										.setName(Component.translatable("mine2metal.options.shader_preset"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_preset.tooltip"))
										.setElementNameProvider(ShaderPresetSync.named(MetalOptionTexts::shaderPresetName))
										.setImpact(OptionImpact.HIGH)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(save)
										.setDefaultValue(MetalSettings.ShaderPreset.OFF)
										.setBinding(value -> MetalSettings.get().applyShaderPreset(value), () -> MetalSettings.get().shaderPreset))
								.addOption(builder.createIntegerOption(SHADER_LIGHTING_SCALE)
										.setName(Component.translatable("mine2metal.options.shader_lighting_scale"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_lighting_scale.tooltip"))
										.setRange(MetalSettings.MIN_SHADER_LIGHTING_SCALE, MetalSettings.MAX_SHADER_LIGHTING_SCALE,
												MetalSettings.SHADER_LIGHTING_SCALE_STEP)
										.setValueFormatter(ShaderPresetSync.formatted(MetalOptionTexts::percent))
										.setImpact(OptionImpact.HIGH)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(save)
										.setDefaultValue(75)
										.setBinding(value -> {
											MetalSettings.get().shaderLightingScale = MetalSettings.clampShaderLightingScale(value);
											MetalSettings.get().syncShaderPreset();
										}, () -> MetalSettings.get().shaderLightingScale)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.shader_lighting"))
								.addOption(builder.createEnumOption(SHADER_SHADOWS, MetalSettings.ShadowQuality.class)
										.setName(Component.translatable("mine2metal.options.shader_shadows"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_shadows.tooltip"))
										.setElementNameProvider(ShaderPresetSync.named(MetalOptionTexts::shadowQualityName))
										.setImpact(OptionImpact.HIGH)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(save)
										.setDefaultValue(MetalSettings.ShadowQuality.OFF)
										.setBinding(value -> {
											MetalSettings.get().shaderShadows = value;
											MetalSettings.get().syncShaderPreset();
										}, () -> MetalSettings.get().shaderShadows))
								.addOption(builder.createEnumOption(SHADER_GI, MetalSettings.GiQuality.class)
										.setName(Component.translatable("mine2metal.options.shader_gi"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_gi.tooltip"))
										.setElementNameProvider(ShaderPresetSync.named(MetalOptionTexts::giQualityName))
										.setImpact(OptionImpact.HIGH)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(save)
										.setDefaultValue(MetalSettings.GiQuality.OFF)
										.setBinding(value -> {
											MetalSettings.get().shaderGi = value;
											MetalSettings.get().syncShaderPreset();
										}, () -> MetalSettings.get().shaderGi))
								.addOption(builder.createEnumOption(SHADER_AO, MetalSettings.AoQuality.class)
										.setName(Component.translatable("mine2metal.options.shader_ao"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_ao.tooltip"))
										.setElementNameProvider(ShaderPresetSync.named(MetalOptionTexts::aoQualityName))
										.setImpact(OptionImpact.MEDIUM)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(save)
										.setDefaultValue(MetalSettings.AoQuality.OFF)
										.setBinding(value -> {
											MetalSettings.get().shaderAo = value;
											MetalSettings.get().syncShaderPreset();
										}, () -> MetalSettings.get().shaderAo)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("mine2metal.options.group.shader_atmosphere"))
								.addOption(builder.createEnumOption(SHADER_REFLECTIONS, MetalSettings.ReflectionQuality.class)
										.setName(Component.translatable("mine2metal.options.shader_reflections"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_reflections.tooltip"))
										.setElementNameProvider(ShaderPresetSync.named(MetalOptionTexts::reflectionQualityName))
										.setImpact(OptionImpact.HIGH)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(save)
										.setDefaultValue(MetalSettings.ReflectionQuality.OFF)
										.setBinding(value -> {
											MetalSettings.get().shaderReflections = value;
											MetalSettings.get().syncShaderPreset();
										}, () -> MetalSettings.get().shaderReflections))
								.addOption(builder.createEnumOption(SHADER_VOLUMETRICS, MetalSettings.VolumetricQuality.class)
										.setName(Component.translatable("mine2metal.options.shader_volumetrics"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_volumetrics.tooltip"))
										.setElementNameProvider(ShaderPresetSync.named(MetalOptionTexts::volumetricQualityName))
										.setImpact(OptionImpact.MEDIUM)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(save)
										.setDefaultValue(MetalSettings.VolumetricQuality.OFF)
										.setBinding(value -> {
											MetalSettings.get().shaderVolumetrics = value;
											MetalSettings.get().syncShaderPreset();
										}, () -> MetalSettings.get().shaderVolumetrics))
								.addOption(builder.createEnumOption(SHADER_BLOOM, MetalSettings.BloomQuality.class)
										.setName(Component.translatable("mine2metal.options.shader_bloom"))
										.setTooltip(value -> MetalOptionTexts.shaderTooltip("mine2metal.options.shader_bloom.tooltip"))
										.setElementNameProvider(ShaderPresetSync.named(MetalOptionTexts::bloomQualityName))
										.setImpact(OptionImpact.LOW)
										.setEnabled(MetalRenderer.isActive())
										.setStorageHandler(save)
										.setDefaultValue(MetalSettings.BloomQuality.OFF)
										.setBinding(value -> {
											MetalSettings.get().shaderBloom = value;
											MetalSettings.get().syncShaderPreset();
										}, () -> MetalSettings.get().shaderBloom))));
	}
}
