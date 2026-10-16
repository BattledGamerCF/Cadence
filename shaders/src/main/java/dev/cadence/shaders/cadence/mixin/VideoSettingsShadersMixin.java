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

/**
 * Cadence: every Cadence setting at the top of Video Settings, under its own headings (no separate screen to find), with the
 * status lines wrapped to the list's width so they never run past the menu at any GUI scale.
 */
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
		// what Cadence tunes on its own (always one line), then anything it is easing right now (heat, memory, Auto resolution)
		int wrap = Math.max(120, Math.min(this.list.getRowWidth(), this.width - 24));
		for (Component line : dev.cadence.shaders.cadence.CadenceSettings.status())
			for (Component part : dev.cadence.shaders.cadence.CadenceSettings.wrap(line, wrap, this.font)) this.list.addHeader(part);
		Screen self = this;
		this.list.addSmall(dev.cadence.shaders.cadence.CadenceSettings.presetButton(),
			net.minecraft.client.gui.components.Button.builder(Component.literal("Quick Setup..."),
				b -> this.minecraft.gui.setScreen(dev.cadence.shaders.cadence.setup.QuickSetupScreens.offer(self)))
			.width(net.minecraft.client.gui.components.Button.DEFAULT_WIDTH)
			.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				"Checks what your Mac can do (about 15 seconds) and recommends settings for it. Nothing changes unless you press Apply.")))
			.build());
		this.list.addHeader(Component.literal("Cadence: Performance"));
		this.list.addSmall(dev.cadence.shaders.cadence.CadenceSettings.heatButton(), dev.cadence.shaders.cadence.CadenceSettings.backgroundButton());
		this.list.addSmall(ShaderToggle.scaleButton(), ShaderToggle.upscalerButton());
		this.list.addSmall(dev.cadence.shaders.cadence.CadenceSettings.leavesButton(), dev.cadence.shaders.cadence.CadenceSettings.messagesButton());
		this.list.addHeader(Component.literal("Cadence: Shaders"));
		this.list.addSmall(ShaderToggle.packButton(), ShaderToggle.qualityButton());
		this.list.addSmall(ShaderToggle.shadowRateButton(), ShaderToggle.liveButton());
		this.list.addSmall(dev.cadence.shaders.cadence.CadenceSettings.packCheckButton(), ShaderToggle.folderButton());
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void cadence$applyShaders(CallbackInfo ci) {
		dev.cadence.shaders.cadence.packcheck.PackCheck.cancel();
		ShaderToggle.applyPending();
	}
}
