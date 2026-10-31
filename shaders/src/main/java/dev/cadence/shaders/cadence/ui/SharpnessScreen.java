package dev.cadence.shaders.cadence.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Cadence: "Sharpness & Speed" (a page under Video Settings): the settings that trade picture sharpness for frame rate, kept
 * together and off the main panel. Resolution (and its Auto), Sharpening (the upscaler) and Retina; then, for shader packs,
 * how often shadows are redrawn and Faster Shadows (the pack check).
 */
public final class SharpnessScreen extends OptionsSubScreen {
	public SharpnessScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, dev.cadence.metal.Lang.literal("Sharpness & Speed"));
	}

	@Override
	protected void addOptions() {
		this.list.addHeader(dev.cadence.metal.Lang.literal("World Resolution").withStyle(net.minecraft.ChatFormatting.BOLD, net.minecraft.ChatFormatting.UNDERLINE));
		this.list.addHeader(dev.cadence.metal.Lang.literal("Fewer pixels: more frames, a softer picture").withStyle(net.minecraft.ChatFormatting.GRAY));
		this.list.addSmall(ShaderToggle.scaleButton(), ShaderToggle.upscalerButton());
		this.list.addSmall(ShaderToggle.retinaButton(), null);
		this.list.addHeader(dev.cadence.metal.Lang.literal("With a Shader Pack").withStyle(net.minecraft.ChatFormatting.BOLD, net.minecraft.ChatFormatting.UNDERLINE));
		this.list.addSmall(ShaderToggle.shadowRateButton(), CadenceSettings.packCheckButton());
	}

	@Override
	public void removed() {
		super.removed();
		dev.cadence.shaders.cadence.packcheck.PackCheck.cancel();
	}

	/** The Video Settings button that opens this page. */
	public static Button button(Screen parent) {
		return Button.builder(dev.cadence.metal.Lang.literal("Sharpness & Speed..."), b -> Minecraft.getInstance().gui.setScreen(new SharpnessScreen(parent)))
			.width(Button.DEFAULT_WIDTH).tooltip(Tips.of(Tips.Impact.IMPROVES, "Trade picture sharpness for frame rate: Resolution, Sharpening, Retina, and shader shadows.",
				"#Lower resolution or Retina off draws fewer pixels: much higher fps, especially in full screen, with a softer picture.")).build();
	}
}
