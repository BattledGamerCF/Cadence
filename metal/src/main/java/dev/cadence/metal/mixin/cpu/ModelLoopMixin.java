// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.cpu;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
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
}
