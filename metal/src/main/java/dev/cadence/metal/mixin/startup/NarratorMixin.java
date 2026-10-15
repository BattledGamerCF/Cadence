// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.startup;

import com.mojang.text2speech.Narrator;
import dev.cadence.metal.LazyNarrator;
import net.minecraft.client.GameNarrator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** -Dcadence.startup.narrator=lazy: GameNarrator gets a narrator that is created on its first use. */
@Mixin(GameNarrator.class)
abstract class NarratorMixin {
	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/text2speech/Narrator;getNarrator()Lcom/mojang/text2speech/Narrator;"))
	private static Narrator cadence$lazy() {
		return new LazyNarrator();
	}
}
