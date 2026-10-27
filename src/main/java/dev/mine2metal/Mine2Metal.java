package dev.mine2metal;

import dev.mine2metal.config.MetalSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Mine2Metal {
	public static final String MOD_ID = "mine2metal";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

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

	public static boolean isSupportedPlatform() {
		return System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch").equals("aarch64");
	}
}
