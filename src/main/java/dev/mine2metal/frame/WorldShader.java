package dev.mine2metal.frame;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.mine2metal.Mine2Metal;
import dev.mine2metal.config.MetalSettings;
import dev.mine2metal.metal.MetalRenderer;
import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * The built-in Metal shaders' per-frame data: camera matrices, sun, weather and fog, the voxel grid around the camera
 * (for ray traced sun shadows and emissive blocks) and the nearest light sources. The shading itself happens in a few
 * places: {@link #lightTerrain} as soon as the opaque terrain and solid entities are drawn with their G-buffer, the
 * water around the translucent terrain, and {@link #post} once the world is finished, before the hand.
 */
final class WorldShader {
	private static final int VOXEL_RADIUS = 48;
	/** The shaders read the grid up to 45 blocks from the camera in every direction. */
	private static final int VOXEL_REACH_Y = 44;
	/** Columns scanned per frame: one row of the grid (the same world z), so the GPU copies take a single z slice. */
	private static final int DIAMETER = VOXEL_RADIUS * 2 + 1;
	private static final int MAX_LIGHTS = 32;
	private static final Reference2ByteOpenHashMap<BlockState> STATE_CACHE = new Reference2ByteOpenHashMap<>();

	static {
		STATE_CACHE.defaultReturnValue((byte) -1);
	}

	private final CameraMotion motion = new CameraMotion();
	private final int[] quality = new int[7];
	private final float[] environment = new float[36];
	private final float[] lights = new float[MAX_LIGHTS * 8];
	private final long[] lightPositions = new long[MAX_LIGHTS];
	/** The highest non-air block of each column of the voxel grid, toroidally addressed like it; MIN_VALUE when empty. */
	private final int[] columnTops = new int[128 * 128];
	private final int[] lightCodes = new int[MAX_LIGHTS];
	private int lightCount;
	private int packedLights;

	private final long startNanos = System.nanoTime();
	private int frameCounter;
	private int scanCursor;
	private long voxelAddress;
	/** Goes up whenever a voxel changes, so the GPU's copies of the grid are refreshed only then. */
	private int voxelVersion;
	/** The grid z slices written since the GPU copies were told (min > max for none). */
	private int dirtyZMin = 128;
	private int dirtyZMax = -1;
	private @Nullable ClientLevel lastLevel;
	private int lastCenterBlockX = Integer.MIN_VALUE;
	private int lastCenterBlockY = Integer.MIN_VALUE;
	private int lastCenterBlockZ = Integer.MIN_VALUE;
	private boolean active;

	/** Whether the main pass of this frame should get the G-buffer: shaders on, in any dimension. */
	boolean wantsGbuffer(WorldFrame frame) {
		return MetalSettings.get().shadersEnabled() && MetalRenderer.isActive() && frame.sky != null && frame.projection() != null;
	}

	/** The opaque terrain and solid entities are drawn: lights them from the G-buffer and draws the sky, if the pass has one. */
	void lightTerrain(WorldFrame frame) {
		if (frame.shaderLit || !this.prepare(frame)) {
			return;
		}
		frame.shaderLit = MetalRenderer.deferredWorldShader(this.quality, this.motion.matrices, this.environment, this.lights, this.packedLights);
	}

	/**
	 * The translucent terrain is next: with reflections on, its water gets drawn by the shaders, from above or below.
	 * When nothing translucent is in view, the scene copies and the water pass are skipped.
	 */
	void beginWater(WorldFrame frame) {
		if (frame.shaderLit && !frame.waterOpen && this.quality[2] > 0 && (this.environment[11] == 0.0F || frame.underwater)
				&& SodiumTerrain.mayDrawTranslucent()) {
			frame.waterOpen = MetalRenderer.beginWater();
		}
	}

	/** The translucent terrain is drawn: the water is redrawn from its G-buffer. */
	void endWater(WorldFrame frame) {
		if (frame.waterOpen) {
			frame.waterOpen = false;
			MetalRenderer.endWater(this.quality, this.motion.matrices, this.environment);
		}
	}

	/** The world is finished: bloom and light shafts over it, and the end of this frame's shader data. */
	void post(WorldFrame frame) {
		if (!this.prepare(frame)) {
			return;
		}
		RenderTarget target = frame.main;
		GpuTexture color = target.getColorTexture();
		GpuTexture depth = target.getDepthTexture();
		if (color != null && depth != null) {
			MetalRenderer.postWorldShader(color, depth, this.quality, this.motion.matrices, this.environment);
		}
		this.motion.finish(frame.shaderLit);
	}

	/** A block changed: updates its voxel right away rather than when the scan comes by. */
	void blockChanged(BlockPos pos, BlockState state) {
		long address = this.voxelAddress;
		if (address == 0L || !this.active || Math.abs(pos.getX() - this.lastCenterBlockX) > VOXEL_RADIUS
				|| Math.abs(pos.getY() - this.lastCenterBlockY) > VOXEL_REACH_Y || Math.abs(pos.getZ() - this.lastCenterBlockZ) > VOXEL_RADIUS) {
			return;
		}
		int before = this.voxelVersion;
		this.writeVoxel(address, pos.getX(), pos.getY(), pos.getZ(), classifyBlockFast(state));
		if (this.voxelVersion != before) {
			this.markDirty(pos.getZ());
		}
	}

	private void markDirty(int worldZ) {
		int z = worldZ & 127;
		this.dirtyZMin = Math.min(this.dirtyZMin, z);
		this.dirtyZMax = Math.max(this.dirtyZMax, z);
	}

	/** Gathers this frame's data once; false when the shaders are off or there is nothing to shade. */
	private boolean prepare(WorldFrame frame) {
		MetalSettings settings = MetalSettings.get();
		if (!settings.shadersEnabled() || !MetalRenderer.isActive()) {
			if (this.active) {
				MetalRenderer.releaseWorldShader();
				this.motion.reset();
				this.lastLevel = null;
				this.lightCount = 0;
				this.voxelAddress = 0L;
				this.active = false;
			}
			return false;
		}
		if (frame.shaderPrepared) {
			return true;
		}

		Matrix4f projection = frame.projection();
		RenderTarget target = frame.main;
		if (projection == null || target.getColorTexture() == null) {
			return false;
		}
		frame.shaderPrepared = true;
		this.active = true;

		CameraRenderState camera = frame.camera;
		Matrix4f viewProjection = new Matrix4f(projection).mul(camera.viewRotationMatrix);
		this.motion.begin(viewProjection, camera.pos, target.width, target.height);
		// 36..51 hold this frame's view-projection (the motion vectors' inverse is not needed here).
		viewProjection.get(this.motion.matrices, 36);

		this.quality[0] = settings.shaderShadows.ordinal();
		this.quality[1] = settings.shaderAo.ordinal();
		this.quality[2] = settings.shaderReflections.ordinal();
		this.quality[3] = settings.shaderGi.ordinal();
		this.quality[4] = settings.shaderVolumetrics.ordinal();
		this.quality[5] = settings.shaderBloom.ordinal();
		this.quality[6] = settings.shaderLightingScale;

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		this.fillEnvironment(level, camera, frame.sky, projection, settings);

		this.voxelAddress = MetalRenderer.shaderVoxelBuffer();
		if (level != null && this.voxelAddress != 0L) {
			this.updateVoxelsAndLights(level, camera.pos, this.voxelAddress);
		} else {
			this.lightCount = 0;
		}
		if (this.dirtyZMax >= this.dirtyZMin) {
			MetalRenderer.shaderVoxelsChanged(this.dirtyZMin, this.dirtyZMax);
			this.dirtyZMin = 128;
			this.dirtyZMax = -1;
		}
		this.packedLights = this.packLights(camera.pos);
		this.environment[31] = this.ceiling();
		this.environment[32] = frame.underwater && level != null ? waterSurface(level, camera.pos) : -1.0e6F;
		this.environment[33] = frame.underwater ? 1.0F : 0.0F;
		this.environment[34] = this.voxelVersion;
		// Rain wets the world only where it falls as rain (not snow, not in deserts).
		this.environment[35] = level != null && this.environment[9] > 0.0F && this.environment[12] == 0.0F && raining(level, camera.pos) ? 1.0F : 0.0F;
		return true;
	}

	private void fillEnvironment(@Nullable ClientLevel level, CameraRenderState camera, @Nullable SkyRenderState sky,
			Matrix4f projection, MetalSettings settings) {
		Vec3 pos = camera.pos;
		this.environment[0] = (float) pos.x;
		this.environment[1] = (float) pos.y;
		this.environment[2] = (float) pos.z;
		this.environment[3] = (System.nanoTime() - this.startNanos) / 1.0e9F;

		float sunX = 0.36F;
		float sunY = 0.84F;
		float sunZ = 0.28F;
		float daylight = 1.0F;
		float twilight = 0.0F;
		float rain = 0.0F;
		float thunder = 0.0F;
		float dimension = 0.0F;
		float sunElevation = 0.84F;

		if (level != null) {
			double angle = sky != null ? sky.sunAngle : 0.35;
			float rawX = (float) -Math.sin(angle);
			float rawY = (float) Math.cos(angle);
			// Complementary/BSL-style sun path tilt so North/South faces and noon trees cast crisp 3D diagonal shadows.
			float tilt = 0.28F;
			sunElevation = rawY;

			float sign = rawY >= -0.06F ? 1.0F : -1.0F;
			float dirX = rawX * sign;
			float dirY = Math.max(Math.abs(rawY), 0.18F);
			float dirZ = tilt * sign;
			float invLen = (float) (1.0 / Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ));
			sunX = dirX * invLen;
			sunY = dirY * invLen;
			sunZ = dirZ * invLen;

			daylight = smoothstep(-0.10F, 0.20F, rawY);
			// Asymmetric twilight curve: peaks right at sunrise/sunset (rawY ~ 0.03) and drops to 0 by /time set night (rawY = -0.19).
			float twilightDelta = rawY - 0.03F;
			float twilightSigma = twilightDelta < 0.0F ? 0.09F : 0.18F;
			twilight = (float) Math.exp(-(twilightDelta * twilightDelta) / (twilightSigma * twilightSigma));
			rain = level.getRainLevel(1.0F);
			thunder = level.getThunderLevel(1.0F);

			// By the sky the dimension draws, so dimensions from data packs light like the one they resemble.
			DimensionType.Skybox skybox = sky != null ? sky.skybox : DimensionType.Skybox.OVERWORLD;
			if (skybox != DimensionType.Skybox.OVERWORLD) {
				dimension = skybox == DimensionType.Skybox.END ? 2.0F : 1.0F;
				daylight = 1.0F;
				twilight = 0.0F;
				rain = 0.0F;
				thunder = 0.0F;
			}
		}

		// Use the camera's exact FogType so entering/exiting water or swimming at the surface switches immediately.
		float fluidMode = 0.0F;
		if (camera.fogType == FogType.WATER) {
			fluidMode = 1.0F;
		} else if (camera.fogType == FogType.LAVA) {
			fluidMode = 2.0F;
		} else if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.isUnderWater()) {
			fluidMode = 1.0F;
		} else if (camera.fogType == FogType.POWDER_SNOW || Minecraft.getInstance().player != null
				&& (Minecraft.getInstance().player.hasEffect(MobEffects.BLINDNESS) || Minecraft.getInstance().player.hasEffect(MobEffects.DARKNESS))) {
			// Vanilla's own dense fog, which the shaders then leave to vanilla.
			fluidMode = 3.0F;
		}

		this.environment[4] = sunX;
		this.environment[5] = sunY;
		this.environment[6] = sunZ;
		this.environment[7] = daylight;

		this.environment[8] = twilight;
		this.environment[9] = rain;
		this.environment[10] = thunder;
		this.environment[11] = fluidMode;

		this.environment[12] = dimension;
		this.environment[13] = settings.hdr ? settings.hdrBrightness / 100.0F : 0.0F;
		// The shaders turn depth into distance with these (Minecraft's projection has reversed Z).
		this.environment[14] = projection.m22();
		this.environment[15] = projection.m32();

		if (sky != null && sky.skyColor != null && sky.skyColor.lengthSquared() > 1.0e-4F) {
			Vector3fc sc = sky.skyColor;
			this.environment[16] = sc.x();
			this.environment[17] = sc.y();
			this.environment[18] = sc.z();
		} else {
			this.environment[16] = 0.42F * daylight + 0.02F;
			this.environment[17] = 0.66F * daylight + 0.03F;
			this.environment[18] = 0.96F * daylight + 0.06F;
		}
		this.environment[19] = (this.frameCounter++ & 1023);

		if (camera.fogData != null && camera.fogData.color != null) {
			Vector4f fc = camera.fogData.color;
			this.environment[20] = fc.x;
			this.environment[21] = fc.y;
			this.environment[22] = fc.z;
		} else {
			this.environment[20] = 0.62F * daylight;
			this.environment[21] = 0.76F * daylight;
			this.environment[22] = 0.92F * daylight;
		}
		this.environment[23] = sunElevation;

		FogData fog = camera.fogData;
		this.environment[24] = fog != null ? fog.environmentalStart : Float.MAX_VALUE;
		this.environment[25] = fog != null ? fog.environmentalEnd : Float.MAX_VALUE;
		this.environment[26] = fog != null ? fog.renderDistanceStart : camera.depthFar * 0.9F;
		this.environment[27] = fog != null ? fog.renderDistanceEnd : camera.depthFar;
		Minecraft mc = Minecraft.getInstance();
		this.environment[28] = mc.options.gamma().get().floatValue();
		this.environment[29] = mc.player != null && mc.player.hasEffect(MobEffects.NIGHT_VISION) ? 1.0F : 0.0F;
		// 1 sun visibility, 2 ambient occlusion, 3 normals, 4 block and sky light, 5 voxel grid coverage.
		this.environment[30] = Integer.getInteger("mine2metal.shaderDebug", 0);
		if (this.environment[30] > 0.0F && (this.frameCounter & 127) == 0) {
			Mine2Metal.LOGGER.info("Shader debug: sun {} {} {}, camera {} {} {}, sky angle {}", this.environment[4], this.environment[5], this.environment[6],
					this.environment[0], this.environment[1], this.environment[2], sky != null ? sky.sunAngle : Float.NaN);
		}
	}

	private void updateVoxelsAndLights(ClientLevel level, Vec3 cameraPos, long voxelAddr) {
		int cx = (int) Math.floor(cameraPos.x);
		int cy = (int) Math.floor(cameraPos.y);
		int cz = (int) Math.floor(cameraPos.z);

		boolean jumped = level != this.lastLevel
				|| Math.abs(cx - this.lastCenterBlockX) > 28
				|| Math.abs(cy - this.lastCenterBlockY) > 28
				|| Math.abs(cz - this.lastCenterBlockZ) > 28;
		int minY = Math.max(level.getMinY(), cy - VOXEL_REACH_Y);
		int maxY = Math.min(level.getMaxY() - 1, cy + VOXEL_REACH_Y);

		if (jumped) {
			MemoryUtil.memSet(voxelAddr, 0, 128L * 128L * 128L);
			this.voxelVersion = (this.voxelVersion + 1) & 0xFFFFF;
			this.dirtyZMin = 0;
			this.dirtyZMax = 127;
			Arrays.fill(this.columnTops, Integer.MIN_VALUE);
			this.lastLevel = level;
			this.lastCenterBlockX = cx;
			this.lastCenterBlockY = cy;
			this.lastCenterBlockZ = cz;
			this.lightCount = 0;
			this.scanCursor = 0;
			// Immediately populate the inner 20-block radius on the first frame so shadows are visible right away.
			for (int dz = -20; dz <= 20; dz++) {
				for (int dx = -20; dx <= 20; dx++) {
					this.scanColumn(level, cx + dx, cz + dz, minY, maxY, voxelAddr);
				}
			}
			return;
		}

		this.lastCenterBlockX = cx;
		this.lastCenterBlockY = cy;
		this.lastCenterBlockZ = cz;

		// One row of the grid per frame, so a sweep of the whole area takes DIAMETER frames and the GPU copies take one
		// z slice (16 KB) rather than the whole grid.
		int dz = this.scanCursor - VOXEL_RADIUS;
		this.scanCursor = (this.scanCursor + 1) % DIAMETER;
		int before = this.voxelVersion;
		for (int dx = -VOXEL_RADIUS; dx <= VOXEL_RADIUS; dx++) {
			this.scanColumn(level, cx + dx, cz + dz, minY, maxY, voxelAddr);
		}
		if (this.voxelVersion != before) {
			this.markDirty(cz + dz);
		}
	}

	private static boolean raining(ClientLevel level, Vec3 cameraPos) {
		BlockPos pos = BlockPos.containing(cameraPos);
		return level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel()) == Biome.Precipitation.RAIN;
	}

	/** The height of the water surface above a camera under water, or far below everything when it is out of reach. */
	private static float waterSurface(ClientLevel level, Vec3 cameraPos) {
		BlockPos.MutableBlockPos pos = BlockPos.containing(cameraPos).mutable();
		for (int i = 0; i < 64; i++) {
			if (!level.getFluidState(pos).is(FluidTags.WATER)) {
				// A full water block's surface sits at 8/9 of its height.
				return pos.getY() - 1.0F + 8.0F / 9.0F;
			}
			pos.move(0, 1, 0);
		}
		return -1.0e6F;
	}

	/** The highest block in the grid, above which a ray towards the sky cannot hit anything. */
	private float ceiling() {
		int top = Integer.MIN_VALUE;
		for (int columnTop : this.columnTops) {
			top = Math.max(top, columnTop);
		}
		return top == Integer.MIN_VALUE ? -1.0e6F : top + 1.0F;
	}

	private void scanColumn(ClientLevel level, int wx, int wz, int minY, int maxY, long voxelAddr) {
		LevelChunk chunk = level.getChunkSource().getChunk(wx >> 4, wz >> 4, ChunkStatus.FULL, false);
		if (chunk == null) {
			return;
		}
		LevelChunkSection[] sections = chunk.getSections();
		int minSectionY = chunk.getMinSectionY();
		int lx = wx & 15;
		int lz = wz & 15;
		long baseOffset = (wx & 127) | ((long) (wz & 127) << 14);
		int column = (wx & 127) | (wz & 127) << 7;
		this.columnTops[column] = Integer.MIN_VALUE;

		int wy = minY;
		while (wy <= maxY) {
			int secIdx = (wy >> 4) - minSectionY;
			int secEndY = Math.min(maxY, ((wy >> 4) << 4) + 15);
			if (secIdx < 0 || secIdx >= sections.length) {
				for (; wy <= secEndY; wy++) {
					this.clearVoxel(voxelAddr + (baseOffset | ((long) (wy & 127) << 7)));
				}
				continue;
			}
			LevelChunkSection section = sections[secIdx];
			if (section == null || section.hasOnlyAir()) {
				for (; wy <= secEndY; wy++) {
					this.clearVoxel(voxelAddr + (baseOffset | ((long) (wy & 127) << 7)));
				}
				continue;
			}
			for (; wy <= secEndY; wy++) {
				this.writeVoxel(voxelAddr, wx, wy, wz, classifyBlockFast(section.getBlockState(lx, wy & 15, lz)));
			}
		}
	}

	private void clearVoxel(long address) {
		if (MemoryUtil.memGetByte(address) != 0) {
			MemoryUtil.memPutByte(address, (byte) 0);
			this.voxelVersion = (this.voxelVersion + 1) & 0xFFFFF;
		}
	}

	private void writeVoxel(long voxelAddr, int x, int y, int z, int code) {
		long address = voxelAddr + ((x & 127) | ((long) (y & 127) << 7) | ((long) (z & 127) << 14));
		if (MemoryUtil.memGetByte(address) != (byte) code) {
			MemoryUtil.memPutByte(address, (byte) code);
			// Kept within a float's exact integers, as it travels in the shader's environment.
			this.voxelVersion = (this.voxelVersion + 1) & 0xFFFFF;
		}
		if (code != 0) {
			int column = (x & 127) | (z & 127) << 7;
			this.columnTops[column] = Math.max(this.columnTops[column], y);
		}
		if (code >= 16) {
			this.recordEmissiveLight(x, y, z, code);
		} else if (this.lightCount > 0) {
			this.removeEmissiveLight(x, y, z);
		}
	}

	private static int classifyBlockFast(BlockState state) {
		if (state.isAir()) {
			return 0;
		}
		byte cached = STATE_CACHE.getByte(state);
		if (cached != -1) {
			return Byte.toUnsignedInt(cached);
		}
		int code = computeBlockCode(state, state.getBlock());
		STATE_CACHE.put(state, (byte) code);
		return code;
	}

	private static int computeBlockCode(BlockState state, Block block) {
		int emission = state.getLightEmission();
		String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
		if (state.getFluidState().is(FluidTags.LAVA) || path.contains("lava") || path.contains("fire") || path.contains("magma")) {
			int palette = path.contains("soul") ? 3 : 1;
			return (palette << 4) | Math.max(12, Math.min(15, emission));
		}
		if (emission >= 6) {
			int palette = 2;
			if (path.contains("soul")) {
				palette = 3;
			} else if (path.contains("sea_lantern") || path.contains("conduit")) {
				palette = 4;
			} else if (path.contains("amethyst") || path.contains("end_") || path.contains("portal") || path.contains("crying") || path.contains("respawn")) {
				palette = 5;
			} else if (path.contains("froglight") || path.contains("bulb")) {
				palette = 6;
			}
			return (palette << 4) | Math.min(15, emission);
		}
		if (state.getFluidState().is(FluidTags.WATER)) {
			return 3;
		}
		if (state.is(BlockTags.LEAVES)) {
			return 2;
		}
		if (path.contains("glass") || path.contains("ice")) {
			if (path.contains("red")) return 5;
			if (path.contains("green") || path.contains("lime")) return 6;
			if (path.contains("blue")) return 7;
			if (path.contains("yellow") || path.contains("orange")) return 8;
			if (path.contains("purple") || path.contains("magenta") || path.contains("pink")) return 9;
			if (path.contains("cyan") || path.contains("light_blue") || path.contains("ice")) return 10;
			return 4;
		}
		if (state.canOcclude()) {
			return 1;
		}
		// Include stairs, slabs, roofs, fences, walls, doors, farmland, dirt paths, and chests so they cast shadows.
		VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		if (!shape.isEmpty() && shape.bounds().getYsize() >= 0.35) {
			return 1;
		}
		return 0;
	}

	private void recordEmissiveLight(int x, int y, int z, int code) {
		long packed = BlockPos.asLong(x, y, z);
		for (int i = 0; i < this.lightCount; i++) {
			if (this.lightPositions[i] == packed) {
				this.lightCodes[i] = code;
				return;
			}
		}
		if (this.lightCount < MAX_LIGHTS) {
			this.lightPositions[this.lightCount] = packed;
			this.lightCodes[this.lightCount] = code;
			this.lightCount++;
		}
	}

	private void removeEmissiveLight(int x, int y, int z) {
		long packed = BlockPos.asLong(x, y, z);
		for (int i = 0; i < this.lightCount; i++) {
			if (this.lightPositions[i] == packed) {
				int last = this.lightCount - 1;
				this.lightPositions[i] = this.lightPositions[last];
				this.lightCodes[i] = this.lightCodes[last];
				this.lightCount = last;
				return;
			}
		}
	}

	private int packLights(Vec3 cameraPos) {
		int written = 0;
		for (int i = 0; i < this.lightCount; i++) {
			long packed = this.lightPositions[i];
			int lx = BlockPos.getX(packed);
			int ly = BlockPos.getY(packed);
			int lz = BlockPos.getZ(packed);
			float rx = (float) (lx + 0.5 - cameraPos.x);
			float ry = (float) (ly + 0.5 - cameraPos.y);
			float rz = (float) (lz + 0.5 - cameraPos.z);
			float distSq = rx * rx + ry * ry + rz * rz;
			if (distSq > 40.0F * 40.0F) {
				int last = this.lightCount - 1;
				this.lightPositions[i] = this.lightPositions[last];
				this.lightCodes[i] = this.lightCodes[last];
				this.lightCount = last;
				i--;
				continue;
			}
			int code = this.lightCodes[i];
			int palette = (code >> 4) & 7;
			int emission = code & 15;
			float radius = 5.0F + emission * 0.70F;
			float r = 1.0F;
			float g = 0.65F;
			float b = 0.28F;
			switch (palette) {
				case 1 -> { r = 1.0F; g = 0.42F; b = 0.12F; }
				case 2 -> { r = 1.0F; g = 0.76F; b = 0.38F; }
				case 3 -> { r = 0.22F; g = 0.82F; b = 1.0F; }
				case 4 -> { r = 0.45F; g = 0.94F; b = 0.88F; }
				case 5 -> { r = 0.84F; g = 0.45F; b = 1.0F; }
				case 6 -> { r = 0.68F; g = 0.95F; b = 0.48F; }
			}
			int base = written * 8;
			this.lights[base] = rx;
			this.lights[base + 1] = ry;
			this.lights[base + 2] = rz;
			this.lights[base + 3] = radius;
			this.lights[base + 4] = r;
			this.lights[base + 5] = g;
			this.lights[base + 6] = b;
			this.lights[base + 7] = (emission / 15.0F) * 1.25F;
			written++;
			if (written >= MAX_LIGHTS) {
				break;
			}
		}
		return written;
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}
}
