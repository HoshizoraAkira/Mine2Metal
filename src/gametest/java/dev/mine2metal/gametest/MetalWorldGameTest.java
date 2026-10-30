package dev.mine2metal.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.mine2metal.MetalAvailability;
import dev.mine2metal.metal.MetalRenderer;
import org.joml.Vector4f;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import com.mojang.renderpearl.api.device.GpuBackend;
import dev.mine2metal.config.MetalSettings;
import dev.mine2metal.frame.WorldPass;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;
import dev.mine2metal.config.MetalSettingsScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
		if (Boolean.getBoolean("mine2metal.test.fallbackOnly")) {
			// Run with -Dos.version=13.6: the backend must step aside, and the title screen say why.
			check(!backend.equals("Metal"), "Metal should not be used on macOS " + System.getProperty("os.version"));
			check(context.computeOnClient(client -> MetalAvailability.reason() != null), "The reason Metal is not used should be recorded");
			context.waitTicks(20);
			context.takeScreenshot("mine2metal-fallback-title");
			LOGGER.info("Fell back to {}: {}", backend, context.computeOnClient(client -> MetalAvailability.reason().getString()));
			return;
		}
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

			if (Boolean.getBoolean("mine2metal.test.walkOnly")) {
				checkWalking(context, singleplayer, above);
				return;
			}
			if (Boolean.getBoolean("mine2metal.test.pacing")) {
				measurePacing(context, false);
				measurePacing(context, true);
				singleplayer.getServer().runCommand(returnAbove);
			}

			if (Boolean.getBoolean("mine2metal.test.temporalOnly")) {
				checkTemporalUpscaling(context, singleplayer, above);
				return;
			}
			if (Boolean.getBoolean("mine2metal.test.reactiveOnly")) {
				checkReactiveMask(context, singleplayer, above);
				return;
			}
			if (Boolean.getBoolean("mine2metal.test.interpolationOnly")) {
				checkFrameInterpolation(context, singleplayer, above);
				return;
			}
			if (Boolean.getBoolean("mine2metal.test.chunkSpikes")) {
				measureChunkSpikes(context, singleplayer, backend);
				return;
			}
			if (Boolean.getBoolean("mine2metal.test.interpolationPerf")) {
				measureInterpolationCost(context, singleplayer);
				return;
			}
			if (Boolean.getBoolean("mine2metal.test.hdrFlicker")) {
				measureHdrFlicker(context, singleplayer, above, false, MetalSettings.Upscaling.OFF);
				measureHdrFlicker(context, singleplayer, above, true, MetalSettings.Upscaling.OFF);
				measureHdrFlicker(context, singleplayer, above, true, MetalSettings.Upscaling.TEMPORAL);
				return;
			}
			if (Boolean.getBoolean("mine2metal.test.hdrOnly")) {
				checkHdr(context, singleplayer, above);
				return;
			}
			if (Boolean.getBoolean("mine2metal.test.shadersOnly")) {
				checkShaders(context, singleplayer, above);
				return;
			}
			if (backend.equals("Metal")) {
				checkSpatialUpscaling(context, singleplayer, returnAbove);
				checkTemporalUpscaling(context, singleplayer, above);
				checkReactiveMask(context, singleplayer, above);
				checkFrameInterpolation(context, singleplayer, above);
				checkHdr(context, singleplayer, above);
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
			checkSodiumPresetSync(context);
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

	/**
	 * Temporal upscaling against native resolution, by MetalFX and by FSR 3.1, for each sign of the jitter the scaler is
	 * told about: still, where only the jitter matters, and right after a turn, where the motion vectors must carry the
	 * history along. Then the spatial upscalers, MetalFX and FSR 1, still. The screenshots are compared afterwards (PSNR
	 * against the native ones).
	 */
	private static void checkTemporalUpscaling(ClientGameTestContext context, TestSingleplayerContext singleplayer, Vec3 above) {
		MetalSettings settings = MetalSettings.get();
		String still = String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f 0 50", above.x, above.y, above.z);
		String turned = String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f 30 50", above.x, above.y, above.z);

		context.runOnClient(client -> settings.upscaling = MetalSettings.Upscaling.OFF);
		hover(context, singleplayer, turned);
		context.takeScreenshot("mine2metal-native-turned");
		hover(context, singleplayer, still);
		context.takeScreenshot("mine2metal-native-still");

		String[] signs = Boolean.getBoolean("mine2metal.test.jitterSigns") ? new String[] {"1,1", "-1,-1", "-1,1", "1,-1"} : new String[] {"1,1"};
		for (MetalSettings.Upscaling engine : new MetalSettings.Upscaling[] {MetalSettings.Upscaling.TEMPORAL, MetalSettings.Upscaling.FSR3}) {
			if (engine == MetalSettings.Upscaling.FSR3 && !MetalRenderer.supportsFsrUpscaling()) {
				continue;
			}
			String prefix = engine == MetalSettings.Upscaling.TEMPORAL ? "mine2metal-temporal-50-" : "mine2metal-fsr3-50-";
			for (String sign : signs) {
				String name = sign.replace(",", "_").replace("-1", "m").replace("1", "p");
				System.setProperty("mine2metal.temporal.jitterSign", sign);
				context.runOnClient(client -> {
					settings.upscaling = engine;
					settings.renderScale = 50;
				});
				hover(context, singleplayer, still);
				context.takeScreenshot(prefix + "still-" + name);
				// Turn by 3 degrees a tick, then capture right away: stale history shows as ghosting.
				for (int yaw = 3; yaw <= 30; yaw += 3) {
					singleplayer.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f %d 50", above.x, above.y, above.z, yaw));
					context.waitTicks(1);
				}
				context.takeScreenshot(prefix + "turned-" + name);
				LOGGER.info("Average FPS from above with {} at 50% (jitter sign {}): {}", engine, sign, averageFps(context));
			}
		}
		System.clearProperty("mine2metal.temporal.jitterSign");

		for (MetalSettings.Upscaling engine : new MetalSettings.Upscaling[] {MetalSettings.Upscaling.SPATIAL, MetalSettings.Upscaling.FSR1}) {
			if (engine == MetalSettings.Upscaling.FSR1 && !MetalRenderer.supportsFsrUpscaling()) {
				continue;
			}
			context.runOnClient(client -> {
				settings.upscaling = engine;
				settings.renderScale = 50;
			});
			hover(context, singleplayer, still);
			context.takeScreenshot("mine2metal-" + engine.name().toLowerCase(Locale.ROOT) + "-50-still");
			LOGGER.info("Average FPS from above with {} at 50%: {}", engine, averageFps(context));
		}

		for (int scale : new int[] {75, 100}) {
			context.runOnClient(client -> {
				settings.upscaling = MetalSettings.Upscaling.TEMPORAL;
				settings.renderScale = scale;
			});
			hover(context, singleplayer, still);
			context.takeScreenshot("mine2metal-temporal-" + scale + "-still");
			LOGGER.info("Average FPS from above with MetalFX temporal at {}%: {}", scale, averageFps(context));
		}
		context.runOnClient(client -> settings.upscaling = MetalSettings.Upscaling.OFF);
		context.waitTicks(10);
	}

	/**
	 * A gold block crossing the view (smoothed over three ticks, so it still moves in the screenshot frame), natively and through temporal upscaling at 50% with and without
	 * the reactive mask. Each run also has a screenshot without the block; what differs between the two, compared
	 * afterwards, is the block and any trail it leaves in the history. At 60 fps: uncapped, the test window runs so fast
	 * that the block moves a fraction of a pixel a frame.
	 */
	private static void checkReactiveMask(ClientGameTestContext context, TestSingleplayerContext singleplayer, Vec3 above) {
		MetalSettings settings = MetalSettings.get();
		int framerateLimit = context.computeOnClient(client -> client.options.framerateLimit().get());
		context.runOnClient(client -> client.options.framerateLimit().set(60));
		String still = String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f 0 50", above.x, above.y, above.z);
		// About 25 blocks along the view, which looks 50 degrees down to +z.
		double y = above.y - 19.0;
		double z = above.z + 16.0;
		String[][] cases = {{"native", "off", "true"}, {"temporal-mask", "temporal", "true"}, {"temporal-nomask", "temporal", "false"}};
		for (String[] mode : cases) {
			System.setProperty("mine2metal.temporal.reactiveMask", mode[2]);
			context.runOnClient(client -> {
				settings.upscaling = mode[1].equals("temporal") ? MetalSettings.Upscaling.TEMPORAL : MetalSettings.Upscaling.OFF;
				settings.renderScale = 50;
			});
			hover(context, singleplayer, still);
			context.takeScreenshot("mine2metal-reactive-" + mode[0] + "-empty");
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "summon minecraft:block_display %.3f %.3f %.3f "
					+ "{block_state:\"minecraft:gold_block\",teleport_duration:3,Tags:[\"mine2metal\"],transformation:{left_rotation:[0f,0f,0f,1f],"
					+ "right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[3f,3f,3f]}}", above.x - 24.0, y, z));
			context.waitTicks(5);
			// Three blocks a tick, about seven pixels a frame at the render resolution; the screenshot is taken halfway.
			for (int step = -21; step <= 24; step += 3) {
				singleplayer.getServer().runCommand(String.format(Locale.ROOT, "tp @e[tag=mine2metal] %.3f %.3f %.3f", above.x + step, y, z));
				context.waitTicks(1);
				if (step == 0) {
					context.takeScreenshot("mine2metal-reactive-" + mode[0] + "-moving");
				}
			}
			singleplayer.getServer().runCommand("kill @e[tag=mine2metal]");
			context.waitTicks(2);
			// Particles write no depth, so only the colour clamp of the scaler keeps them from trailing.
			for (int tick = 0; tick < 20; tick++) {
				singleplayer.getServer().runCommand(String.format(Locale.ROOT, "particle minecraft:end_rod %.3f %.3f %.3f 3 1 3 0.4 30 force", above.x, y, z));
				context.waitTicks(1);
			}
			context.takeScreenshot("mine2metal-reactive-" + mode[0] + "-particles");
			context.waitTicks(40);
		}
		System.clearProperty("mine2metal.temporal.reactiveMask");
		context.runOnClient(client -> {
			settings.upscaling = MetalSettings.Upscaling.OFF;
			client.options.framerateLimit().set(framerateLimit);
		});
		context.waitTicks(10);
	}

	/**
	 * Frame interpolation against native frames: the camera turns from yaw 0 to 4 between two frames, and the frame
	 * generated between them is read back and later compared with a native frame at yaw 2 (and, as baselines, the two
	 * real frames). Without upscaling and with temporal upscaling at 50%, for each motion vector scale tried.
	 */
	private static void checkFrameInterpolation(ClientGameTestContext context, TestSingleplayerContext singleplayer, Vec3 above) {
		MetalSettings settings = MetalSettings.get();
		boolean metalFx = context.computeOnClient(client -> MetalRenderer.supportsFrameInterpolation());
		boolean fsr = context.computeOnClient(client -> MetalRenderer.supportsFsrFrameGeneration());
		if (!metalFx && !fsr) {
			LOGGER.info("Frame generation is not supported here; skipped");
			return;
		}
		Path screenshots = FabricLoader.getInstance().getGameDir().resolve("screenshots");
		// A covered test window renders uncapped, which the frame generation guard would rightly pause.
		System.setProperty("mine2metal.frameGenerationGuard", "false");
		context.runOnClient(client -> {
			settings.upscaling = MetalSettings.Upscaling.OFF;
			settings.frameGeneration = MetalSettings.FrameGeneration.OFF;
		});
		for (int yaw : new int[] {0, 2, 4}) {
			hover(context, singleplayer, String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f %d 50", above.x, above.y, above.z, yaw));
			context.takeScreenshot("mine2metal-interpolation-native-yaw" + yaw);
		}

		// Engine, upscaling, motion vector scale. -Dmine2metal.test.motionScales tries the other signs and scales, to
		// calibrate each engine's motion vector convention.
		java.util.List<String[]> cases = new java.util.ArrayList<>();
		boolean calibrate = Boolean.getBoolean("mine2metal.test.motionScales");
		if (metalFx) {
			cases.add(new String[] {"metalfx", "off", "1,1"});
			cases.add(new String[] {"metalfx", "temporal", "1,1"});
		}
		if (fsr) {
			cases.add(new String[] {"fsr3", "off", "1,1"});
			cases.add(new String[] {"fsr3", "temporal", "1,1"});
			if (calibrate) {
				for (String scale : new String[] {"-1,-1", "1,-1", "-1,1"}) {
					cases.add(new String[] {"fsr3", "off", scale});
				}
			}
		}
		for (String[] testCase : cases) {
			MetalSettings.FrameGeneration engine = testCase[0].equals("fsr3") ? MetalSettings.FrameGeneration.FSR3 : MetalSettings.FrameGeneration.METALFX;
			MetalSettings.Upscaling upscaling = testCase[1].equals("off") ? MetalSettings.Upscaling.OFF : MetalSettings.Upscaling.TEMPORAL;
			String name = testCase[0] + "-" + testCase[1] + "-" + testCase[2].replace(",", "_").replace("-", "m");
			System.setProperty("mine2metal.interpolation.motionScale", testCase[2]);
			context.runOnClient(client -> {
				settings.upscaling = upscaling;
				settings.renderScale = 50;
				settings.frameGeneration = engine;
			});
			hover(context, singleplayer, String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f 0 50", above.x, above.y, above.z));
			LOGGER.info("Average FPS from above with frame generation ({}): {}", name, averageFps(context));
			checkFramesOnScreenLine(context, name);

			AtomicBoolean finished = new AtomicBoolean();
			AtomicBoolean written = new AtomicBoolean();
			Path file = screenshots.resolve("mine2metal-interpolation-" + name + ".png");
			context.runOnClient(client -> {
				// Both the rotation and the one interpolated from, so the next frame is the first at yaw 4.
				client.player.setYRot(4.0F);
				client.player.yRotO = 4.0F;
				MetalRenderer.dumpNextInterpolatedFrame(file, client.gameRenderer.mainRenderTarget().width, client.gameRenderer.mainRenderTarget().height, ok -> {
					written.set(ok);
					finished.set(true);
				});
			});
			context.waitFor(client -> finished.get());
			check(written.get(), "No generated frame to read back for " + name);
			LOGGER.info("Wrote the generated frame for {} to {}", name, file);
		}
		System.clearProperty("mine2metal.interpolation.motionScale");
		System.clearProperty("mine2metal.frameGenerationGuard");
		context.runOnClient(client -> {
			settings.upscaling = MetalSettings.Upscaling.OFF;
			settings.frameGeneration = MetalSettings.FrameGeneration.OFF;
		});
		context.waitTicks(10);
	}

	/**
	 * Development aid: with the camera still, reads back consecutive EDR frames and counts the pixels that change
	 * between them, split by whether they are expanded above SDR white, plus the display headroom on each.
	 */
	private static void measureHdrFlicker(ClientGameTestContext context, TestSingleplayerContext singleplayer, Vec3 above, boolean ignoreHeadroom,
			MetalSettings.Upscaling upscaling) {
		MetalSettings settings = MetalSettings.get();
		if (!context.computeOnClient(client -> MetalRenderer.supportsHdr())) {
			LOGGER.info("This display has no EDR; HDR flicker skipped");
			return;
		}
		if (ignoreHeadroom) {
			System.setProperty("mine2metal.hdr.ignoreDisplayHeadroom", "true");
		} else {
			System.clearProperty("mine2metal.hdr.ignoreDisplayHeadroom");
		}
		singleplayer.getServer().runCommand("time set noon");
		// A bright textured floor under the camera, which the expansion reaches.
		int floor = (int) Math.floor(above.y) - 12;
		singleplayer.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:sand", (int) above.x - 25, floor,
				(int) above.z - 5, (int) above.x + 25, floor, (int) above.z + 30));
		context.runOnClient(client -> {
			settings.upscaling = upscaling;
			settings.renderScale = 75;
			settings.frameGeneration = MetalSettings.FrameGeneration.OFF;
			settings.hdr = true;
			settings.hdrBrightness = 200;
			MetalRenderer.setHdr(true, 200);
		});
		hover(context, singleplayer, String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f 0 50", above.x, above.y, above.z));
		context.waitTicks(40);
		float[] previous = null;
		for (int i = 0; i < 12; i++) {
			AtomicBoolean finished = new AtomicBoolean();
			float[][] frame = new float[1][];
			float[] headroom = new float[1];
			context.runOnClient(client -> {
				headroom[0] = MetalRenderer.hdrHeadroom();
				MetalRenderer.readNextHdrFrame(client.gameRenderer.mainRenderTarget().width, client.gameRenderer.mainRenderTarget().height, values -> {
					frame[0] = values;
					finished.set(true);
				});
			});
			context.waitFor(client -> finished.get());
			float[] current = frame[0];
			float peak = 0.0F;
			int changedSdr = 0;
			int changedExpanded = 0;
			float largest = 0.0F;
			double sumExpanded = 0.0;
			double sumBright = 0.0;
			int expanded = 0;
			int bright = 0;
			for (int j = 0; j < current.length; j += 4) {
				float value = Math.max(current[j], Math.max(current[j + 1], current[j + 2]));
				peak = Math.max(peak, value);
				if (previous != null) {
					float before = Math.max(previous[j], Math.max(previous[j + 1], previous[j + 2]));
					float change = Math.abs(value - before) / Math.max(0.05F, Math.max(value, before));
					largest = Math.max(largest, change);
					float most = Math.max(value, before);
					float least = Math.min(value, before);
					if (least > 1.05F) {
						sumExpanded += change;
						expanded++;
					} else if (least > 0.3F && most < 0.95F) {
						sumBright += change;
						bright++;
					}
					if (change > 0.03F) {
						if (Math.max(value, before) > 1.0F) {
							changedExpanded++;
						} else {
							changedSdr++;
						}
					}
				}
			}
			LOGGER.info("HDR flicker ({}, ignore headroom {}), frame {}: headroom {}, peak {}, changed >3%: {} SDR / {} expanded of {}, largest {}; "
					+ "mean change {} over {} expanded, {} over {} bright SDR", upscaling, ignoreHeadroom, i, headroom[0], peak, changedSdr, changedExpanded,
					current.length / 4, largest, expanded > 0 ? sumExpanded / expanded : 0.0, expanded, bright > 0 ? sumBright / bright : 0.0, bright);
			previous = current;
		}
		System.clearProperty("mine2metal.hdr.ignoreDisplayHeadroom");
		context.runOnClient(client -> {
			settings.hdr = false;
			MetalRenderer.setHdr(false, settings.hdrBrightness);
		});
		context.waitTicks(10);
	}

	/**
	 * The debug screen's fps entry gains a line with the frames shown per second, which frame generation doubles. A
	 * covered test window may show none, so only the line itself is checked.
	 */
	private static void checkFramesOnScreenLine(ClientGameTestContext context, String name) {
		java.util.List<String> lines = new java.util.ArrayList<>();
		DebugScreenDisplayer displayer = new DebugScreenDisplayer() {
			@Override
			public void addPriorityLine(String line) {
				lines.add(line);
			}

			@Override
			public void addLine(String line) {
				lines.add(line);
			}

			@Override
			public void addToGroup(Identifier group, java.util.Collection<String> groupLines) {
				lines.addAll(groupLines);
			}

			@Override
			public void addToGroup(Identifier group, String line) {
				lines.add(line);
			}
		};
		// The rate is counted over a second, from the first time the line is asked for.
		context.runOnClient(client -> new DebugEntryFps().display(displayer, client.level, null, null));
		context.waitTicks(30);
		context.runOnClient(client -> {
			lines.clear();
			new DebugEntryFps().display(displayer, client.level, null, null);
		});
		String shown = lines.stream().filter(line -> line.contains("fps on screen")).findFirst().orElse(null);
		check(shown != null, "No frames-on-screen line on the debug screen with frame generation (" + name + "): " + lines);
		LOGGER.info("Debug screen with frame generation ({}): {}", name, shown);
	}

	/**
	 * EDR output: reads back the composed frame and checks that the world's highlights go above SDR white while the
	 * hotbar stays at it, alone and together with temporal upscaling and frame interpolation. The display's headroom is
	 * ignored so the result does not depend on its brightness setting.
	 */
	private static void checkHdr(ClientGameTestContext context, TestSingleplayerContext singleplayer, Vec3 above) {
		MetalSettings settings = MetalSettings.get();
		if (!context.computeOnClient(client -> MetalRenderer.supportsHdr())) {
			LOGGER.info("This display has no EDR; HDR checks skipped");
			return;
		}
		System.setProperty("mine2metal.hdr.ignoreDisplayHeadroom", "true");
		System.setProperty("mine2metal.frameGenerationGuard", "false");
		String still = String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f 0 50", above.x, above.y, above.z);
		boolean interpolation = context.computeOnClient(client -> MetalRenderer.supportsFrameInterpolation());
		boolean fsr = context.computeOnClient(client -> MetalRenderer.supportsFsrFrameGeneration());
		java.util.List<String> cases = new java.util.ArrayList<>(java.util.List.of("plain"));
		if (interpolation) {
			cases.add("temporal+interpolation");
		}
		if (fsr) {
			cases.add("temporal+fsr3");
		}
		for (String name : cases) {
			boolean combined = !name.equals("plain");
			context.runOnClient(client -> {
				settings.upscaling = combined ? MetalSettings.Upscaling.TEMPORAL : MetalSettings.Upscaling.OFF;
				settings.renderScale = 50;
				settings.frameGeneration = !combined ? MetalSettings.FrameGeneration.OFF
						: name.contains("fsr") ? MetalSettings.FrameGeneration.FSR3 : MetalSettings.FrameGeneration.METALFX;
				settings.hdr = true;
				settings.hdrBrightness = 300;
				MetalRenderer.setHdr(true, 300);
			});
			hover(context, singleplayer, still);
			LOGGER.info("Average FPS from above with HDR ({}): {}", name, averageFps(context));

			AtomicBoolean finished = new AtomicBoolean();
			float[][] frame = new float[1][];
			int[] size = new int[3];
			context.runOnClient(client -> {
				size[0] = client.gameRenderer.mainRenderTarget().width;
				size[1] = client.gameRenderer.mainRenderTarget().height;
				size[2] = client.getWindow().getGuiScale();
				MetalRenderer.readNextHdrFrame(size[0], size[1], values -> {
					frame[0] = values;
					finished.set(true);
				});
			});
			context.waitFor(client -> finished.get());
			check(frame[0] != null, "No HDR frame to read back (" + name + ")");

			int width = size[0];
			int height = size[1];
			// Raw little-endian floats, for looking at afterwards.
			ByteBuffer raw = ByteBuffer.allocate(frame[0].length * 4).order(ByteOrder.LITTLE_ENDIAN);
			raw.asFloatBuffer().put(frame[0]);
			try {
				Files.write(FabricLoader.getInstance().getGameDir().resolve("screenshots")
						.resolve("mine2metal-hdr-" + name.replace('+', '-') + "-" + width + "x" + height + ".rgba32f"), raw.array());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			// Rows are bottom-up: the hotbar (22 GUI pixels tall, 182 wide) is at the first rows.
			float hotbar = brightest(frame[0], width, width / 2 - 91 * size[2], width / 2 + 91 * size[2], 0, 22 * size[2]);
			float world = brightest(frame[0], width, 0, width, height / 2, height);
			LOGGER.info("HDR frame ({}): brightest world {} and hotbar {} times SDR white", name, world, hotbar);
			check(world > 1.5F, "The world's highlights should go above SDR white (" + name + ")");
			check(hotbar <= 1.01F, "The hotbar should stay at SDR brightness (" + name + ")");
		}
		System.clearProperty("mine2metal.hdr.ignoreDisplayHeadroom");
		System.clearProperty("mine2metal.frameGenerationGuard");
		context.runOnClient(client -> {
			settings.upscaling = MetalSettings.Upscaling.OFF;
			settings.frameGeneration = MetalSettings.FrameGeneration.OFF;
			settings.hdr = false;
			MetalRenderer.setHdr(false, settings.hdrBrightness);
		});
		context.waitTicks(10);
	}

	/**
	 * Frame rate in a heavy scene with frame interpolation, spatial upscaling and HDR in turn: 1920x1080, render distance
	 * 16, on the ground walking towards the trees, so chunks keep loading and frames hitch now and then. With
	 * {@code -Dmine2metal.stats=true} the log has GPU and present time per frame for each.
	 */
	private static void measureInterpolationCost(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		MetalSettings settings = MetalSettings.get();
		String[] size = System.getProperty("mine2metal.test.perfSize", "1920x1080").split("x");
		context.getInput().resizeWindow(Integer.parseInt(size[0]), Integer.parseInt(size[1]));
		// Fullscreen as players run it: on a scaled Retina desktop that is far more pixels than any test window.
		boolean fullscreen = Boolean.getBoolean("mine2metal.test.perfFullscreen");
		context.waitTicks(5);
		LOGGER.info("Window {}x{} on screen, framebuffer {}x{}, pixel density {}", context.computeOnClient(client -> client.getWindow().getScreenWidth()),
				context.computeOnClient(client -> client.getWindow().getScreenHeight()), context.computeOnClient(client -> client.getWindow().getWidth()),
				context.computeOnClient(client -> client.getWindow().getHeight()), context.computeOnClient(client -> client.getWindow().getPixelDensity()));
		if (fullscreen) {
			context.runOnClient(client -> client.options.fullscreen().set(true));
			context.waitTicks(40);
			LOGGER.info("Fullscreen: {}x{} pixels", context.computeOnClient(client -> client.getWindow().getWidth()),
					context.computeOnClient(client -> client.getWindow().getHeight()));
		}
		context.runOnClient(client -> client.options.renderDistance().set(16));
		// Down from the view above: the teleport ends flight, so the player falls back to the ground.
		singleplayer.getServer().runCommand("tp @a ~ ~-38 ~");
		context.waitTicks(60);
		Vec3 ground = context.computeOnClient(client -> client.player.position());
		String start = String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f 0 5", ground.x, ground.y, ground.z);
		singleplayer.getServer().runCommand(start);
		context.waitTicks(45 * 20);
		String[] cases = System.getProperty("mine2metal.test.perfCases", "off,interpolation,interpolation+spatial75,interpolation+spatial75+hdr,hdr,off")
				.split(",");
		for (String name : cases) {
			context.runOnClient(client -> {
				settings.frameGeneration = name.contains("fsr") ? MetalSettings.FrameGeneration.FSR3
						: name.contains("interpolation") ? MetalSettings.FrameGeneration.METALFX : MetalSettings.FrameGeneration.OFF;
				settings.upscaling = name.contains("spatial") ? MetalSettings.Upscaling.SPATIAL
						: name.contains("temporal") ? MetalSettings.Upscaling.TEMPORAL : MetalSettings.Upscaling.OFF;
				settings.renderScale = name.contains("50") ? 50 : 75;
				settings.hdr = name.contains("hdr");
				MetalRenderer.setHdr(settings.hdr, settings.hdrBrightness);
				MetalRenderer.setPerformanceHud(name.contains("hud"));
			});
			singleplayer.getServer().runCommand(start);
			context.waitTicks(20);
			LOGGER.info("Measuring {} at {}x{} pixels", name, context.computeOnClient(client -> client.gameRenderer.mainRenderTarget().width),
					context.computeOnClient(client -> client.gameRenderer.mainRenderTarget().height));
			context.runOnClient(client -> client.options.keyUp.setDown(true));
			int fps = averageFps(context);
			context.runOnClient(client -> client.options.keyUp.setDown(false));
			LOGGER.info("Average FPS, render distance 16, walking towards the trees, {}: {}", name, fps);
			context.runOnClient(client -> WorldPass.frameGenerationDebugLine());
			context.waitTicks(30);
			LOGGER.info("Debug screen line for {}: {}", name, context.computeOnClient(client -> WorldPass.frameGenerationDebugLine()));
		}
		context.runOnClient(client -> {
			settings.frameGeneration = MetalSettings.FrameGeneration.OFF;
			settings.upscaling = MetalSettings.Upscaling.OFF;
			settings.hdr = false;
			MetalRenderer.setHdr(false, settings.hdrBrightness);
			MetalRenderer.setPerformanceHud(false);
			if (fullscreen) {
				client.options.fullscreen().set(false);
			}
		});
	}

	/**
	 * Frame times while chunks load, comparable across graphics backends (Metal, or OpenGL / Vulkan with
	 * -Dmine2metal.enabled=false and preferredGraphicsBackend in options.txt): 1920x1080, render distance 16, vsync off,
	 * first standing on the ground for 20 seconds, then walking towards the trees for 40, which keeps new chunks
	 * coming in.
	 */
	private static void measureChunkSpikes(ClientGameTestContext context, TestSingleplayerContext singleplayer, String backend) {
		context.getInput().resizeWindow(1920, 1080);
		context.runOnClient(client -> {
			client.options.renderDistance().set(16);
			client.options.enableVsync().set(false);
			client.options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF);
			client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		});
		singleplayer.getServer().runCommand("tp @a ~ ~-38 ~");
		context.waitTicks(60);
		Vec3 ground = context.computeOnClient(client -> client.player.position());
		singleplayer.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f 0 5", ground.x, ground.y, ground.z));
		context.waitTicks(45 * 20);

		FrameTimes.start();
		context.waitTicks(20 * 20);
		LOGGER.info("Frame times on {}, standing: {}", backend, FrameTimes.stop());
		FrameTimes.start();
		context.runOnClient(client -> client.options.keyUp.setDown(true));
		context.waitTicks(40 * 20);
		context.runOnClient(client -> client.options.keyUp.setDown(false));
		LOGGER.info("Frame times on {}, walking towards the trees: {}", backend, FrameTimes.stop());
	}

	/** The largest channel value in a block of an RGBA float image. */
	private static float brightest(float[] rgba, int width, int left, int right, int bottom, int top) {
		float result = 0.0F;
		for (int y = bottom; y < top; y++) {
			for (int x = Math.max(0, left); x < Math.min(width, right); x++) {
				int i = (y * width + x) * 4;
				result = Math.max(result, Math.max(rgba[i], Math.max(rgba[i + 1], rgba[i + 2])));
			}
		}
		return result;
	}

	/**
	 * Teleports and keeps flying there, so screenshots taken the same number of ticks later show the same view. The
	 * teleport stops flight, so flying is switched on again after it.
	 */
	private static void hover(ClientGameTestContext context, TestSingleplayerContext singleplayer, String teleport) {
		singleplayer.getServer().runCommand(teleport);
		context.waitTicks(1);
		context.runOnClient(client -> {
			client.player.getAbilities().flying = true;
			client.player.onUpdateAbilities();
		});
		singleplayer.getServer().runCommand(teleport);
		context.waitTicks(30);
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

	/**
	 * The built-in shaders at a few times of day, from the ground and from above, with their frame rate against shaders
	 * off. The terrain must be lit from the G-buffer (Sodium's patched shader) rather than left as vanilla drew it.
	 */
	private static void checkShaders(ClientGameTestContext context, TestSingleplayerContext singleplayer, Vec3 above) {
		context.getInput().resizeWindow(1600, 900);
		context.runOnClient(client -> client.options.renderDistance().set(12));
		singleplayer.getServer().runCommand("time set noon");
		singleplayer.getServer().runCommand("weather clear");
		LOGGER.info("Average FPS from above, shaders off: {}", averageFps(context));

		for (MetalSettings.ShaderPreset preset : new MetalSettings.ShaderPreset[] {MetalSettings.ShaderPreset.HIGH, MetalSettings.ShaderPreset.ULTRA}) {
			String name = preset.name().toLowerCase(Locale.ROOT);
			context.runOnClient(client -> MetalSettings.get().applyShaderPreset(preset));
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f", above.x, above.y, above.z));
			keepFlying(context);
			context.getInput().lookAt(0.0F, 50.0F);
			context.waitTicks(40);
			check(context.computeOnClient(client -> WorldPass.lastFrameShaderLit()), "The " + name + " shaders should light the terrain from the G-buffer");
			context.takeScreenshot("mine2metal-shaders-" + name + "-above");
			LOGGER.info("Average FPS from above, shaders {}: {}", name, averageFps(context));

			// Standing a little above the ground, looking along it: shadows, foliage and the sky together.
			int ground = context.computeOnClient(client -> client.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(above.x),
					(int) Math.floor(above.z)));
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.3f %d %.3f", above.x, ground + 3, above.z));
			keepFlying(context);
			// A known shadow caster in front of the camera: a stone roof on a pillar, and a free-standing pillar.
			int bx = (int) Math.floor(above.x);
			int bz = (int) Math.floor(above.z);
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d stone", bx - 3, ground + 5, bz + 6, bx + 1, ground + 5, bz + 10));
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d stone", bx + 1, ground, bz + 10, bx + 1, ground + 4, bz + 10));
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d oak_log", bx + 4, ground, bz + 8, bx + 4, ground + 6, bz + 8));
			// A pool three blocks deep with a stone block in it, for the water.
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", bx - 3, ground, bz + 2, bx + 3, ground + 1, bz + 5));
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d water", bx - 3, ground - 3, bz + 2, bx + 3, ground - 1, bz + 5));
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "setblock %d %d %d stone", bx + 1, ground - 3, bz + 3));
			// Entities and a block entity next to the pool, standing still.
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "summon cow %d %d %d {NoAI:1b,Rotation:[150f,0f]}", bx - 2, ground, bz + 8));
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "summon villager %d %d %d {NoAI:1b,Rotation:[180f,0f]}", bx, ground, bz + 9));
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "summon creeper %d %d %d {NoAI:1b,Rotation:[200f,0f]}", bx + 2, ground, bz + 8));
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "setblock %d %d %d chest", bx + 3, ground, bz + 7));
			for (String item : new String[] {"diamond_sword", "grass_block", "oak_log"}) {
				singleplayer.getServer().runCommand(String.format(Locale.ROOT,
						"summon item %d %d %d {Item:{id:\"minecraft:%s\",count:1},PickupDelay:32767,Age:-32768}", bx - 1, ground, bz + 6, item));
			}
			// A lake eight blocks deep further on, to look around from under water.
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", bx - 8, ground, bz + 12, bx + 8, ground + 3, bz + 28));
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d water", bx - 8, ground - 8, bz + 12, bx + 8, ground - 1, bz + 28));
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d stone", bx - 1, ground - 8, bz + 20, bx + 1, ground - 5, bz + 22));
			context.getInput().lookAt(0.0F, 25.0F);
			context.waitTicks(40);
			context.takeScreenshot("mine2metal-shaders-" + name + "-noon");
			context.getInput().lookAt(0.0F, 12.0F);
			context.waitTicks(10);
			singleplayer.getServer().runCommand(String.format(Locale.ROOT,
					"particle minecraft:block{block_state:\"minecraft:redstone_block\"} %d %d %d 0.6 0.6 0.6 0 300 force", bx + 1, ground + 1, bz + 6));
			context.waitTicks(3);
			context.takeScreenshot("mine2metal-shaders-" + name + "-entities");
			if (preset == MetalSettings.ShaderPreset.HIGH) {
				checkShadersWithOtherFeatures(context);
				// Savanna never rains: plains around the camera (within the command's 32768 block limit), then rain until it
				// is at full strength.
				singleplayer.getServer().runCommand(String.format(Locale.ROOT, "fillbiome %d %d %d %d %d %d minecraft:plains",
						bx - 20, ground - 4, bz - 8, bx + 20, ground + 12, bz + 32));
				singleplayer.getServer().runCommand("weather rain");
				context.waitTicks(240);
				context.getInput().lookAt(0.0F, 30.0F);
				context.waitTicks(10);
				context.takeScreenshot("mine2metal-shaders-rain");
				context.getInput().lookAt(0.0F, 45.0F);
				context.waitTicks(10);
				context.takeScreenshot("mine2metal-shaders-rain-water");
				singleplayer.getServer().runCommand("weather clear");
				context.waitTicks(240);
				context.getInput().lookAt(0.0F, 12.0F);
			}
			context.getInput().lookAt(0.0F, 40.0F);
			context.waitTicks(20);
			context.takeScreenshot("mine2metal-shaders-" + name + "-water");
			context.getInput().lookAt(0.0F, 25.0F);
			if (Boolean.getBoolean("mine2metal.test.shaderDebugViews")) {
				for (int view = 1; view <= 4; view++) {
					System.setProperty("mine2metal.shaderDebug", Integer.toString(view));
					context.waitTicks(10);
					context.takeScreenshot("mine2metal-shaders-" + name + "-debug" + view);
				}
				System.clearProperty("mine2metal.shaderDebug");
			}
			context.getInput().lookAt(90.0F, 4.0F);
			singleplayer.getServer().runCommand("time set 12600");
			context.waitTicks(40);
			context.takeScreenshot("mine2metal-shaders-" + name + "-sunset");
			context.getInput().lookAt(30.0F, 30.0F);
			context.waitTicks(20);
			context.takeScreenshot("mine2metal-shaders-" + name + "-water-sunset");
			context.getInput().lookAt(90.0F, 4.0F);
			singleplayer.getServer().runCommand("time set 18000");
			context.waitTicks(40);
			context.takeScreenshot("mine2metal-shaders-" + name + "-night");

			singleplayer.getServer().runCommand("time set noon");
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.3f %d %.3f", above.x, ground - 5, above.z + 16.0));
			keepFlying(context);
			context.getInput().lookAt(0.0F, 10.0F);
			context.waitTicks(40);
			context.takeScreenshot("mine2metal-shaders-" + name + "-underwater");
			context.getInput().lookAt(0.0F, -60.0F);
			context.waitTicks(20);
			context.takeScreenshot("mine2metal-shaders-" + name + "-underwater-up");
			singleplayer.getServer().runCommand("time set 12600");
			context.getInput().lookAt(90.0F, -20.0F);
			context.waitTicks(30);
			context.takeScreenshot("mine2metal-shaders-" + name + "-underwater-sunset");
			singleplayer.getServer().runCommand("time set noon");
		}
		checkShadedDimensions(context, singleplayer);
		context.runOnClient(client -> MetalSettings.get().applyShaderPreset(MetalSettings.ShaderPreset.OFF));
	}

	/**
	 * The shaders together with the rest of the mod: upscaling (the world, and so the G-buffer, at render scale; temporal
	 * scaling jitters the projection), frame generation and EDR output. Each must still light the world from the G-buffer.
	 */
	private static void checkShadersWithOtherFeatures(ClientGameTestContext context) {
		record Combination(String name, MetalSettings.Upscaling upscaling, MetalSettings.FrameGeneration frameGeneration, boolean hdr) {
		}
		Combination[] combinations = {
				new Combination("temporal", MetalSettings.Upscaling.TEMPORAL, MetalSettings.FrameGeneration.OFF, false),
				new Combination("spatial", MetalSettings.Upscaling.SPATIAL, MetalSettings.FrameGeneration.OFF, false),
				new Combination("fsr3", MetalSettings.Upscaling.FSR3, MetalSettings.FrameGeneration.OFF, false),
				new Combination("framegen", MetalSettings.Upscaling.OFF, MetalSettings.FrameGeneration.METALFX, false),
				new Combination("hdr", MetalSettings.Upscaling.OFF, MetalSettings.FrameGeneration.OFF, true),
		};
		for (Combination combination : combinations) {
			context.runOnClient(client -> {
				MetalSettings settings = MetalSettings.get();
				settings.upscaling = combination.upscaling();
				settings.renderScale = 67;
				settings.frameGeneration = combination.frameGeneration();
				settings.hdr = combination.hdr();
			});
			context.waitTicks(40);
			check(context.computeOnClient(client -> WorldPass.lastFrameShaderLit()),
					"The shaders should light the world from the G-buffer with " + combination.name());
			context.takeScreenshot("mine2metal-shaders-with-" + combination.name());
			if (combination.upscaling() == MetalSettings.Upscaling.TEMPORAL) {
				LOGGER.info("Average FPS on the ground, shaders high, temporal upscaling at 67%: {}", averageFps(context));
			}
		}
		context.runOnClient(client -> {
			MetalSettings settings = MetalSettings.get();
			settings.upscaling = MetalSettings.Upscaling.OFF;
			settings.frameGeneration = MetalSettings.FrameGeneration.OFF;
			settings.hdr = false;
		});
		LOGGER.info("Average FPS on the ground, shaders high, no upscaling: {}", averageFps(context));
	}

	/** The Nether (lava, glowstone and their light, in its fog) and the End (its island and sky), with the High shaders. */
	private static void checkShadedDimensions(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		context.runOnClient(client -> MetalSettings.get().applyShaderPreset(MetalSettings.ShaderPreset.HIGH));
		// Teleported first: commands only reach loaded chunks, and waitForChunksRender does not settle right after a
		// change of dimension.
		String nether = "execute in minecraft:the_nether run ";
		singleplayer.getServer().runCommand(nether + "tp @a 0 62 -6 0 15");
		context.waitTicks(100);
		keepFlying(context);
		singleplayer.getServer().runCommand(nether + "fill -9 59 -9 9 74 9 netherrack");
		singleplayer.getServer().runCommand(nether + "fill -8 61 -8 8 73 8 air");
		singleplayer.getServer().runCommand(nether + "fill 1 60 1 5 60 5 lava");
		singleplayer.getServer().runCommand(nether + "setblock -4 64 4 glowstone");
		singleplayer.getServer().runCommand(nether + "fill -2 61 6 -2 66 6 nether_bricks");
		singleplayer.getServer().runCommand(nether + "tp @a 0 62 -6 0 15");
		context.waitTicks(80);
		check(context.computeOnClient(client -> WorldPass.lastFrameShaderLit()), "The shaders should light the Nether from the G-buffer");
		context.takeScreenshot("mine2metal-shaders-nether");

		singleplayer.getServer().runCommand("execute in minecraft:the_end run tp @a 0 80 40 180 20");
		context.waitTicks(140);
		keepFlying(context);
		context.takeScreenshot("mine2metal-shaders-end");
	}

	private static void keepFlying(ClientGameTestContext context) {
		context.runOnClient(client -> {
			client.player.getAbilities().flying = true;
			client.player.onUpdateAbilities();
		});
	}

	/** Uncaps the frame rate and averages Minecraft's per-second FPS counter over a few seconds. */
	private static int averageFps(ClientGameTestContext context) {
		context.runOnClient(client -> {
			client.options.enableVsync().set(false);
			// A cap stands in for a game too slow to reach the refresh rate by itself.
			client.options.framerateLimit().set(Integer.getInteger("mine2metal.test.fpsCap", Options.UNLIMITED_FRAMERATE_CUTOFF));
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

	/**
	 * On Sodium's shader page, cycling the preset must move the controls under it before Apply, and editing one of
	 * them must move the preset to Custom (ShaderPresetSync). Sodium's config classes are reached reflectively since
	 * Sodium is optional at runtime; the pending values are read the way the controls read them.
	 */
	private static void checkSodiumPresetSync(ClientGameTestContext context) {
		try {
			Class<?> manager = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager");
			Object config = manager.getField("CONFIG").get(null);
			Class<?> configClass = config.getClass();
			Object modOptions = ((java.util.List<?>) configClass.getMethod("getModOptions").invoke(config)).stream()
					.filter(mod -> {
						try {
							return "mine2metal".equals(mod.getClass().getMethod("configId").invoke(mod));
						} catch (ReflectiveOperationException e) {
							throw new IllegalStateException(e);
						}
					}).findFirst().orElseThrow(() -> new IllegalStateException("No mine2metal options registered with Sodium"));
			java.util.List<?> pages = (java.util.List<?>) modOptions.getClass().getMethod("pages").invoke(modOptions);
			Object shaderPage = pages.get(1);
			Class<?> optionPage = Class.forName("net.caffeinemc.mods.sodium.client.config.structure.OptionPage");
			Class<?> screenClass = Class.forName("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen");
			java.lang.reflect.Method createScreen = screenClass.getMethod("createScreen", net.minecraft.client.gui.screens.Screen.class, optionPage);
			context.setScreen(() -> {
				try {
					return (net.minecraft.client.gui.screens.Screen) createScreen.invoke(null, new TitleScreen(), shaderPage);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException(e);
				}
			});
			context.waitTicks(5);
			context.takeScreenshot("mine2metal-video-settings-shaders");

			java.lang.reflect.Method getOption = configClass.getMethod("getOption", Identifier.class);
			java.lang.reflect.Method readEnum = configClass.getMethod("readEnumOption", Identifier.class, Class.class, boolean.class);
			java.lang.reflect.Method readInt = configClass.getMethod("readIntOption", Identifier.class, boolean.class);
			java.util.function.BiConsumer<String, Object> modify = (name, value) -> context.runOnClient(client -> {
				try {
					Object option = getOption.invoke(config, Identifier.fromNamespaceAndPath("mine2metal", name));
					option.getClass().getMethod("modifyValue", Object.class).invoke(option, value);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException(e);
				}
			});
			java.util.function.BiFunction<String, Class<?>, Object> pending = (name, type) -> context.computeOnClient(client -> {
				try {
					Identifier id = Identifier.fromNamespaceAndPath("mine2metal", name);
					return type == Integer.class ? readInt.invoke(config, id, false) : readEnum.invoke(config, id, type, false);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException(e);
				}
			});

			modify.accept("shader_preset", MetalSettings.ShaderPreset.HIGH);
			context.waitTicks(3);
			check(pending.apply("shader_shadows", MetalSettings.ShadowQuality.class) == MetalSettings.ShadowQuality.HIGH,
					"Shadows should follow the High preset before Apply");
			check(pending.apply("shader_ao", MetalSettings.AoQuality.class) == MetalSettings.AoQuality.MEDIUM,
					"AO should follow the High preset before Apply");
			check(pending.apply("shader_lighting_scale", Integer.class).equals(75), "Lighting scale should follow the High preset");

			modify.accept("shader_bloom", MetalSettings.BloomQuality.LOW);
			context.waitTicks(3);
			check(pending.apply("shader_preset", MetalSettings.ShaderPreset.class) == MetalSettings.ShaderPreset.CUSTOM,
					"Editing bloom should move the preset to Custom before Apply");

			modify.accept("shader_preset", MetalSettings.ShaderPreset.LOW);
			context.waitTicks(3);
			check(pending.apply("shader_bloom", MetalSettings.BloomQuality.class) == MetalSettings.BloomQuality.LOW
					&& pending.apply("shader_lighting_scale", Integer.class).equals(50)
					&& pending.apply("shader_reflections", MetalSettings.ReflectionQuality.class) == MetalSettings.ReflectionQuality.LOW,
					"The Low preset should set every derived control");

			// Apply the way the button does, then put everything back.
			context.runOnClient(client -> {
				try {
					configClass.getMethod("applyAllOptions").invoke(config);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException(e);
				}
			});
			check(MetalSettings.get().shaderPreset == MetalSettings.ShaderPreset.LOW
					&& MetalSettings.get().shaderShadows == MetalSettings.ShadowQuality.LOW
					&& MetalSettings.get().shaderLightingScale == 50, "Apply should land the Low preset in the settings");
			modify.accept("shader_preset", MetalSettings.ShaderPreset.OFF);
			context.waitTicks(3);
			context.runOnClient(client -> {
				try {
					configClass.getMethod("applyAllOptions").invoke(config);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException(e);
				}
			});
			check(!MetalSettings.get().shadersEnabled(), "Applying Off should turn every shader option off");
			LOGGER.info("Sodium shader page: preset and derived options follow each other while editing");
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not drive Sodium's video settings", e);
		}
	}

	/**
	 * Walking on the ground with the shaders on, looking at the sky: a screenshot every couple of ticks
	 * (-Dmine2metal.test.walkOnly=true). View bobbing moves the camera inside the projection matrix, which once bent
	 * the shader's sky directions and left the cloud history as hard-edged rectangles; still screenshots never showed it.
	 */
	private static void checkWalking(ClientGameTestContext context, TestSingleplayerContext singleplayer, Vec3 above) {
		context.getInput().resizeWindow(1600, 900);
		context.runOnClient(client -> client.options.renderDistance().set(12));
		singleplayer.getServer().runCommand("weather clear");
		context.runOnClient(client -> MetalSettings.get().applyShaderPreset(MetalSettings.ShaderPreset.HIGH));
		int ground = context.computeOnClient(client -> client.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(above.x),
				(int) Math.floor(above.z)));
		for (String time : new String[] {"noon", "sunset"}) {
			singleplayer.getServer().runCommand("time set " + (time.equals("noon") ? "noon" : "12600"));
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.3f %d %.3f 0 -15", above.x, ground + 1, above.z));
			context.runOnClient(client -> {
				client.player.getAbilities().flying = false;
				client.player.onUpdateAbilities();
			});
			context.waitTicks(40);
			context.getInput().lookAt(0.0F, -20.0F);
			context.waitTicks(10);
			context.takeScreenshot("mine2metal-walk-" + time + "-still");
			context.getInput().holdKey(options -> options.keyUp);
			for (int shot = 0; shot < 6; shot++) {
				context.waitTicks(2);
				context.takeScreenshot("mine2metal-walk-" + time + "-" + shot);
			}
			for (int shot = 0; shot < 4; shot++) {
				context.getInput().lookAt(30.0F * (shot + 1), -20.0F);
				context.waitTicks(2);
				context.takeScreenshot("mine2metal-walk-" + time + "-turn" + shot);
			}
			context.getInput().releaseKey(options -> options.keyUp);
		}
		context.runOnClient(client -> MetalSettings.get().applyShaderPreset(MetalSettings.ShaderPreset.OFF));
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
