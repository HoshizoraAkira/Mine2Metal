package dev.mine2metal;

import dev.mine2metal.config.MetalSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Mine2Metal {
	public static final String MOD_ID = "mine2metal";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	/** The oldest macOS the native library is built for (macos_min_version in gradle.properties). */
	public static final int MIN_MACOS_VERSION = 14;

	private Mine2Metal() {
	}

	/** {@code -Dmine2metal.enabled} wins over the config file; both leave Mojang's backends alone when false. */
	public static boolean isEnabled() {
		String override = System.getProperty("mine2metal.enabled");
		if (override != null) {
			return Boolean.parseBoolean(override);
		}
		return MetalSettings.get().enabled;
	}

	/** The Metal version asked for: {@code -Dmine2metal.metal} wins over the config file. */
	public static MetalSettings.MetalVersion requestedMetalVersion() {
		String override = System.getProperty("mine2metal.metal");
		return override != null ? MetalSettings.parseMetalVersion(override) : MetalSettings.get().metalVersion;
	}

	/** A Mac with Apple silicon and a new enough macOS; {@link MetalAvailability#platformProblem} says what is missing. */
	public static boolean isSupportedPlatform() {
		return MetalAvailability.platformProblem() == null;
	}
}
