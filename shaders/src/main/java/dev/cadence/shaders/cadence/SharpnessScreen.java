package dev.cadence.shaders.cadence;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Cadence: "Sharpness & Speed" (a page under Video Settings): the settings that trade picture sharpness for frame rate, kept
 * together and off the main panel. Resolution (and its Auto), Sharpening (the upscaler) and Retina.
 */
public final class SharpnessScreen extends OptionsSubScreen {
	public SharpnessScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.literal("Sharpness & Speed"));
	}

	@Override
	protected void addOptions() {
		this.list.addHeader(Component.literal("Fewer pixels: more frames, a softer picture"));
		this.list.addSmall(ShaderToggle.scaleButton(), ShaderToggle.upscalerButton());
		this.list.addSmall(ShaderToggle.retinaButton(), null);
	}

	/** The Video Settings button that opens this page. */
	public static Button button(Screen parent) {
		return Button.builder(Component.literal("Sharpness & Speed..."), b -> Minecraft.getInstance().gui.setScreen(new SharpnessScreen(parent)))
			.width(Button.DEFAULT_WIDTH).tooltip(Tips.of("Trade picture sharpness for frame rate: Resolution, Sharpening and Retina.",
				"#Lower resolution or Retina off draws fewer pixels: much higher fps, especially in full screen, with a softer picture.")).build();
	}
}
