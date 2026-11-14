package dev.cadence.shaders.cadence.mixin.ui;

import dev.cadence.shaders.cadence.setup.QuickSetup;
import dev.cadence.shaders.cadence.setup.QuickSetupScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (QuickSetup): offered once at the title screen until answered on this Mac; incompatible mods named before it (ModConflicts). */
@Mixin(TitleScreen.class)
public abstract class QuickSetupOfferMixin {
	@Unique private static boolean cadence$offered;
	@Unique private static boolean cadence$conflictsShown, cadence$noticesShown;

	@Inject(method = "init", at = @At("RETURN"))
	private void cadence$offer(CallbackInfo ci) {
		if (cadence$offered) return;
		Screen self = (Screen) (Object) this;
		Minecraft mc = Minecraft.getInstance();
		// incompatible mods first (Continue comes back here, and Quick Setup is offered then)
		if (!cadence$conflictsShown) {
			cadence$conflictsShown = true;
			Screen conflicts = dev.cadence.shaders.cadence.compat.ModConflicts.screen(self);
			if (conflicts != null) {
				mc.execute(() -> mc.gui.setScreen(conflicts));
				return;
			}
		}
		// (after the conflicts screen's Continue too: it comes back here with the flag already set)
		if (!cadence$noticesShown) {
			cadence$noticesShown = true;
			dev.cadence.shaders.cadence.compat.ModConflicts.lithiumNotice();
			dev.cadence.shaders.cadence.compat.ModConflicts.dynamicFpsNotice();
		}
		cadence$offered = true;
		if (!QuickSetup.shouldOffer()) return;
		mc.execute(() -> mc.gui.setScreen(QuickSetupScreens.offer(self)));
	}
}
