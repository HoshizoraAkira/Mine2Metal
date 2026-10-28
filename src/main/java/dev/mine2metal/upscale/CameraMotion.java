package dev.mine2metal.upscale;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * The camera of the previous frame, for the motion vectors the native side derives from depth: each pixel is
 * unprojected with this frame's matrices and projected with the previous frame's.
 */
final class CameraMotion {
	/** A camera that moves further than this between frames has teleported; its history is useless. */
	private static final double TELEPORT_DISTANCE = 16.0;
	/** Minecraft's near plane, for a projection whose planes cannot be read back. */
	private static final float NEAR_PLANE = 0.05F;

	/**
	 * This frame's inverse view-projection, the previous frame's view-projection, the camera's movement since then
	 * (and whether there is a previous frame) and the previous view-projection's inverse, as
	 * {@code m2m_metalfx_temporal_upscale} takes them. Filled by {@link #begin}.
	 */
	final float[] matrices = new float[52];

	private boolean hasHistory;
	private final Matrix4f previousViewProjection = new Matrix4f();
	private @Nullable Vec3 previousCameraPos;
	private int previousWidth;
	private int previousHeight;

	private final Matrix4f currentViewProjection = new Matrix4f();
	private @Nullable Vec3 currentCameraPos;
	private int currentWidth;
	private int currentHeight;

	/**
	 * Fills {@link #matrices} for a frame whose depth is width by height; true when there is no usable history, in
	 * which case the frame is matched against itself. Follow with {@link #finish}.
	 */
	boolean begin(Matrix4f viewProjection, Vec3 cameraPos, int width, int height) {
		boolean reset = !this.hasHistory || this.previousCameraPos == null || width != this.previousWidth || height != this.previousHeight
				|| cameraPos.distanceToSqr(this.previousCameraPos) > TELEPORT_DISTANCE * TELEPORT_DISTANCE;
		Matrix4f previous = reset ? viewProjection : this.previousViewProjection;
		Vec3 delta = reset ? Vec3.ZERO : cameraPos.subtract(this.previousCameraPos);

		new Matrix4f(viewProjection).invert().get(this.matrices, 0);
		previous.get(this.matrices, 16);
		this.matrices[32] = (float) delta.x;
		this.matrices[33] = (float) delta.y;
		this.matrices[34] = (float) delta.z;
		this.matrices[35] = reset ? 0.0F : 1.0F;
		new Matrix4f(previous).invert().get(this.matrices, 36);

		this.currentViewProjection.set(viewProjection);
		this.currentCameraPos = cameraPos;
		this.currentWidth = width;
		this.currentHeight = height;
		return reset;
	}

	/** Makes the frame from {@link #begin} the previous one; used says whether its output becomes history. */
	void finish(boolean used) {
		this.previousViewProjection.set(this.currentViewProjection);
		this.previousCameraPos = this.currentCameraPos;
		this.previousWidth = this.currentWidth;
		this.previousHeight = this.currentHeight;
		this.hasHistory = used;
	}

	void reset() {
		this.hasHistory = false;
	}

	/**
	 * Near and far plane into values[0..1], for AMD's code. The Metal device reports zero-to-one depth, so the projection
	 * has m22 = f / (n - f) and m32 = n f / (n - f). depthFar stands in when the projection does not say.
	 */
	static void planes(Matrix4f projection, float depthFar, float[] values) {
		float near = projection.m32() / projection.m22();
		float far = projection.m32() / (projection.m22() + 1.0F);
		if (!(near > 0.0F && far > near && Float.isFinite(far))) {
			near = NEAR_PLANE;
			far = depthFar;
		}
		values[0] = near;
		values[1] = far;
	}
}
