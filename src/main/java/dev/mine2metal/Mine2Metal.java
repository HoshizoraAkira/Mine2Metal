package dev.mine2metal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Mine2Metal {
	public static final String MOD_ID = "mine2metal";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private Mine2Metal() {
	}

	/** {@code -Dmine2metal.enabled=false} leaves Mojang's backend order untouched. */
	public static boolean isEnabled() {
		return Boolean.parseBoolean(System.getProperty("mine2metal.enabled", "true"));
	}

	public static boolean isSupportedPlatform() {
		return System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch").equals("aarch64");
	}
}
