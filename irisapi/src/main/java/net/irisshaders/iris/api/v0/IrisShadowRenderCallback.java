package net.irisshaders.iris.api.v0;

import org.joml.Matrix4f;

/** Iris API v0.4: drawing of a mod's own into the shadow pass (IrisApi.registerShadowRenderCallback). */
@FunctionalInterface
public interface IrisShadowRenderCallback {
	void renderShadow(Matrix4f modelView, Matrix4f projection, double cameraX, double cameraY, double cameraZ, float tickDelta);
}
