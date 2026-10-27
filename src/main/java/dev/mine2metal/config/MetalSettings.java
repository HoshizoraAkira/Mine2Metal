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
		/** Not implemented yet; listed so the settings screens can show it as coming. */
		TEMPORAL
	}

	public boolean enabled = true;
	public Upscaling upscaling = Upscaling.OFF;
	/** Percent of the window's width and height the world is rendered at while upscaling. */
	public int renderScale = 75;
	/** Not implemented yet; always treated as off. */
	public boolean frameInterpolation;

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
		settings.upscaling = parseUpscaling(values.getProperty("upscaling", "off"));
		settings.renderScale = clampScale(parseInt(values.getProperty("renderScale"), settings.renderScale));
		settings.frameInterpolation = Boolean.parseBoolean(values.getProperty("frameInterpolation", "false"));
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
				# off: render the world at full resolution
				# spatial: render the world at renderScale percent and upscale it with MetalFX
				# temporal: not available yet
				upscaling=%s
				renderScale=%d
				#
				# Not available yet.
				frameInterpolation=%s
				""".formatted(this.enabled, this.upscaling.name().toLowerCase(Locale.ROOT), this.renderScale, this.frameInterpolation);
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
