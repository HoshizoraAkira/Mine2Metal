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
		SPATIAL,
		TEMPORAL
	}

	public enum MetalVersion {
		/** Metal 4 when this Mac supports it (macOS 26, Apple M1 or later), otherwise Metal 3. */
		AUTO,
		METAL_4,
		METAL_3
	}

	public boolean enabled = true;
	/** Read once at startup, when the backend is created. */
	public MetalVersion metalVersion = MetalVersion.AUTO;
	public Upscaling upscaling = Upscaling.OFF;
	/** Percent of the window's width and height the world is rendered at while upscaling. */
	public int renderScale = 75;
	/** MetalFX frame interpolation: a generated frame between each pair the game renders (macOS 26 or newer). */
	public boolean frameInterpolation;
	/** Apple's Metal Performance HUD over the game window. */
	public boolean performanceHud;

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
		settings.frameInterpolation = Boolean.parseBoolean(values.getProperty("frameInterpolation", "false"));
		settings.performanceHud = Boolean.parseBoolean(values.getProperty("performanceHud", "false"));
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
				upscaling=%s
				renderScale=%d
				#
				# MetalFX frame interpolation (macOS 26 or newer): shows a generated frame between each pair the game
				# renders, for up to twice the frame rate on screen, capped at the display's refresh rate. Frames are
				# paced to the display even with vsync off, and input lags by about one rendered frame.
				frameInterpolation=%s
				#
				# Apple's Metal Performance HUD (frame rate, frame times, GPU time, memory) over the game window.
				performanceHud=%s
				""".formatted(this.enabled, metalVersionName(this.metalVersion), this.upscaling.name().toLowerCase(Locale.ROOT), this.renderScale, this.frameInterpolation,
						this.performanceHud);
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text);
		} catch (IOException e) {
			Mine2Metal.LOGGER.warn("Could not write {}", file, e);
		}
	}

	public static int clampScale(int percent) {
		int stepped = Math.round(percent / (float) RENDER_SCALE_STEP) * RENDER_SCALE_STEP;
		return Math.clamp(stepped, MIN_RENDER_SCALE, MAX_RENDER_SCALE);
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

	private static Upscaling parseUpscaling(String value) {
		try {
			return Upscaling.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return Upscaling.OFF;
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
