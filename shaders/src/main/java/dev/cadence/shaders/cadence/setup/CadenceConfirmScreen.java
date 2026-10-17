package dev.cadence.shaders.cadence.setup;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

/**
 * Cadence: vanilla's confirm screen, readable over the world. With Menu Background Blur at 0 (a common setting), vanilla
 * draws its message straight over the sky and grass; here a dark veil sits behind it when a world is showing, and the text
 * wraps at a comfortable width instead of running across the whole screen.
 */
public class CadenceConfirmScreen extends ConfirmScreen {
	private final int maxTextWidth;

	public CadenceConfirmScreen(BooleanConsumer callback, Component title, Component message, Component yes, Component no) {
		this(callback, title, message, yes, no, 330);
	}

	/** maxTextWidth: wider for lists (one change a line), narrower for prose. */
	public CadenceConfirmScreen(BooleanConsumer callback, Component title, Component message, Component yes, Component no, int maxTextWidth) {
		super(callback, title, message, yes, no);
		this.maxTextWidth = maxTextWidth;
	}

	@Override
	protected LayoutElement addMessage() {
		return new MultiLineTextWidget(this.message, this.font).setMaxWidth(Math.min(this.width - 50, this.maxTextWidth)).setMaxRows(20)
			.setCentered(true);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
		super.extractBackground(graphics, mouseX, mouseY, partial);
		if (this.minecraft != null && this.minecraft.level != null) graphics.fill(0, 0, this.width, this.height, 0xB0101014);
	}
}
