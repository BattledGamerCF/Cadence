package dev.cadence.metal.mixin;

import dev.cadence.api.RenderStage;
import dev.cadence.metal.ApiHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence API (dev.cadence.api.Cadence#onRender): AFTER_HUD, the frame drawn (world and GUI). One check per frame while unused. */
@Mixin(GameRenderer.class)
abstract class ApiHudMixin {
	@Inject(method = "render", at = @At("RETURN"))
	private void cadence$apiHud(CallbackInfo ci) {
		if (!ApiHooks.wanted(RenderStage.AFTER_HUD)) return;
		Minecraft mc = Minecraft.getInstance();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		var entity = mc.getCameraEntity();
		ApiHooks.fire(RenderStage.AFTER_HUD, null, entity != null ? entity.getEyePosition(partial) : net.minecraft.world.phys.Vec3.ZERO, null, null, partial);
	}
}
