package dev.mine2metal.config;

import dev.mine2metal.Mine2Metal;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.Config;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.Function;

/**
 * Keeps the shader preset and its derived options in step while they are being edited in Sodium's video settings.
 *
 * <p>Sodium holds a pending value per option and, on Apply, runs only the bindings of the options that changed. So
 * choosing a preset would write every derived value into {@link MetalSettings}, but the controls under it kept
 * showing their old pending values until the screen was reopened, and changing one derived option left the preset
 * control where it was although the settings had moved to Custom. {@link MetalSettingsScreen} does this with
 * callbacks; Sodium's config API has none, so {@link #tick} is called from the name providers the controls evaluate
 * each frame, compares the pending values with the last ones it saw, and pushes the derived values the same way
 * {@link MetalSettings#applyShaderPreset} and {@link MetalSettings#syncShaderPreset} would on Apply. The bindings then
 * find everything consistent in whatever order Sodium applies them.
 *
 * <p>It leans on Sodium's internal config classes; if they are missing it logs once and the bindings alone remain,
 * which is correct after a reopen.
 */
final class ShaderPresetSync {
	private static final Identifier PRESET = Identifier.fromNamespaceAndPath("mine2metal", "shader_preset");
	private static final Identifier LIGHTING_SCALE = Identifier.fromNamespaceAndPath("mine2metal", "shader_lighting_scale");
	private static final Identifier SHADOWS = Identifier.fromNamespaceAndPath("mine2metal", "shader_shadows");
	private static final Identifier AO = Identifier.fromNamespaceAndPath("mine2metal", "shader_ao");
	private static final Identifier REFLECTIONS = Identifier.fromNamespaceAndPath("mine2metal", "shader_reflections");
	private static final Identifier GI = Identifier.fromNamespaceAndPath("mine2metal", "shader_gi");
	private static final Identifier VOLUMETRICS = Identifier.fromNamespaceAndPath("mine2metal", "shader_volumetrics");
	private static final Identifier BLOOM = Identifier.fromNamespaceAndPath("mine2metal", "shader_bloom");

	/** The pending values as of the last tick, or null before the first one and after a failure. */
	private static MetalSettings seen;
	private static boolean broken;

	private ShaderPresetSync() {
	}

	/** An element name provider that also runs {@link #tick}; the controls evaluate it every frame they are drawn. */
	static <E> Function<E, Component> named(Function<E, Component> names) {
		return value -> {
			tick();
			return names.apply(value);
		};
	}

	/** A value formatter that also runs {@link #tick}. */
	static ControlValueFormatter formatted(ControlValueFormatter formatter) {
		return value -> {
			tick();
			return formatter.format(value);
		};
	}

	static void tick() {
		if (broken) {
			return;
		}
		try {
			Config config = ConfigManager.CONFIG;
			if (config == null) {
				return;
			}
			MetalSettings pending = readPending(config);
			MetalSettings last = seen;
			seen = pending;
			if (last == null) {
				return;
			}
			boolean presetChanged = pending.shaderPreset != last.shaderPreset;
			boolean derivedChanged = !sameDerived(pending, last);
			if (presetChanged && !derivedChanged) {
				// The preset control was cycled (or reset): the derived controls follow it.
				pending.applyShaderPreset(pending.shaderPreset);
				writeDerived(config, pending);
			} else if (derivedChanged && !presetChanged) {
				// A derived control was edited: the preset control shows which preset that is, or Custom.
				pending.syncShaderPreset();
				if (pending.shaderPreset != last.shaderPreset) {
					modify(config, PRESET, pending.shaderPreset);
				}
			}
			// Both at once is the screen opening, Undo or a page reset, which load consistent values.
		} catch (Throwable e) {
			broken = true;
			seen = null;
			Mine2Metal.LOGGER.warn("Shader preset and its options no longer follow each other while editing", e);
		}
	}

	private static MetalSettings readPending(Config config) {
		MetalSettings values = MetalSettings.scratch();
		values.shaderPreset = config.readEnumOption(PRESET, MetalSettings.ShaderPreset.class, false);
		values.shaderLightingScale = config.readIntOption(LIGHTING_SCALE, false);
		values.shaderShadows = config.readEnumOption(SHADOWS, MetalSettings.ShadowQuality.class, false);
		values.shaderAo = config.readEnumOption(AO, MetalSettings.AoQuality.class, false);
		values.shaderReflections = config.readEnumOption(REFLECTIONS, MetalSettings.ReflectionQuality.class, false);
		values.shaderGi = config.readEnumOption(GI, MetalSettings.GiQuality.class, false);
		values.shaderVolumetrics = config.readEnumOption(VOLUMETRICS, MetalSettings.VolumetricQuality.class, false);
		values.shaderBloom = config.readEnumOption(BLOOM, MetalSettings.BloomQuality.class, false);
		return values;
	}

	private static boolean sameDerived(MetalSettings a, MetalSettings b) {
		return a.shaderLightingScale == b.shaderLightingScale
				&& a.shaderShadows == b.shaderShadows
				&& a.shaderAo == b.shaderAo
				&& a.shaderReflections == b.shaderReflections
				&& a.shaderGi == b.shaderGi
				&& a.shaderVolumetrics == b.shaderVolumetrics
				&& a.shaderBloom == b.shaderBloom;
	}

	private static void writeDerived(Config config, MetalSettings values) {
		modify(config, LIGHTING_SCALE, values.shaderLightingScale);
		modify(config, SHADOWS, values.shaderShadows);
		modify(config, AO, values.shaderAo);
		modify(config, REFLECTIONS, values.shaderReflections);
		modify(config, GI, values.shaderGi);
		modify(config, VOLUMETRICS, values.shaderVolumetrics);
		modify(config, BLOOM, values.shaderBloom);
	}

	@SuppressWarnings("unchecked")
	private static <V> void modify(Config config, Identifier id, V value) {
		((StatefulOption<V>) config.getOption(id)).modifyValue(value);
	}
}
