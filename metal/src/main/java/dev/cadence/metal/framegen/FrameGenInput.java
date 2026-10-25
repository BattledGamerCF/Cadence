package dev.cadence.metal.framegen;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.cadence.metal.mixin.framegen.FrameGenEventsAccess;
import net.minecraft.client.Minecraft;

/** Responsive Frame Generation: the window's input read between frames, as the frame loop reads it at a frame's start. */
final class FrameGenInput {
	private FrameGenInput() {
	}

	static void poll(Minecraft mc) {
		RenderSystem.pollEvents(((FrameGenEventsAccess) mc).cadence$sdlEventHandler());
		// (the mouse's movement since the frame turns the player now: the generated frame shows it; the next frame starts from it)
		if (mc.isWindowActive() && mc.gui.screen() == null) mc.mouseHandler.handleAccumulatedMovement();
	}
}
