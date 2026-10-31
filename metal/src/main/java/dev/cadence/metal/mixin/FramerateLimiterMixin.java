// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin;

import dev.cadence.metal.backend.FrameWait;
import net.minecraft.client.FramerateLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's frame cap parks the render thread with parkNanos, then spins until the frame's deadline. On macOS that park is
 * a coalescable timer that wakes up to a quarter or more of the wait late (a 60 fps cap ran at 51 fps with Sodium), and the
 * spin burns a core for nothing. Here one wait on a timer that fires on time, no spin, on a grid of deadlines that doesn't
 * drift. Applies to every cap: the fps option, menus, AFK and minimized.
 */
@Mixin(FramerateLimiter.class)
abstract class FramerateLimiterMixin {
	@Shadow
	private static long lastFrameTime;

	@Inject(method = "limitDisplayFPS", at = @At("HEAD"), cancellable = true)
	private static void cadence$preciseWait(int framerateLimit, CallbackInfo ci) {
		if (!FrameWait.ENABLED) return;
		// Cadence: frames on a fixed grid of deadlines, each one interval after the last deadline (not after the last wait ended:
		// every wait's small overshoot then added up, and a 120 cap ran at 119.x, the counter showing 119 with jumps to 120). A
		// frame late by more than an interval starts a new grid at once instead of hurrying to catch up.
		long interval = 1_000_000_000L / framerateLimit, deadline = lastFrameTime + interval, now = System.nanoTime();
		long remaining = deadline - now;
		if (remaining > 0) {
			// Frame Generation (Responsive): a frame made halfway through the wait, from the input read then
			long mid = dev.cadence.metal.framegen.FrameGen.responsiveAt(remaining);
			if (mid > 0) {
				FrameWait.sleep(mid);
				dev.cadence.metal.framegen.FrameGen.responsiveFrame();
				remaining = deadline - System.nanoTime();
			}
			if (remaining > 0) FrameWait.sleep(remaining);
			lastFrameTime = deadline;
		} else {
			lastFrameTime = -remaining > interval ? now : deadline;
		}
		ci.cancel();
	}
}
