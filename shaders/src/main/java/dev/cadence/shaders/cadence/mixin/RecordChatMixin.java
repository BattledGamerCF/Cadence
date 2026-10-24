package dev.cadence.shaders.cadence.mixin;

import dev.cadence.metal.record.Record;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (Record Mode): chat messages, which its own HUD shows bottom left (the game's HUD isn't built then). */
@Mixin(ChatComponent.class)
abstract class RecordChatMixin {
	@Inject(method = "addMessage", at = @At("HEAD"))
	private void cadence$recordChat(Component message, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
		if (Record.chosen()) Record.chat(message.getString());
	}
}
