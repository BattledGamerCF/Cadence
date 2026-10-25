package dev.cadence.shaders.cadence.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Cadence: the shader keys Iris players know, under Controls > Miscellaneous: O opens Shader Packs, K turns shaders off and
 * back on to the last pack. Added to the game's key list just before it loads the saved keys (OptionsShaderKeysMixin), so it
 * works with or without Fabric API; checked once a tick while no screen is open (MinecraftShaderKeysMixin). Cadence ships no
 * language file (mod assets need Fabric API to load), so the names are written out, as a missing translation shows its key.
 */
public final class ShaderKeys {
	static final KeyMapping PACKS = new KeyMapping("Shader Packs (Cadence)", InputConstants.Type.KEYBOARD, InputConstants.KEY_O, KeyMapping.Category.MISC);
	static final KeyMapping TOGGLE = new KeyMapping("Toggle Shaders (Cadence)", InputConstants.Type.KEYBOARD, InputConstants.KEY_K, KeyMapping.Category.MISC);
	static final KeyMapping PHOTO = new KeyMapping("Photo Mode (Cadence)", InputConstants.Type.KEYBOARD, InputConstants.KEY_F9, KeyMapping.Category.MISC);

	private ShaderKeys() {
	}

	/** Options.load HEAD: the game's keys plus Cadence's (once). */
	public static KeyMapping[] add(KeyMapping[] keys) {
		if (Arrays.asList(keys).contains(PACKS)) return keys;
		KeyMapping[] out = Arrays.copyOf(keys, keys.length + 3);
		out[keys.length] = PACKS;
		out[keys.length + 1] = TOGGLE;
		out[keys.length + 2] = PHOTO;
		return out;
	}

	/** Minecraft.handleKeybinds (in a world, no screen open). */
	public static void tick(Minecraft mc) {
		while (PHOTO.consumeClick()) dev.cadence.shaders.cadence.render.PhotoMode.take(mc);
		dev.cadence.shaders.cadence.render.PhotoMode.frame(mc);
		if (!dev.cadence.shaders.CadencePackGate.metalBackend()) {
			// (no shader packs this session: say why once per press instead of opening a list that can't work)
			boolean pressed = false;
			while (PACKS.consumeClick() | TOGGLE.consumeClick()) pressed = true;
			if (pressed) dev.cadence.shaders.cadence.system.Notices.post("Shaders are off", dev.cadence.shaders.CadencePackGate.NO_METAL + ".");
			return;
		}
		while (PACKS.consumeClick()) mc.gui.setScreen(new ShaderPacksScreen(null));
		while (TOGGLE.consumeClick()) {
			String now = ShaderToggle.selectedPack();
			if (!now.isEmpty()) {
				ShaderToggle.saveKey("lastPack", now);
				ShaderToggle.choose("");
			} else {
				String last = ShaderToggle.configValue("lastPack", "").strip();
				if (last.isEmpty() || !ShaderToggle.choices().contains(last)) {
					mc.gui.setScreen(new ShaderPacksScreen(null));
					continue;
				}
				ShaderToggle.choose(last);
			}
			// (without live switching this session the choice is only saved: say so, or K would seem to do nothing)
			if (!dev.cadence.shaders.CadencePackGate.hooksApplied() && mc.player != null) {
				String pack = ShaderToggle.selectedPack();
				mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Shaders: "
					+ (pack.isEmpty() ? "Off" : ShaderToggle.display(pack)) + " from the next start (this session can't switch packs live)")
					.withStyle(net.minecraft.ChatFormatting.GRAY));
			}
		}
	}
}
