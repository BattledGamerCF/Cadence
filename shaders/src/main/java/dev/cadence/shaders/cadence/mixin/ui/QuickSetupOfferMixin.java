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

/** Cadence (QuickSetup): offered once at the title screen until answered on this Mac. */
@Mixin(TitleScreen.class)
public abstract class QuickSetupOfferMixin {
	@Unique private static boolean cadence$offered;

	@Inject(method = "init", at = @At("RETURN"))
	private void cadence$offer(CallbackInfo ci) {
		if (cadence$offered) return;
		cadence$offered = true;
		if (!QuickSetup.shouldOffer()) return;
		Screen self = (Screen) (Object) this;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.setScreen(QuickSetupScreens.offer(self)));
	}
}
