// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.cadence.shaders.geometry.PortalGeometry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies face geometry that the vanilla portal's position-only material does not need. */
@Mixin(BufferBuilder.class)
public abstract class PortalBufferBuilderMixin {
  @Shadow @Final private VertexFormat format;
  @Shadow private int vertices;
  @Shadow private long vertexPointer;

  @Inject(method = "addVertex(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;", at = @At("RETURN"))
  private void shaders$portalFaceNormal(
      float x, float y, float z, CallbackInfoReturnable<VertexConsumer> callback) {
    if (format != PortalGeometry.FORMAT) return;
    // Satisfy the generic builder's element bookkeeping until the complete quad is available.
    callback.getReturnValue().setNormal(0, 0, 0);
    if ((vertices & 3) == 0) PortalGeometry.finishQuad(vertexPointer);
  }
}
