package dev.cadence.compattest.mixin;

import java.util.concurrent.atomic.AtomicLong;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test mod: hooks the method Cadence's entity model writer overwrites; logs that its hook runs. */
@Mixin(targets = "net.minecraft.client.model.geom.ModelPart$Cube")
abstract class CubeCountMixin {
	private static final AtomicLong CALLS = new AtomicLong();

	@Inject(method = "compile", at = @At("HEAD"))
	private void compattest$count(CallbackInfo ci) {
		long n = CALLS.incrementAndGet();
		if (n == 1 || n % 1_000_000 == 0) System.out.println("[compat-test] ModelPart.Cube.compile hook ran " + n + " times");
	}
}
