package dev.cadence.shaders.cadence.mixin.ui;

import dev.cadence.shaders.cadence.ui.TooltipPlacement;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cadence (TooltipPlacement): button tooltips flip above or below by where the button is, and stay on screen. */
@Mixin(WidgetTooltipHolder.class)
public abstract class TooltipPlacementMixin {
	@Inject(method = "createTooltipPositioner", at = @At("HEAD"), cancellable = true)
	private void cadence$place(ScreenRectangle widget, boolean hovered, boolean focused, CallbackInfoReturnable<ClientTooltipPositioner> cir) {
		cir.setReturnValue(TooltipPlacement.around(widget));
	}

	@Redirect(method = "refreshTooltipForNextRenderPass", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/Tooltip;toCharSequence(Lnet/minecraft/client/Minecraft;)Ljava/util/List;"))
	private List<FormattedCharSequence> cadence$fit(Tooltip tooltip, Minecraft mc, GuiGraphicsExtractor graphics, int mouseX, int mouseY,
		boolean hovered, boolean focused, ScreenRectangle widget) {
		return TooltipPlacement.fit(mc, ((TooltipAccess) tooltip).cadence$message(), tooltip.toCharSequence(mc), widget);
	}
}
