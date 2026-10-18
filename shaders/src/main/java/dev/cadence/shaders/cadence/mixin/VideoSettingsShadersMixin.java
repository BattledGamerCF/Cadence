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
		int before = this.list.children().size();
		for (Component line : dev.cadence.shaders.cadence.CadenceSettings.status())
			for (Component part : dev.cadence.shaders.cadence.CadenceSettings.wrap(line, wrap, this.font)) this.list.addHeader(part);
		// (the status rows follow the buttons: a preset or setting changed on this screen shows in them at once)
		java.util.List<net.minecraft.client.gui.components.AbstractWidget> statusRows = new java.util.ArrayList<>();
		java.util.List<? extends net.minecraft.client.gui.components.events.GuiEventListener> entries = this.list.children();
		for (int i = before; i < entries.size(); i++)
			if (entries.get(i) instanceof net.minecraft.client.gui.components.events.ContainerEventHandler entry)
				for (var child : entry.children())
					if (child instanceof net.minecraft.client.gui.components.AbstractWidget w) statusRows.add(w);
		dev.cadence.shaders.cadence.CadenceSettings.statusRows(statusRows, wrap, this.font);
		Screen self = this;
		// shaders first: the pack in use (the list behind it) and its settings
		net.minecraft.client.gui.components.Button settings = net.minecraft.client.gui.components.Button.builder(
				Component.literal("Shader Pack Settings..."), b -> {
					Screen next = dev.cadence.shaders.cadence.PackSettingsScreen.open(self);
					if (next != null) this.minecraft.gui.setScreen(next);
					else dev.cadence.shaders.cadence.system.Notices.post("Can't open this pack's settings",
						"Its files couldn't be read (see the log). The pack itself may still work.");
				})
			.width(net.minecraft.client.gui.components.Button.DEFAULT_WIDTH)
			.tooltip(dev.cadence.shaders.cadence.Tips.of("The chosen pack's own options (sun, clouds, water, shadows...), on its own pages."))
			.build();
		settings.active = !ShaderToggle.selectedPack().isEmpty();
		if (!settings.active) settings.setTooltip(dev.cadence.shaders.cadence.Tips.of("Pick a shader pack first (the Shaders button)."));
		this.list.addSmall(ShaderToggle.packsButton(self), settings);
		String mac = ShaderToggle.configValue("setupMac", "");
		this.list.addSmall(dev.cadence.shaders.cadence.CadenceSettings.presetButton(),
			net.minecraft.client.gui.components.Button.builder(Component.literal("Quick Setup..."),
				b -> this.minecraft.gui.setScreen(dev.cadence.shaders.cadence.setup.QuickSetupScreens.offer(self)))
			.width(net.minecraft.client.gui.components.Button.DEFAULT_WIDTH)
			.tooltip(dev.cadence.shaders.cadence.Tips.of("Tests your Mac (about 15 seconds) and recommends settings.",
				"#Nothing changes until you press Apply." + (mac.isEmpty() ? "" : " Last set up for: " + mac + ".")))
			.build());
		this.list.addHeader(Component.literal("Cadence: Performance"));
		this.list.addSmall(ShaderToggle.scaleButton(), ShaderToggle.upscalerButton());
		this.list.addSmall(ShaderToggle.retinaButton(), dev.cadence.shaders.cadence.CadenceSettings.leavesButton());
		this.list.addSmall(dev.cadence.shaders.cadence.CadenceSettings.heatButton(), dev.cadence.shaders.cadence.CadenceSettings.backgroundButton());
		this.list.addSmall(java.util.List.of(dev.cadence.shaders.cadence.CadenceSettings.messagesButton()));
		this.list.addHeader(Component.literal("Cadence: Shader Speed"));
		this.list.addSmall(ShaderToggle.shadowRateButton(), dev.cadence.shaders.cadence.CadenceSettings.packCheckButton());
	}

	/**
	 * Vanilla's Graphics API choice: Cadence draws with Metal whatever it says (OpenGL and Vulkan are only fallbacks), so the
	 * button is greyed out with a note instead of offering a choice that does nothing.
	 */
	@Inject(method = "addOptions", at = @At("TAIL"))
	private void cadence$graphicsApi(CallbackInfo ci) {
		if (this.list == null || !Boolean.parseBoolean(System.getProperty("cadence.metal", "true"))) return;
		var api = this.list.findOption(this.options.preferredGraphicsBackend());
		if (api == null) return;
		api.active = false;
		api.setMessage(Component.literal("Graphics API: Metal"));
		api.setTooltip(dev.cadence.shaders.cadence.Tips.of("Cadence always draws with Apple's Metal.",
			"#This setting only matters without Cadence installed."));
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void cadence$leaving(CallbackInfo ci) {
		dev.cadence.shaders.cadence.packcheck.PackCheck.cancel();
	}
}
