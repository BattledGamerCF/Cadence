// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.cpu;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Unique;
import dev.cadence.metal.cpu.Cpu;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * -Dcadence.cpu.model=true: ModelPart.compile walks its cubes with an indexed loop instead of the immutable list's iterator (the
 * iterator was 2.2% of the Neo's render thread in a 200-mob crowd). Same cubes, same order, same calls.
 *
 * <p>Cadence (2026-10-18, garbage in a crowded scene, ~350 KB a frame): a part without children no longer makes an iterator
 * over its empty child map (most parts are leaves; ~4% of the render thread's garbage), and a rotated part's quaternion is one
 * reused object on the render thread (PoseStack.rotate applies it at once and keeps nothing; ~1%).
 */
@Mixin(ModelPart.class)
abstract class ModelLoopMixin {
	@Shadow @Final private List<ModelPart.Cube> cubes;

	@Inject(method = "compile", at = @At("HEAD"), cancellable = true)
	private void cadence$indexed(PoseStack.Pose pose, VertexConsumer builder, int lightCoords, int overlayCoords, int color, CallbackInfo ci) {
		if (!Cpu.modelActive) return;
		List<ModelPart.Cube> cubes = this.cubes;
		for (int i = 0, n = cubes.size(); i < n; i++) cubes.get(i).compile(pose, builder, lightCoords, overlayCoords, color);
		ci.cancel();
	}

	@WrapOperation(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
		at = @At(value = "INVOKE", target = "Ljava/util/Collection;iterator()Ljava/util/Iterator;"))
	private Iterator<ModelPart> cadence$leaf(Collection<ModelPart> children, Operation<Iterator<ModelPart>> original) {
		return Cpu.modelActive && children.isEmpty() ? Collections.emptyIterator() : original.call(children);
	}

	@Unique private static final Quaternionf cadence$rotation = new Quaternionf();

	@WrapOperation(method = "translateAndRotate", at = @At(value = "NEW", target = "()Lorg/joml/Quaternionf;"))
	private Quaternionf cadence$rotation(Operation<Quaternionf> original) {
		return Cpu.modelActive && RenderSystem.isOnRenderThread() ? cadence$rotation : original.call();
	}
}
