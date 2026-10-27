package dev.mine2metal.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import com.mojang.renderpearl.api.device.GpuBackend;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates a singleplayer world and screenshots it through Minecraft's own readback path, so the images exercise the
 * Metal backend end to end. Screenshots land in the game test run directory's {@code screenshots} folder.
 */
public class MetalWorldGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mine2metal-gametest");
	private static final String SEED = "mine2metal";

	@Override
	public void runTest(ClientGameTestContext context) {
		String backend = context.computeOnClient(client -> RenderSystem.getDevice().getDeviceInfo().backendName());
		if (!backend.equals("Metal") && Boolean.parseBoolean(System.getProperty("mine2metal.enabled", "true"))) {
			throw new AssertionError("Expected the Metal backend, got " + backend);
		}
		LOGGER.info("Sodium draw backend: {}", sodiumDrawBackend());
		if (backend.equals("Metal")) {
			checkBackendSelection();
		}

		context.takeScreenshot("mine2metal-title");

		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.adjustSettings(settings -> {
					settings.setWorldType(settings.getNormalPresetList().getFirst());
					settings.setSeed(SEED);
				})
				.create()) {
			singleplayer.getConnection().waitForChunksRender();
			context.takeScreenshot("mine2metal-world");

			// Creative flight keeps the player where the camera is put instead of falling to its death.
			singleplayer.getServer().runCommand("gamemode creative @a");
			context.waitTicks(5);
			context.runOnClient(client -> client.player.getAbilities().flying = true);

			// From above, looking down: lots of chunks, foliage (cutout) and usually water (translucent).
			singleplayer.getServer().runCommand("tp @a ~ ~40 ~");
			context.waitTicks(20);
			context.getInput().lookAt(0.0F, 50.0F);
			singleplayer.getConnection().waitForChunksRender();
			context.takeScreenshot("mine2metal-world-above");
			LOGGER.info("Average FPS from above (vsync off, uncapped): {}", averageFps(context));

			singleplayer.getServer().runCommand("time set midnight");
			context.waitTicks(20);
			context.takeScreenshot("mine2metal-world-night");

			// Improved transparency (formerly Fabulous) renders translucency into extra targets and composites them
			// with depth sampling, a path the plain screenshots above never take.
			singleplayer.getServer().runCommand("time set noon");
			context.runOnClient(client -> client.options.improvedTransparency().set(true));
			context.waitTicks(40);
			context.takeScreenshot("mine2metal-world-improved-transparency");
			context.runOnClient(client -> client.options.improvedTransparency().set(false));

			// A heavier load: a large window and render distance 16, looking at the horizon.
			singleplayer.getServer().runCommand("time set noon");
			context.getInput().resizeWindow(1920, 1080);
			context.runOnClient(client -> client.options.renderDistance().set(16));
			context.getInput().lookAt(0.0F, 10.0F);
			// waitForChunksRender never settles at this distance (on OpenGL too). Generation competes with rendering for
			// the CPU, so give it long enough to finish or the measurement mostly reflects world generation.
			context.waitTicks(45 * 20);
			context.takeScreenshot("mine2metal-world-far");
			LOGGER.info("Average FPS at 1920x1080, render distance 16: {}", averageFps(context));

			// The surface has to follow fullscreen switches and window resizes.
			context.runOnClient(client -> client.options.fullscreen().set(true));
			context.waitTicks(40);
			context.takeScreenshot("mine2metal-fullscreen");
			context.runOnClient(client -> client.options.fullscreen().set(false));
			context.waitTicks(40);
			context.getInput().resizeWindow(1000, 600);
			context.waitTicks(20);
			int[] size = context.computeOnClient(client -> new int[] {client.getWindow().getWidth(), client.getWindow().getHeight()});
			LOGGER.info("Framebuffer after fullscreen and resize: {}x{}", size[0], size[1]);
			context.takeScreenshot("mine2metal-resized");
		}
	}

	/** Metal comes first only for the "Default" graphics API, and the config file can turn it off. */
	private static void checkBackendSelection() {
		check(PreferredGraphicsApi.DEFAULT.getBackendsToTry()[0].getName().equals("Metal"), "Default should try Metal first");
		for (PreferredGraphicsApi explicit : new PreferredGraphicsApi[] {PreferredGraphicsApi.OPENGL, PreferredGraphicsApi.VULKAN}) {
			for (GpuBackend candidate : explicit.getBackendsToTry()) {
				check(!candidate.getName().equals("Metal"), explicit + " should not try Metal");
			}
		}

		Path config = FabricLoader.getInstance().getConfigDir().resolve("mine2metal.properties");
		try {
			String original = Files.readString(config);
			Files.writeString(config, original.replace("enabled=true", "enabled=false"));
			try {
				check(!PreferredGraphicsApi.DEFAULT.getBackendsToTry()[0].getName().equals("Metal"), "enabled=false should skip Metal");
			} finally {
				Files.writeString(config, original);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		LOGGER.info("Backend selection checks passed");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	/** Uncaps the frame rate and averages Minecraft's per-second FPS counter over a few seconds. */
	private static int averageFps(ClientGameTestContext context) {
		context.runOnClient(client -> {
			client.options.enableVsync().set(false);
			client.options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF);
			// The test sends no input for minutes, which would otherwise trip the 30 fps AFK limiter.
			client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		});
		// Let chunk meshing settle so the numbers measure rendering, not world loading.
		context.waitTicks(100);

		int samples = 10;
		int total = 0;
		for (int i = 0; i < samples; i++) {
			context.waitTicks(20);
			total += context.computeOnClient(Minecraft::getFps);
		}
		return total / samples;
	}

	/** Sodium is optional at runtime (-PnoSodium), so it is looked up reflectively. */
	private static String sodiumDrawBackend() {
		try {
			Class<?> drawBackend = Class.forName("net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend");
			Object current = drawBackend.getField("BACKEND").get(null);
			return (String) drawBackend.getMethod("getName").invoke(current);
		} catch (ClassNotFoundException e) {
			return "not installed";
		} catch (ReflectiveOperationException e) {
			return "unknown (" + e + ")";
		}
	}
}
