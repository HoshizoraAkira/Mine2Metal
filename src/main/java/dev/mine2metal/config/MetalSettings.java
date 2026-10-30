package dev.mine2metal.config;

import dev.mine2metal.Mine2Metal;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** Player settings, kept in {@code config/mine2metal.properties}. Read once, then changed through the settings screens. */
public final class MetalSettings {
	public static final int MIN_RENDER_SCALE = 50;
	public static final int MAX_RENDER_SCALE = 100;
	public static final int RENDER_SCALE_STEP = 5;

	private static final String FILE = "mine2metal.properties";
	private static MetalSettings instance;

	public enum Upscaling {
		OFF,
		/** MetalFX spatial scaling. */
		SPATIAL,
		/** MetalFX temporal scaling. */
		TEMPORAL,
		/** AMD FidelityFX Super Resolution 1 (EASU and RCAS sharpening), spatial, through the Metal backend. */
		FSR1,
		/** AMD FidelityFX Super Resolution 3.1 upscaling, temporal, through the Metal backend. */
		FSR3
	}

	public enum FrameGeneration {
		OFF,
		/** MetalFX frame interpolation (macOS 26 or newer). */
		METALFX,
		/** AMD FidelityFX Super Resolution 3.1 frame generation, through the Metal backend. */
		FSR3
	}

	public enum MetalVersion {
		/** Metal 4 when this Mac supports it (macOS 26, Apple M1 or later), otherwise Metal 3. */
		AUTO,
		METAL_4,
		METAL_3
	}

	public enum ShaderPreset {
		OFF,
		LOW,
		MEDIUM,
		HIGH,
		ULTRA,
		CUSTOM
	}

	public enum ShadowQuality {
		OFF,
		/** Fast screen-space contact shadows. */
		LOW,
		/** Soft screen-space sun/moon shadows with penumbra filtering. */
		MEDIUM,
		/** Hybrid 3D voxel + screen-space directional shadows. */
		HIGH,
		/** Ray-traced soft shadows (PCSS) + colored translucent glass/foliage transmission. */
		ULTRA
	}

	public enum AoQuality {
		OFF,
		/** Fast hemisphere SSAO. */
		LOW,
		/** Ground-Truth Ambient Occlusion (GTAO). */
		MEDIUM,
		/** Ray-Traced Ambient Occlusion (RTAO) in the 3D voxel volume. */
		HIGH
	}

	public enum ReflectionQuality {
		OFF,
		/** Fresnel water waves and atmospheric sky/sun specular. */
		LOW,
		/** Screen-Space Reflections (SSR) on water and smooth blocks. */
		MEDIUM,
		/** Multi-octave water waves, underwater absorption, and high-step SSR. */
		HIGH,
		/** Hybrid SSR + 3D Voxel Ray-Traced Reflections for off-screen geometry and lights. */
		ULTRA
	}

	public enum GiQuality {
		OFF,
		/** Directional sky hemisphere and warm sun bounce tint. */
		LOW,
		/** Screen-space diffuse bounce + direct emissive point light illumination. */
		MEDIUM,
		/** Voxel Ray-Traced Global Illumination (RTGI) + shadowed emissive block lights. */
		HIGH
	}

	public enum VolumetricQuality {
		OFF,
		LOW,
		MEDIUM,
		HIGH
	}

	public enum BloomQuality {
		OFF,
		LOW,
		MEDIUM,
		HIGH
	}

	public static final int MIN_SHADER_LIGHTING_SCALE = 50;
	public static final int MAX_SHADER_LIGHTING_SCALE = 100;
	public static final int SHADER_LIGHTING_SCALE_STEP = 25;

	public boolean enabled = true;
	/** Read once at startup, when the backend is created. */
	public MetalVersion metalVersion = MetalVersion.AUTO;
	public Upscaling upscaling = Upscaling.OFF;
	/** Percent of the window's width and height the world is rendered at while upscaling. */
	public int renderScale = 75;
	/** A generated frame between each pair the game renders, by MetalFX or by FSR 3.1; one at a time. */
	public FrameGeneration frameGeneration = FrameGeneration.OFF;
	public static final int MIN_HDR_BRIGHTNESS = 125;
	public static final int MAX_HDR_BRIGHTNESS = 400;
	public static final int HDR_BRIGHTNESS_STEP = 25;

	/** EDR output: the world's highlights brighter than SDR white, on displays that can show it. */
	public boolean hdr;
	/** Percent of SDR white the brightest highlights reach with {@link #hdr}, capped by what the display can show. */
	public int hdrBrightness = 200;
	/** Apple's Metal Performance HUD over the game window. */
	public boolean performanceHud;

	/** Built-in Native Metal Shader (Natural Cinematic) settings. */
	public ShaderPreset shaderPreset = ShaderPreset.OFF;
	public ShadowQuality shaderShadows = ShadowQuality.OFF;
	public AoQuality shaderAo = AoQuality.OFF;
	public ReflectionQuality shaderReflections = ReflectionQuality.OFF;
	public GiQuality shaderGi = GiQuality.OFF;
	public VolumetricQuality shaderVolumetrics = VolumetricQuality.OFF;
	public BloomQuality shaderBloom = BloomQuality.OFF;
	public int shaderLightingScale = 75;

	private MetalSettings() {
	}

	public static synchronized MetalSettings get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/** Forgets the loaded values so the next {@link #get} reads the file again. */
	public static synchronized void reload() {
		instance = null;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE);
	}

	private static MetalSettings load() {
		MetalSettings settings = new MetalSettings();
		Path file = file();
		if (Files.notExists(file)) {
			settings.save();
			return settings;
		}

		Properties values = new Properties();
		try (Reader reader = Files.newBufferedReader(file)) {
			values.load(reader);
		} catch (IOException e) {
			Mine2Metal.LOGGER.warn("Could not read {}; using defaults", file, e);
			return settings;
		}
		settings.enabled = Boolean.parseBoolean(values.getProperty("enabled", "true"));
		settings.metalVersion = parseMetalVersion(values.getProperty("metalVersion", "auto"));
		settings.upscaling = parseUpscaling(values.getProperty("upscaling", "off"));
		settings.renderScale = clampScale(parseInt(values.getProperty("renderScale"), settings.renderScale));
		// frameInterpolation=true is what versions with only MetalFX wrote.
		String legacy = Boolean.parseBoolean(values.getProperty("frameInterpolation", "false")) ? "metalfx" : "off";
		settings.frameGeneration = parseFrameGeneration(values.getProperty("frameGeneration", legacy));
		settings.hdr = Boolean.parseBoolean(values.getProperty("hdr", "false"));
		settings.hdrBrightness = clampHdrBrightness(parseInt(values.getProperty("hdrBrightness"), settings.hdrBrightness));
		settings.performanceHud = Boolean.parseBoolean(values.getProperty("performanceHud", "false"));
		settings.shaderPreset = parseEnum(ShaderPreset.class, values.getProperty("shaderPreset"), ShaderPreset.OFF);
		settings.shaderShadows = parseEnum(ShadowQuality.class, values.getProperty("shaderShadows"), ShadowQuality.OFF);
		settings.shaderAo = parseEnum(AoQuality.class, values.getProperty("shaderAo"), AoQuality.OFF);
		settings.shaderReflections = parseEnum(ReflectionQuality.class, values.getProperty("shaderReflections"), ReflectionQuality.OFF);
		settings.shaderGi = parseEnum(GiQuality.class, values.getProperty("shaderGi"), GiQuality.OFF);
		settings.shaderVolumetrics = parseEnum(VolumetricQuality.class, values.getProperty("shaderVolumetrics"), VolumetricQuality.OFF);
		settings.shaderBloom = parseEnum(BloomQuality.class, values.getProperty("shaderBloom"), BloomQuality.OFF);
		settings.shaderLightingScale = clampShaderLightingScale(parseInt(values.getProperty("shaderLightingScale"), settings.shaderLightingScale));
		if (settings.shaderPreset != ShaderPreset.CUSTOM) {
			settings.applyShaderPreset(settings.shaderPreset);
		}
		return settings;
	}

	public synchronized void save() {
		String text = """
				# mine2metal: native Metal rendering for Minecraft on Apple silicon Macs.
				# Also editable in game: Video Settings > Metal (with Sodium) or Video Settings > Metal Settings.
				#
				# Metal is used while Options > Video Settings > Graphics API is "Default". Choosing OpenGL or
				# Vulkan there (and restarting) bypasses it, as does enabled=false. -Dmine2metal.enabled overrides this.
				enabled=%s
				#
				# auto: Metal 4 when this Mac supports it (macOS 26 or newer), otherwise Metal 3
				# metal4 / metal3: that version; metal4 falls back to Metal 3 where it is not available
				# Applies after a restart. -Dmine2metal.metal=auto|metal4|metal3 overrides this.
				metalVersion=%s
				#
				# off: render the world at full resolution
				# spatial: render the world at renderScale percent and upscale it with MetalFX, frame by frame
				# temporal: the same, combining several frames for more detail; at 100 it only smooths edges
				# fsr1 / fsr3: like spatial / temporal, with AMD FidelityFX Super Resolution 1 / 3.1 (about twice the GPU
				#   time of MetalFX on Apple GPUs)
				# Upscaling pays off only when the GPU holds the game back (shaders, large displays): plain Minecraft costs
				# the GPU well under a millisecond a frame, upscaling a few.
				upscaling=%s
				renderScale=%d
				#
				# Frame generation: shows a generated frame between each pair the game renders, for up to twice the
				# frame rate on screen, capped at the display's refresh rate. Frames are paced to the display even with
				# vsync off, and input lags by about one rendered frame. It stands by while the game reaches the refresh
				# rate by itself and steps in when the game falls below it.
				# off
				# metalfx: MetalFX frame interpolation (macOS 26 or newer)
				# fsr3: AMD FidelityFX Super Resolution 3.1 frame generation, about half the GPU cost of MetalFX
				# Heavy on the GPU at large window sizes.
				frameGeneration=%s
				#
				# HDR (EDR) output on displays that can show brighter than SDR white: the world's brightest parts (sky,
				# sun, lava, light sources) reach hdrBrightness percent of SDR white (125-400), as far as the display's
				# current brightness setting leaves room. The hand and the GUI stay at SDR brightness.
				hdr=%s
				hdrBrightness=%d
				#
				# Apple's Metal Performance HUD (frame rate, frame times, GPU time, memory) over the game window.
				performanceHud=%s
				#
				# Native Metal Shaders (Natural Cinematic):
				# shaderPreset: off | low | medium | high | ultra | custom
				shaderPreset=%s
				shaderShadows=%s
				shaderAo=%s
				shaderReflections=%s
				shaderGi=%s
				shaderVolumetrics=%s
				shaderBloom=%s
				shaderLightingScale=%d
				""".formatted(
						this.enabled,
						metalVersionName(this.metalVersion),
						this.upscaling.name().toLowerCase(Locale.ROOT),
						this.renderScale,
						this.frameGeneration.name().toLowerCase(Locale.ROOT),
						this.hdr,
						this.hdrBrightness,
						this.performanceHud,
						this.shaderPreset.name().toLowerCase(Locale.ROOT),
						this.shaderShadows.name().toLowerCase(Locale.ROOT),
						this.shaderAo.name().toLowerCase(Locale.ROOT),
						this.shaderReflections.name().toLowerCase(Locale.ROOT),
						this.shaderGi.name().toLowerCase(Locale.ROOT),
						this.shaderVolumetrics.name().toLowerCase(Locale.ROOT),
						this.shaderBloom.name().toLowerCase(Locale.ROOT),
						this.shaderLightingScale);
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text);
		} catch (IOException e) {
			Mine2Metal.LOGGER.warn("Could not write {}", file, e);
		}
	}

	public boolean shadersEnabled() {
		return this.shaderPreset != ShaderPreset.OFF && (this.shaderShadows != ShadowQuality.OFF
				|| this.shaderAo != AoQuality.OFF
				|| this.shaderReflections != ReflectionQuality.OFF
				|| this.shaderGi != GiQuality.OFF
				|| this.shaderVolumetrics != VolumetricQuality.OFF
				|| this.shaderBloom != BloomQuality.OFF);
	}

	public void applyShaderPreset(ShaderPreset preset) {
		this.shaderPreset = preset;
		switch (preset) {
			case OFF -> {
				this.shaderShadows = ShadowQuality.OFF;
				this.shaderAo = AoQuality.OFF;
				this.shaderReflections = ReflectionQuality.OFF;
				this.shaderGi = GiQuality.OFF;
				this.shaderVolumetrics = VolumetricQuality.OFF;
				this.shaderBloom = BloomQuality.OFF;
				this.shaderLightingScale = 75;
			}
			case LOW -> {
				this.shaderShadows = ShadowQuality.LOW;
				this.shaderAo = AoQuality.LOW;
				this.shaderReflections = ReflectionQuality.LOW;
				this.shaderGi = GiQuality.LOW;
				this.shaderVolumetrics = VolumetricQuality.LOW;
				this.shaderBloom = BloomQuality.LOW;
				this.shaderLightingScale = 50;
			}
			case MEDIUM -> {
				this.shaderShadows = ShadowQuality.MEDIUM;
				this.shaderAo = AoQuality.MEDIUM;
				this.shaderReflections = ReflectionQuality.MEDIUM;
				this.shaderGi = GiQuality.MEDIUM;
				this.shaderVolumetrics = VolumetricQuality.MEDIUM;
				this.shaderBloom = BloomQuality.MEDIUM;
				this.shaderLightingScale = 75;
			}
			case HIGH -> {
				this.shaderShadows = ShadowQuality.HIGH;
				this.shaderAo = AoQuality.MEDIUM;
				this.shaderReflections = ReflectionQuality.HIGH;
				this.shaderGi = GiQuality.HIGH;
				this.shaderVolumetrics = VolumetricQuality.HIGH;
				this.shaderBloom = BloomQuality.HIGH;
				this.shaderLightingScale = 75;
			}
			case ULTRA -> {
				this.shaderShadows = ShadowQuality.ULTRA;
				this.shaderAo = AoQuality.HIGH;
				this.shaderReflections = ReflectionQuality.ULTRA;
				this.shaderGi = GiQuality.HIGH;
				this.shaderVolumetrics = VolumetricQuality.HIGH;
				this.shaderBloom = BloomQuality.HIGH;
				this.shaderLightingScale = 75;
			}
			case CUSTOM -> {
				if (!this.shadersEnabled()) {
					this.shaderShadows = ShadowQuality.MEDIUM;
					this.shaderAo = AoQuality.MEDIUM;
					this.shaderReflections = ReflectionQuality.MEDIUM;
					this.shaderGi = GiQuality.MEDIUM;
					this.shaderVolumetrics = VolumetricQuality.MEDIUM;
					this.shaderBloom = BloomQuality.MEDIUM;
					this.shaderLightingScale = 75;
				}
			}
		}
	}

	public void syncShaderPreset() {
		if (this.shaderShadows == ShadowQuality.OFF && this.shaderAo == AoQuality.OFF
				&& this.shaderReflections == ReflectionQuality.OFF && this.shaderGi == GiQuality.OFF
				&& this.shaderVolumetrics == VolumetricQuality.OFF && this.shaderBloom == BloomQuality.OFF) {
			this.shaderPreset = ShaderPreset.OFF;
		} else if (this.shaderShadows == ShadowQuality.LOW && this.shaderAo == AoQuality.LOW
				&& this.shaderReflections == ReflectionQuality.LOW && this.shaderGi == GiQuality.LOW
				&& this.shaderVolumetrics == VolumetricQuality.LOW && this.shaderBloom == BloomQuality.LOW
				&& this.shaderLightingScale == 50) {
			this.shaderPreset = ShaderPreset.LOW;
		} else if (this.shaderShadows == ShadowQuality.MEDIUM && this.shaderAo == AoQuality.MEDIUM
				&& this.shaderReflections == ReflectionQuality.MEDIUM && this.shaderGi == GiQuality.MEDIUM
				&& this.shaderVolumetrics == VolumetricQuality.MEDIUM && this.shaderBloom == BloomQuality.MEDIUM
				&& this.shaderLightingScale == 75) {
			this.shaderPreset = ShaderPreset.MEDIUM;
		} else if (this.shaderShadows == ShadowQuality.HIGH && this.shaderAo == AoQuality.MEDIUM
				&& this.shaderReflections == ReflectionQuality.HIGH && this.shaderGi == GiQuality.HIGH
				&& this.shaderVolumetrics == VolumetricQuality.HIGH && this.shaderBloom == BloomQuality.HIGH
				&& this.shaderLightingScale == 75) {
			this.shaderPreset = ShaderPreset.HIGH;
		} else if (this.shaderShadows == ShadowQuality.ULTRA && this.shaderAo == AoQuality.HIGH
				&& this.shaderReflections == ReflectionQuality.ULTRA && this.shaderGi == GiQuality.HIGH
				&& this.shaderVolumetrics == VolumetricQuality.HIGH && this.shaderBloom == BloomQuality.HIGH
				&& this.shaderLightingScale == 75) {
			this.shaderPreset = ShaderPreset.ULTRA;
		} else {
			this.shaderPreset = ShaderPreset.CUSTOM;
		}
	}

	public static int clampShaderLightingScale(int percent) {
		int stepped = Math.round(percent / (float) SHADER_LIGHTING_SCALE_STEP) * SHADER_LIGHTING_SCALE_STEP;
		return Math.clamp(stepped, MIN_SHADER_LIGHTING_SCALE, MAX_SHADER_LIGHTING_SCALE);
	}

	public static int clampScale(int percent) {
		int stepped = Math.round(percent / (float) RENDER_SCALE_STEP) * RENDER_SCALE_STEP;
		return Math.clamp(stepped, MIN_RENDER_SCALE, MAX_RENDER_SCALE);
	}

	public static int clampHdrBrightness(int percent) {
		int stepped = Math.round(percent / (float) HDR_BRIGHTNESS_STEP) * HDR_BRIGHTNESS_STEP;
		return Math.clamp(stepped, MIN_HDR_BRIGHTNESS, MAX_HDR_BRIGHTNESS);
	}

	public static MetalVersion parseMetalVersion(String value) {
		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "metal4", "4" -> MetalVersion.METAL_4;
			case "metal3", "3" -> MetalVersion.METAL_3;
			default -> MetalVersion.AUTO;
		};
	}

	private static String metalVersionName(MetalVersion version) {
		return switch (version) {
			case AUTO -> "auto";
			case METAL_4 -> "metal4";
			case METAL_3 -> "metal3";
		};
	}

	private static FrameGeneration parseFrameGeneration(String value) {
		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "metalfx" -> FrameGeneration.METALFX;
			case "fsr3", "fsr" -> FrameGeneration.FSR3;
			default -> FrameGeneration.OFF;
		};
	}

	private static Upscaling parseUpscaling(String value) {
		try {
			return Upscaling.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return Upscaling.OFF;
		}
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	private static int parseInt(String value, int fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
