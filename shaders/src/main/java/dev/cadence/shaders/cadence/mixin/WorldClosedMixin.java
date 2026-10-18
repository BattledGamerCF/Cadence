package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.WorldClosed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (WorldClosed): let go of the old world's state when the engines are given no level (leaving a world). */
@Mixin(Minecraft.class)
public abstract class WorldClosedMixin {
	@Inject(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", at = @At("TAIL"))
	private void cadence$worldClosed(ClientLevel level, boolean stopSound, CallbackInfo ci) {
		if (level == null) WorldClosed.run();
		if (level == null) dev.cadence.shaders.cadence.PanoramaTexture.worldClosed();
	}
}
