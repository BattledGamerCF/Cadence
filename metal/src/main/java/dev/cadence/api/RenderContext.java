package dev.cadence.api;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * What an addon draws with at a {@link RenderStage}. Valid only during the hook call (a new one each call: don't keep it).
 * At AFTER_HUD the world's view is gone: modelView and projection are identity matrices and camera is the camera entity's eye
 * position (draw in screen space there).
 *
 * @param stage where in the frame this is
 * @param pass the game's open render pass at AFTER_OPAQUE and AFTER_TRANSLUCENT (draw into it with Minecraft's render
 *     pipelines; set your pipeline and uniforms, the game restores its own after you), null at the other stages
 * @param mainTarget the window's colour and depth target (what the level is drawn into)
 * @param camera the camera's world position this frame (geometry is drawn relative to it)
 * @param modelView the camera's rotation (world, relative to the camera, to view)
 * @param projection the camera's projection (Minecraft's own: reversed depth, 0 far)
 * @param partialTick how far between the last and the next game tick this frame is (0 to 1)
 * @param metal the Metal objects behind this frame, for addons that draw with Metal directly; null when the game isn't on
 *     Cadence's Metal backend
 */
public record RenderContext(RenderStage stage, @Nullable RenderPass pass, RenderTarget mainTarget, Vec3 camera, Matrix4fc modelView,
	Matrix4fc projection, float partialTick, @Nullable MetalHandles metal) {
}
