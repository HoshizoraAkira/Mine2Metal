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

			if (Boolean.getBoolean("mine2metal.test.temporalOnly")) {
				checkTemporalUpscaling(context, singleplayer, above);
				return;
			}
			if (Boolean.getBoolean("mine2metal.test.interpolationOnly")) {
				checkFrameInterpolation(context, singleplayer, above);
				return;
			}
			if (Boolean.getBoolean("mine2metal.test.interpolationPerf")) {
				measureInterpolationCost(context, singleplayer);
				return;
			}
			if (Boolean.getBoolean("mine2metal.test.hdrOnly")) {
				checkHdr(context, singleplayer, above);
				return;
			}
			if (backend.equals("Metal")) {
				checkSpatialUpscaling(context, singleplayer, returnAbove);
				checkTemporalUpscaling(context, singleplayer, above);
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
	 * Temporal upscaling against native resolution, for each sign of the jitter MetalFX is told about: still, where only
	 * the jitter matters, and right after a turn, where the motion vectors must carry the history along. The
	 * screenshots are compared afterwards (PSNR against the native ones).
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
		for (String sign : signs) {
			String name = sign.replace(",", "_").replace("-1", "m").replace("1", "p");
			System.setProperty("mine2metal.temporal.jitterSign", sign);
			context.runOnClient(client -> {
				settings.upscaling = MetalSettings.Upscaling.TEMPORAL;
				settings.renderScale = 50;
			});
			hover(context, singleplayer, still);
			context.takeScreenshot("mine2metal-temporal-50-still-" + name);
			// Turn by 3 degrees a tick, then capture right away: stale history shows as ghosting.
			for (int yaw = 3; yaw <= 30; yaw += 3) {
				singleplayer.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f %d 50", above.x, above.y, above.z, yaw));
				context.waitTicks(1);
			}
			context.takeScreenshot("mine2metal-temporal-50-turned-" + name);
			LOGGER.info("Average FPS from above with MetalFX temporal at 50% (jitter sign {}): {}", sign, averageFps(context));
		}
		System.clearProperty("mine2metal.temporal.jitterSign");

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
		context.runOnClient(client -> {
			settings.upscaling = MetalSettings.Upscaling.OFF;
			settings.frameGeneration = MetalSettings.FrameGeneration.OFF;
		});
		context.waitTicks(10);
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
			});
			singleplayer.getServer().runCommand(start);
			context.waitTicks(20);
			LOGGER.info("Measuring {}", name);
			context.runOnClient(client -> client.options.keyUp.setDown(true));
			int fps = averageFps(context);
			context.runOnClient(client -> client.options.keyUp.setDown(false));
			LOGGER.info("Average FPS, render distance 16, walking towards the trees, {}: {}", name, fps);
		}
		context.runOnClient(client -> {
			settings.frameGeneration = MetalSettings.FrameGeneration.OFF;
			settings.upscaling = MetalSettings.Upscaling.OFF;
			settings.hdr = false;
			MetalRenderer.setHdr(false, settings.hdrBrightness);
		});
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
