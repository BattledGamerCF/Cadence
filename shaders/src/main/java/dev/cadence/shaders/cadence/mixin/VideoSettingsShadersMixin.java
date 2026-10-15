package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.ShaderToggle;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence: a short Cadence section at the top of Video Settings (the rest is in Cadence Settings). */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsShadersMixin extends OptionsSubScreen {
	private VideoSettingsShadersMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At("HEAD"))
	private void cadence$shaders(CallbackInfo ci) {
		if (this.list == null) return;
		dev.cadence.shaders.cadence.CadenceSettings.newScreen();
		this.list.addHeader(Component.literal("Cadence"));
		this.list.addSmall(dev.cadence.shaders.cadence.CadenceSettings.presetButton(), ShaderToggle.packButton());
		// only when Cadence is adjusting something on its own (heat, memory, Auto resolution): nothing to read otherwise
		for (Component line : dev.cadence.shaders.cadence.CadenceSettings.status()) this.list.addHeader(line);
		Screen self = this;
		this.list.addSmall(net.minecraft.client.gui.components.Button.builder(Component.literal("Quick Setup..."),
				b -> this.minecraft.gui.setScreen(dev.cadence.shaders.cadence.setup.QuickSetupScreens.offer(self)))
			.width(net.minecraft.client.gui.components.Button.DEFAULT_WIDTH)
			.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				"Checks what your Mac can do (about 15 seconds) and recommends settings for it. Nothing changes unless you press Apply.")))
			.build(),
			net.minecraft.client.gui.components.Button.builder(Component.literal("Cadence Settings..."),
				b -> this.minecraft.gui.setScreen(new dev.cadence.shaders.cadence.CadenceSettingsScreen(self)))
			.width(net.minecraft.client.gui.components.Button.DEFAULT_WIDTH)
			.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				"Heat Control, resolution, leaves, messages and shader options.")))
			.build());
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void cadence$applyShaders(CallbackInfo ci) {
		dev.cadence.shaders.cadence.packcheck.PackCheck.cancel();
		ShaderToggle.applyPending();
	}
}
