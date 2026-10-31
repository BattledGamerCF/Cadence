package dev.cadence.shaders.cadence.mixin.chunk;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence: leaving a world drops the chunk compile tasks still queued. Vanilla keeps compiling them while it waits for the
 * integrated server to save and stop, on the same worker pool the server's chunk saving needs; with a shader pack the queue
 * can hold thousands of tasks (off-camera shadow casters), and a run with vanilla's queue sat on "Saving world" for 5+
 * minutes with every worker busy compiling. The meshes would be thrown away with the level a moment later anyway.
 * -Dcadence.dropCompileOnLeave=false: vanilla.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftDisconnectMixin {
	private static final boolean CADENCE_DROP = !"false".equals(System.getProperty("cadence.dropCompileOnLeave"));

	@Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
	private void cadence$dropCompileQueue(Screen screen, boolean keepResourcePacks, boolean stopSound, CallbackInfo ci) {
		if (!CADENCE_DROP) return;
		var dispatcher = ((Minecraft) (Object) this).levelRenderer.sectionRenderDispatcher();
		if (dispatcher != null) dispatcher.clearCompileQueue();
	}
}
