package dev.cadence.shaders.cadence.mixin.maxfps;

import com.mojang.blaze3d.platform.SDLEventHandler;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence (Record Mode, Render Max FPS): the window's events (keys, mouse, the window itself) polled at most a thousand times
 * a second instead of every frame. At 14,000 frames a second the poll was 11% of the render thread; a millisecond is far
 * below what a hand or a screen can tell apart.
 */
@Mixin(RenderSystem.class)
abstract class FastPollMixin {
	@Unique private static long cadence$lastPoll;

	@Inject(method = "pollEvents(Lcom/mojang/blaze3d/platform/SDLEventHandler;)V", at = @At("HEAD"), cancellable = true)
	private static void cadence$throttle(SDLEventHandler handler, CallbackInfo ci) {
		if (!dev.cadence.metal.record.Record.chosen() && !dev.cadence.shaders.cadence.render.MaxFpsMode.chosen()) return;
		long now = System.nanoTime();
		if (now - cadence$lastPoll < 1_000_000L) {
			ci.cancel();
			return;
		}
		cadence$lastPoll = now;
	}
}
