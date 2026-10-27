package dev.cadence.shaders.cadence.mixin.ui;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence: Sound Engine (SoundEngineSetting) at the end of Options > Music & Sounds. */
@Mixin(SoundOptionsScreen.class)
public abstract class SoundOptionsEngineMixin extends OptionsSubScreen {
	private SoundOptionsEngineMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At("TAIL"))
	private void cadence$engine(CallbackInfo ci) {
		if (this.list == null) return;
		this.list.addSmall(dev.cadence.shaders.cadence.ui.SoundEngineSetting.button(), dev.cadence.shaders.cadence.ui.SoundEngineSetting.reverbButton());
	}
}
