package dev.cadence.metal.mixin.cpu;

import dev.cadence.metal.cpu.Cpu;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.resources.language.FormattedBidiReorder;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.SubStringSource;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cadence (Cpu.textBidi; -Dcadence.cpu.textBidi=false: vanilla): every piece of text the game draws (the HUD, chat, signs,
 * name tags, menus, the frame counter) goes through ICU's bidirectional reordering and Arabic shaping each frame, for
 * right-to-left scripts. In the benchmark scene that was ~8% of the render thread's allocation (Bidi's level arrays, the
 * shaper's char arrays). With a left-to-right language and text that holds no character from U+0590 up (Hebrew, Arabic and
 * every other right-to-left letter, Arabic numbers and the explicit direction marks all start there), the algorithm gives a
 * single left-to-right run over the whole text and shaping changes nothing, so the result is the text in order: built here
 * through the same SubStringSource, without ICU. Anything else goes to vanilla. -Dcadence.cpu.textVerify=true runs both and
 * compares every character and style.
 */
@Mixin(FormattedBidiReorder.class)
abstract class TextBidiMixin {
	@Unique private static final Optional<Boolean> CADENCE_STOP = Optional.of(Boolean.TRUE);
	@Unique private static long cadence$verified, cadence$mismatches;

	@Inject(method = "reorder", at = @At("HEAD"), cancellable = true)
	private static void cadence$leftToRight(FormattedText text, boolean defaultRightToLeft, CallbackInfoReturnable<FormattedCharSequence> cir) {
		if (!Cpu.textBidi || defaultRightToLeft) return;
		// (a non-capturing lambda: no allocation for the check)
		if (text.visit(s -> {
			for (int i = 0, n = s.length(); i < n; i++) if (s.charAt(i) >= 0x0590) return CADENCE_STOP;
			return Optional.empty();
		}).isPresent()) return;
		SubStringSource source = SubStringSource.create(text, c -> c, s -> s);
		int length = source.getPlainText().length();
		FormattedCharSequence fast = FormattedCharSequence.composite(length == 0 ? List.of() : source.substring(0, length, false));
		if (Cpu.TEXT_VERIFY) cadence$verify(text, fast);
		cir.setReturnValue(fast);
	}

	@Unique
	private static void cadence$verify(FormattedText text, FormattedCharSequence fast) {
		Cpu.textBidi = false;
		FormattedCharSequence vanilla;
		try {
			vanilla = FormattedBidiReorder.reorder(text, false);
		} finally {
			Cpu.textBidi = true;
		}
		List<Object> a = new ArrayList<>(), b = new ArrayList<>();
		fast.accept((i, style, cp) -> { a.add(style); a.add(cp); return true; });
		vanilla.accept((i, style, cp) -> { b.add(style); b.add(cp); return true; });
		if (!a.equals(b) && cadence$mismatches++ < 5) System.out.println("cadence-cpu: textBidi MISMATCH for \"" + text.getString() + "\"");
		if (++cadence$verified % 100000 == 0 || cadence$verified == 1000)
			System.out.println("cadence-cpu: textBidi verified " + cadence$verified + " texts, " + cadence$mismatches + " mismatches");
	}
}
