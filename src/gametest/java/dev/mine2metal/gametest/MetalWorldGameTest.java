package dev.mine2metal.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.mine2metal.metal.MetalRenderer;
import org.joml.Vector4f;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import com.mojang.renderpearl.api.device.GpuBackend;
import dev.mine2metal.config.MetalSettings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;
import dev.mine2metal.config.MetalSettingsScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;

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
		if (backend.equals("Metal")) {
			checkTimestamps(context);
			checkPartialClear(context);
			context.runOnClient(client -> {
				MetalRenderer.setPerformanceHud(true);
				MetalRenderer.setPerformanceHud(false);
			});
		}
		if (backend.equals("Metal") && !Boolean.getBoolean("mine2metal.test.skipScreens")) {
			openSettingsScreens(context);
		}

		if (backend.equals("Metal")) {
			// Upscaling left on from an earlier session: the sky renderer is built while the world renders small.
			context.runOnClient(client -> {
				MetalSettings.get().upscaling = MetalSettings.Upscaling.SPATIAL;
				MetalSettings.get().renderScale = 60;
			});
		}

		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.adjustSettings(settings -> {
					settings.setWorldType(settings.getNormalPresetList().getFirst());
					settings.setSeed(SEED);
				})
				.create()) {
			singleplayer.getConnection().waitForChunksRender();
			context.runOnClient(client -> MetalSettings.get().upscaling = MetalSettings.Upscaling.OFF);
			context.waitTicks(5);
			context.takeScreenshot("mine2metal-world");

			// Creative flight keeps the player where the camera is put instead of falling to its death.
			singleplayer.getServer().runCommand("gamemode creative @a");
			context.waitTicks(5);
			context.runOnClient(client -> {
				client.player.getAbilities().flying = true;
				client.player.onUpdateAbilities();
			});

			// From above, looking down: lots of chunks, foliage (cutout) and usually water (translucent).
			singleplayer.getServer().runCommand("tp @a ~ ~40 ~");
			context.waitTicks(20);
			context.getInput().lookAt(0.0F, 50.0F);
			Vec3 above = context.computeOnClient(client -> client.player.position());
			String returnAbove = String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f 0 50", above.x, above.y, above.z);
			singleplayer.getConnection().waitForChunksRender();
			context.takeScreenshot("mine2metal-world-above");
			LOGGER.info("Average FPS from above (vsync off, uncapped): {}", averageFps(context));

			if (Boolean.getBoolean("mine2metal.test.pacing")) {
				measurePacing(context, false);
				measurePacing(context, true);
				singleplayer.getServer().runCommand(returnAbove);
			}

			if (backend.equals("Metal")) {
				checkSpatialUpscaling(context, singleplayer, returnAbove);
			}

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

	/**
	 * Walks Options > Video Settings to the Metal options: Sodium's page when Sodium is installed, otherwise the
	 * "Metal Settings..." button and screen, then returns to the title screen.
	 */
	private static void openSettingsScreens(ClientGameTestContext context) {
		context.clickScreenButton("menu.options");
		context.clickScreenButton("options.video");
		context.waitTicks(5);
		if (FabricLoader.getInstance().isModLoaded("sodium")) {
			// Sodium draws its own sidebar, which game tests cannot click; the screenshot shows the "mine2metal" entry
			// and its "Metal" page under Sodium's pages.
			context.takeScreenshot("mine2metal-video-settings");
		} else {
			String label = Component.translatable("mine2metal.options.button").getString();
			boolean present = context.computeOnClient(client -> findWidget(client.gui.screen(), label) != null);
			check(present, "Vanilla video settings should have a " + label + " button");
			context.setScreen(() -> new MetalSettingsScreen(new TitleScreen(), Minecraft.getInstance().options));
		}
		context.waitTicks(5);
		if (!FabricLoader.getInstance().isModLoaded("sodium")) {
			context.takeScreenshot("mine2metal-settings");
		}
		context.setScreen(TitleScreen::new);
		context.waitTicks(5);
	}

	private static @Nullable AbstractWidget findWidget(GuiEventListener listener, String message) {
		if (listener instanceof AbstractWidget widget && widget.getMessage().getString().equals(message)) {
			return widget;
		}
		if (listener instanceof ContainerEventHandler container) {
			for (GuiEventListener child : container.children()) {
				AbstractWidget found = findWidget(child, message);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/** Renders the same view through MetalFX spatial upscaling at two scales, then switches it off again. */
	private static void checkSpatialUpscaling(ClientGameTestContext context, TestSingleplayerContext singleplayer, String returnAbove) {
		MetalSettings settings = MetalSettings.get();
		for (int scale : new int[] {50, 75}) {
			context.runOnClient(client -> {
				settings.upscaling = MetalSettings.Upscaling.SPATIAL;
				settings.renderScale = scale;
			});
			singleplayer.getServer().runCommand(returnAbove);
			context.waitTicks(10);
			context.takeScreenshot("mine2metal-spatial-" + scale);
			LOGGER.info("Average FPS from above with MetalFX spatial at {}%: {}", scale, averageFps(context));
		}
		// Players flip these while the world keeps rendering behind the settings screen; the scaled target is created,
		// resized and destroyed under them. Includes a window resize with upscaling on.
		for (int i = 0; i < 30; i++) {
			int step = i;
			context.runOnClient(client -> {
				settings.upscaling = step % 3 == 2 ? MetalSettings.Upscaling.OFF : MetalSettings.Upscaling.SPATIAL;
				settings.renderScale = MetalSettings.clampScale(50 + step * 7 % 50);
			});
			if (step == 10) {
				context.getInput().resizeWindow(1100, 700);
			}
			context.waitTicks(1);
		}
		context.getInput().resizeWindow(854, 480);

		// The same with a menu open, as players do it: the world keeps rendering behind the blurred settings screen.
		context.setScreen(() -> new MetalSettingsScreen(null, Minecraft.getInstance().options));
		for (int i = 0; i < 20; i++) {
			int step = i;
			context.runOnClient(client -> {
				settings.upscaling = step % 2 == 0 ? MetalSettings.Upscaling.SPATIAL : MetalSettings.Upscaling.OFF;
				settings.renderScale = MetalSettings.clampScale(50 + step * 11 % 50);
			});
			context.waitTicks(2);
		}
		context.setScreen(() -> null);
		context.runOnClient(client -> settings.upscaling = MetalSettings.Upscaling.OFF);
		context.waitTicks(10);
	}

	/** Two timestamps around a clear: both arrive, in order, and less than a second apart. */
	private static void checkTimestamps(ClientGameTestContext context) {
		GpuQueryPool pool = context.computeOnClient(client -> {
			GpuDevice device = RenderSystem.getDevice();
			GpuQueryPool created = device.createTimestampQueryPool(2);
			CommandEncoder encoder = device.createCommandEncoder();
			try (GpuTexture texture = device.createTexture("mine2metal timestamp test", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 256, 256,
					1, 1)) {
				encoder.writeTimestamp(created, 0);
				encoder.clearColorTexture(texture, new Vector4f(1.0F, 0.0F, 0.0F, 1.0F));
				encoder.writeTimestamp(created, 1);
			}
			return created;
		});
		context.waitTicks(5);
		OptionalLong[] values = context.computeOnClient(client -> pool.getValues(0, 2));
		context.runOnClient(client -> pool.close());
		check(values[0].isPresent() && values[1].isPresent(), "Timestamps should be available a few ticks after they were written");
		long elapsed = values[1].getAsLong() - values[0].getAsLong();
		check(elapsed >= 0L && elapsed < 1_000_000_000L, "Timestamps should be ordered and close together, got " + elapsed + " ns apart");
		LOGGER.info("Timestamp checks passed ({} ns apart)", elapsed);
	}

	/**
	 * A partial clear, as the GUI item atlas does for a single slot, changes only its region: rows keep GL's bottom-up
	 * order, so region row y is row y of the read-back image.
	 */
	private static void checkPartialClear(ClientGameTestContext context) {
		int size = 16;
		AtomicBoolean copied = new AtomicBoolean();
		GpuBuffer readback = context.computeOnClient(client -> {
			GpuDevice device = RenderSystem.getDevice();
			CommandEncoder encoder = device.createCommandEncoder();
			GpuBuffer buffer = device.createBuffer(() -> "mine2metal partial clear readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
					(long) size * size * 4);
			try (GpuTexture color = device.createTexture("mine2metal partial clear", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC
					| GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, size, size, 1, 1);
					GpuTexture depth = device.createTexture("mine2metal partial clear depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, size,
							size, 1, 1)) {
				encoder.clearColorAndDepthTextures(color, new Vector4f(1.0F, 0.0F, 0.0F, 1.0F), depth, 1.0);
				encoder.clearColorAndDepthTextures(color, new Vector4f(0.0F, 0.0F, 1.0F, 1.0F), depth, 0.0, 4, 8, 4, 4, 0);
				encoder.copyTextureToBuffer(color, buffer, 0L, () -> copied.set(true), 0);
			}
			return buffer;
		});
		for (int i = 0; i < 20 && !copied.get(); i++) {
			context.waitTicks(1);
		}
		check(copied.get(), "The partial clear readback should finish");
		int[] pixels = context.computeOnClient(client -> {
			try (GpuBufferSlice.MappedView view = readback.map(true, false)) {
				ByteBuffer data = view.data();
				int[] rgba = new int[size * size];
				for (int i = 0; i < rgba.length; i++) {
					rgba[i] = data.getInt(i * 4);
				}
				return rgba;
			} finally {
				readback.close();
			}
		});
		int red = pixels[0];
		int blue = pixels[9 * size + 5];
		check(red != blue, "The cleared region should differ from the rest");
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				boolean inside = x >= 4 && x < 8 && y >= 8 && y < 12;
				check(pixels[y * size + x] == (inside ? blue : red), "Pixel " + x + "," + y + " should be " + (inside ? "cleared" : "kept"));
			}
		}
		LOGGER.info("Partial clear checks passed");
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
			MetalSettings.reload();
			try {
				check(!PreferredGraphicsApi.DEFAULT.getBackendsToTry()[0].getName().equals("Metal"), "enabled=false should skip Metal");
			} finally {
				Files.writeString(config, original);
				MetalSettings.reload();
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

	/**
	 * Turns the camera steadily while flying forward for ten seconds, which is when uneven frame pacing shows; the
	 * numbers are in the frame stats ({@code -Dmine2metal.stats=true}) logged during the segment.
	 */
	private static void measurePacing(ClientGameTestContext context, boolean vsync) {
		context.runOnClient(client -> {
			client.options.enableVsync().set(vsync);
			client.options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF);
			client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		});
		context.waitTicks(20);
		LOGGER.info("Pacing segment start (vsync {})", vsync);
		context.getInput().holdKey(options -> options.keyUp);
		for (int tick = 0; tick < 200; tick++) {
			context.getInput().lookAt(tick * 6.0F, 15.0F);
			context.waitTick();
		}
		context.getInput().releaseKey(options -> options.keyUp);
		LOGGER.info("Pacing segment end (vsync {})", vsync);
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
		String limits = context.<String, RuntimeException>computeOnClient(client -> "framerateLimit=" + client.options.framerateLimit().get()
				+ ", vsync=" + client.options.enableVsync().get() + ", inactivity=" + client.options.inactivityFpsLimit().get() + ", screen="
				+ client.gui.screen());
		LOGGER.info("Frame limits during measurement: {}", limits);
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
