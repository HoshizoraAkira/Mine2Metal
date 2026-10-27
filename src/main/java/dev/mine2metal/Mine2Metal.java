package dev.mine2metal;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class Mine2Metal {
	public static final String MOD_ID = "mine2metal";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String CONFIG_FILE = "mine2metal.properties";
	private static final String DEFAULT_CONFIG = """
			# mine2metal: native Metal rendering for Minecraft on Apple silicon Macs.
			#
			# Metal is used while Options > Video Settings > Graphics API is "Default".
			# Choosing OpenGL or Vulkan there (and restarting) bypasses it, as does enabled=false below.
			# The JVM flag -Dmine2metal.enabled=true|false overrides this file.
			enabled=true
			""";

	private Mine2Metal() {
	}

	/** {@code -Dmine2metal.enabled} wins over the config file; both leave Mojang's backends alone when false. */
	public static boolean isEnabled() {
		String override = System.getProperty("mine2metal.enabled");
		if (override != null) {
			return Boolean.parseBoolean(override);
		}
		return Boolean.parseBoolean(loadConfig().getProperty("enabled", "true"));
	}

	public static boolean isSupportedPlatform() {
		return System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch").equals("aarch64");
	}

	private static Properties loadConfig() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
		Properties config = new Properties();
		try {
			if (Files.notExists(file)) {
				Files.createDirectories(file.getParent());
				try (Writer writer = Files.newBufferedWriter(file)) {
					writer.write(DEFAULT_CONFIG);
				}
			}
			try (Reader reader = Files.newBufferedReader(file)) {
				config.load(reader);
			}
		} catch (IOException e) {
			LOGGER.warn("Could not read {}; using defaults", file, e);
		}
		return config;
	}
}
