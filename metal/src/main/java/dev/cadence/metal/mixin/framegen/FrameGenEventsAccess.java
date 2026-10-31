package dev.cadence.metal.mixin.framegen;

import com.mojang.blaze3d.platform.SDLEventHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Frame Generation (Responsive): the game's window event handler, to read input between frames as the frame loop does. */
@Mixin(Minecraft.class)
public interface FrameGenEventsAccess {
	@Accessor("sdlEventHandler")
	SDLEventHandler cadence$sdlEventHandler();
}
