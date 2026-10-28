package dev.mine2metal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Why the game is not rendering with Metal although it would have, for a message once the title screen shows: this Mac
 * cannot run it, or creating the backend failed. Minecraft then falls back to its own backends by itself but tells no
 * one. A player who picked OpenGL or Vulkan, or turned Metal off, gets no message.
 */
public final class MetalAvailability {
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(15_000L);
	private static @Nullable Component reason;
	private static boolean shown;

	private MetalAvailability() {
	}

	/** Why this platform cannot run the backend: for the player, and in English for the log. */
	public record Problem(Component message, String log) {
	}

	/** Why this platform cannot run the backend, or null if it can. */
	public static @Nullable Problem platformProblem() {
		if (!System.getProperty("os.name").startsWith("Mac")) {
			return new Problem(Component.translatable("mine2metal.unavailable.not_mac"), "not a Mac");
		}
		// Also what an Intel Java reports on Apple silicon, under Rosetta.
		if (!System.getProperty("os.arch").equals("aarch64")) {
			return new Problem(Component.translatable("mine2metal.unavailable.intel"), "needs Apple silicon and an arm64 Java, this is " + System.getProperty("os.arch"));
		}
		String version = System.getProperty("os.version");
		int major = macosMajorVersion(version);
		if (major > 0 && major < Mine2Metal.MIN_MACOS_VERSION) {
			return new Problem(Component.translatable("mine2metal.unavailable.old_macos", version, Mine2Metal.MIN_MACOS_VERSION),
					"needs macOS " + Mine2Metal.MIN_MACOS_VERSION + " or later, this is " + version);
		}
		return null;
	}

	private static int macosMajorVersion(String version) {
		try {
			return Integer.parseInt(version.split("\\.")[0]);
		} catch (RuntimeException e) {
			return 0;
		}
	}

	public static void unavailable(Problem problem) {
		if (reason == null) {
			reason = problem.message();
			Mine2Metal.LOGGER.warn("Not rendering with Metal: {}; Minecraft falls back to its own graphics backends", problem.log());
		}
	}

	/** Creating the backend failed with this message (a missing Metal 3, a library macOS refused to load). */
	public static void failed(String message) {
		unavailable(new Problem(Component.translatable("mine2metal.unavailable.failed", message), message));
	}

	public static @Nullable Component reason() {
		return reason;
	}

	/** On the title screen: shows the reason once per launch. */
	public static void showOnce(Minecraft client) {
		Component why = reason;
		if (why == null || shown) {
			return;
		}
		shown = true;
		SystemToast.addOrUpdate(client.gui.toastManager(), TOAST, Component.translatable("mine2metal.unavailable.title"), why);
	}
}
