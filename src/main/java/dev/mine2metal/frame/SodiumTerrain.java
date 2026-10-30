package dev.mine2metal.frame;

import dev.mine2metal.Mine2Metal;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.util.Iterator;

/**
 * What Sodium's terrain renderer is about to draw. The built-in shaders copy the scene and attach the G-buffer before
 * the translucent terrain for the water; when no visible section has translucent geometry that work has nothing to
 * show, so it is skipped. The answer errs on the side of drawing: without Sodium, or should Sodium's internals change,
 * it is always "maybe".
 */
final class SodiumTerrain {
	private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("sodium");
	private static boolean failed;

	private SodiumTerrain() {
	}

	/** False only when Sodium is certain to draw no translucent terrain this frame. */
	static boolean mayDrawTranslucent() {
		if (!LOADED || failed) {
			return true;
		}
		try {
			return Sodium.mayDrawTranslucent();
		} catch (Throwable t) {
			failed = true;
			Mine2Metal.LOGGER.warn("Cannot read Sodium's render lists; the water stage of the shaders runs every frame", t);
			return true;
		}
	}

	/** Loaded only with Sodium on the class path. */
	private static final class Sodium {
		private static final Field MANAGER;

		static {
			try {
				MANAGER = SodiumWorldRenderer.class.getDeclaredField("renderSectionManager");
				MANAGER.setAccessible(true);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}

		static boolean mayDrawTranslucent() throws ReflectiveOperationException {
			SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
			if (renderer == null) {
				return true;
			}
			RenderSectionManager manager = (RenderSectionManager) MANAGER.get(renderer);
			if (manager == null) {
				return true;
			}
			// As DefaultChunkRenderer decides what to draw: a region's storage for the pass, and each visible section's
			// slice mask (which faces have geometry).
			Iterator<ChunkRenderList> lists = manager.getRenderLists().iterator(false);
			while (lists.hasNext()) {
				ChunkRenderList list = lists.next();
				SectionRenderDataStorage storage = list.getRegion().getStorage(DefaultTerrainRenderPasses.TRANSLUCENT);
				if (storage == null) {
					continue;
				}
				ByteIterator sections = list.sectionsWithGeometryIterator(false);
				while (sections.hasNext()) {
					long data = storage.getDataPointer(sections.nextByteAsInt());
					if (data != 0L && SectionRenderDataUnsafe.getSliceMask(data) != 0) {
						return true;
					}
				}
			}
			return false;
		}
	}
}
