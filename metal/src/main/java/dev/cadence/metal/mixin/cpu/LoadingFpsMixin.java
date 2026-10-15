// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.cpu;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.cadence.metal.cpu.Cpu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dcadence.cpu.loadingFps=N: vanilla caps menus at 60 fps only while there's no level, but the world-loading screen shows while the
 * level already exists, so during a join it renders uncapped (32% of the Neo's render thread during the join). Its
 * close check runs on the client tick, which the cap doesn't change.
 */
@Mixin(FramerateLimitTracker.class)
abstract class LoadingFpsMixin {
	@Shadow @Final private Minecraft minecraft;

	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
	private void cadence$capLoading(CallbackInfoReturnable<Integer> cir) {
		if (this.minecraft.gui.screen() instanceof LevelLoadingScreen && cir.getReturnValueI() > Cpu.LOADING_FPS) cir.setReturnValue(Cpu.LOADING_FPS);
	}
}
