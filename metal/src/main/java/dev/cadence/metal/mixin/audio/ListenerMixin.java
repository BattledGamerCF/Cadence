package dev.cadence.metal.mixin.audio;

import com.mojang.blaze3d.audio.Listener;
import com.mojang.blaze3d.audio.ListenerTransform;
import dev.cadence.metal.audio.AppleAudio;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (AppleAudio): the listener's position and orientation to the Apple engine's environment while it runs. */
@Mixin(Listener.class)
abstract class ListenerMixin {
	@Shadow private ListenerTransform transform;

	@Inject(method = "setTransform", at = @At("HEAD"), cancellable = true)
	private void cadence$transform(ListenerTransform t, CallbackInfo ci) {
		if (!AppleAudio.running()) return;
		ci.cancel();
		this.transform = t;
		var p = t.position();
		var f = t.forward();
		var u = t.up();
		AppleAudio.listener(p.x, p.y, p.z, f.x, f.y, f.z, u.x, u.y, u.z);
	}
}
