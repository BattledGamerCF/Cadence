package dev.cadence.shaders.cadence;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Cadence Settings (from Video Settings): everything beyond the few choices most players need, so Video Settings stays
 * short. Every button explains itself in its tooltip.
 */
public final class CadenceSettingsScreen extends OptionsSubScreen {
	public CadenceSettingsScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.literal("Cadence Settings"));
	}

	@Override
	protected void addOptions() {
		CadenceSettings.newScreen();
		this.list.addHeader(Component.literal("Performance"));
		this.list.addSmall(CadenceSettings.heatButton(), CadenceSettings.backgroundButton());
		this.list.addSmall(ShaderToggle.scaleButton(), ShaderToggle.upscalerButton());
		this.list.addSmall(CadenceSettings.leavesButton(), CadenceSettings.messagesButton());
		this.list.addHeader(Component.literal("Shaders"));
		this.list.addSmall(ShaderToggle.qualityButton(), ShaderToggle.shadowRateButton());
		this.list.addSmall(ShaderToggle.liveButton(), CadenceSettings.packCheckButton());
		this.list.addSmall(ShaderToggle.folderButton(), null);
	}

	@Override
	public void removed() {
		super.removed();
		dev.cadence.shaders.cadence.packcheck.PackCheck.cancel();
		ShaderToggle.applyPending();
	}
}
