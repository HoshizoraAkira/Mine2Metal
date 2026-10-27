package dev.mine2metal.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.mine2metal.Mine2Metal;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

/**
 * Development aid: {@code -Dmine2metal.dumpFrame=/path/frame.png} reads back the texture presented on frame
 * {@code -Dmine2metal.dumpFrameIndex} (default 300) and writes it as a PNG, without needing screen-recording access.
 */
final class FrameDump {
	private static final String PATH = System.getProperty("mine2metal.dumpFrame");
	private static final int FRAME = Integer.getInteger("mine2metal.dumpFrameIndex", 300);

	private int frame;

	void onPresent(long ctx, MetalCommandEncoder encoder, GpuTexture texture) {
		if (PATH == null || ++this.frame != FRAME) {
			return;
		}
		if (texture.getFormat() != GpuFormat.RGBA8_UNORM) {
			Mine2Metal.LOGGER.warn("Frame dump only handles RGBA8 targets, not {}", texture.getFormat());
			return;
		}

		int width = texture.getWidth(0);
		int height = texture.getHeight(0);
		long size = (long) width * height * 4;
		MetalBuffer buffer = new MetalBuffer(encoder, MetalNative.bufferCreate(ctx, size), 0, size);
		encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
			// Render targets are stored bottom-up, as in GL.
			STBImageWrite.stbi_flip_vertically_on_write(true);
			boolean written = STBImageWrite.stbi_write_png(PATH, width, height, 4, MemoryUtil.memByteBuffer(buffer.address, (int) size), width * 4);
			Mine2Metal.LOGGER.info(written ? "Dumped frame {} to {}" : "Could not write frame {} to {}", FRAME, PATH);
			buffer.close();
		}, 0);
	}
}
