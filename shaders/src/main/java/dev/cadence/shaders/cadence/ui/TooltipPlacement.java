package dev.cadence.shaders.cadence.ui;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Vector2i;
import org.joml.Vector2ic;

/**
 * Cadence: where a button's tooltip goes. Below the button in the top half of the screen, above it in the bottom half
 * (the button's position as drawn, so scrolling counts), the other side if it only fits there, and always on screen. A
 * tooltip too tall for either side is wrapped wider until it fits.
 */
public final class TooltipPlacement {
	/** Space between the tooltip's frame and the button or the screen edge, and the frame's own size around the text. */
	private static final int GAP = 4, FRAME = 4, LINE = 10, NORMAL_WIDTH = 170;

	private TooltipPlacement() {
	}

	/** keyboard: the widget has focus from the keyboard or a controller, not the mouse: the tooltip starts at its left edge. */
	public static ClientTooltipPositioner around(ScreenRectangle widget, boolean keyboard) {
		return (screenWidth, screenHeight, mouseX, mouseY, width, height) -> {
			int below = widget.bottom() + GAP + FRAME, above = widget.top() - GAP - FRAME - height;
			boolean fitsBelow = below + height + FRAME + GAP <= screenHeight, fitsAbove = above - FRAME - GAP >= 0;
			boolean down = widget.top() + widget.bottom() < screenHeight ? fitsBelow || !fitsAbove : !fitsAbove && fitsBelow;
			if (!fitsBelow && !fitsAbove) down = screenHeight - widget.bottom() > widget.top();
			int y = down ? below : above;
			y = Math.max(GAP + FRAME, Math.min(y, screenHeight - GAP - FRAME - height));
			int x = Math.max(GAP + FRAME, Math.min(keyboard ? widget.left() + 3 : mouseX + 12, screenWidth - GAP - FRAME - width));
			return new Vector2i(x, y);
		};
	}

	/** The tooltip's lines, wrapped wider than usual when they are too tall for the room above or below the button. */
	public static List<FormattedCharSequence> fit(Minecraft mc, Component message, List<FormattedCharSequence> lines, ScreenRectangle widget) {
		int screenWidth = mc.getWindow().getGuiScaledWidth(), screenHeight = mc.getWindow().getGuiScaledHeight();
		int room = Math.max(widget.top(), screenHeight - widget.bottom()) - 2 * (GAP + FRAME);
		if (height(lines) <= room || message == null) return lines;
		int widest = screenWidth - 2 * (GAP + FRAME);
		for (int width = NORMAL_WIDTH + 60; ; width += 60) {
			List<FormattedCharSequence> wider = mc.font.split(message, Math.min(width, widest));
			if (height(wider) <= room || width >= widest) return wider;
		}
	}

	/**
	 * Self-test: where the tooltip of a widget at {@code widget} lands with the mouse on its centre, as drawn; a description of
	 * what goes off screen, or null if all of it shows.
	 */
	public static String offScreen(Minecraft mc, net.minecraft.client.gui.components.Tooltip tooltip, Component message, ScreenRectangle widget) {
		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		List<FormattedCharSequence> lines = fit(mc, message, tooltip.toCharSequence(mc), widget);
		int w = 0;
		for (FormattedCharSequence l : lines) w = Math.max(w, mc.font.width(l));
		int h = height(lines);
		Vector2ic at = around(widget, false).positionTooltip(sw, sh, widget.left() + widget.width() / 2, widget.top() + widget.height() / 2, w, h);
		int left = at.x() - FRAME, top = at.y() - FRAME, right = at.x() + w + FRAME, bottom = at.y() + h + FRAME;
		if (left >= 0 && top >= 0 && right <= sw && bottom <= sh) return null;
		return "box " + left + "," + top + " to " + right + "," + bottom + " on a " + sw + " x " + sh + " screen, " + lines.size() + " lines";
	}

	private static int height(List<FormattedCharSequence> lines) {
		return lines.size() * LINE + (lines.size() > 1 ? 2 : 0);
	}
}
