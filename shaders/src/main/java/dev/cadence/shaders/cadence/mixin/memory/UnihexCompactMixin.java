package dev.cadence.shaders.cadence.mixin.memory;

import com.mojang.blaze3d.font.UnbakedGlyph;
import dev.cadence.shaders.cadence.memory.UnihexCompact;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.gui.font.CodepointMap;
import net.minecraft.client.gui.font.providers.UnihexProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cadence: the unifont's glyphs packed into one array (UnihexCompact). */
@Mixin(UnihexProvider.class)
public abstract class UnihexCompactMixin {
	@SuppressWarnings("rawtypes")
	@Shadow @Final private CodepointMap glyphs;
	@Unique private UnihexCompact cadence$packed;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void cadence$pack(CallbackInfo ci) {
		cadence$packed = UnihexCompact.pack(glyphs);
	}

	@Inject(method = "getGlyph", at = @At("HEAD"), cancellable = true)
	private void cadence$glyph(int codepoint, CallbackInfoReturnable<UnbakedGlyph> cir) {
		if (cadence$packed != null) cir.setReturnValue((UnbakedGlyph) cadence$packed.glyph(codepoint));
	}

	@Inject(method = "getSupportedGlyphs", at = @At("HEAD"), cancellable = true)
	private void cadence$supported(CallbackInfoReturnable<IntSet> cir) {
		if (cadence$packed != null) cir.setReturnValue(cadence$packed.supported());
	}
}
