// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import org.jspecify.annotations.Nullable;

/** ClientLevel.calculateBlockTint's hook (OwnMeshTintMixin): our compile's OwnRegion blends, else null (vanilla). */
public final class OwnTintHook {
	private OwnTintHook() {
	}

	public static @Nullable Integer blend(ClientLevel level, BlockPos pos, ColorResolver resolver) {
		OwnRegion r = OwnRegion.current();
		if (r == null) return null;
		int dist = Minecraft.getInstance().options.biomeBlendRadius().get();
		Integer v = dist == 0 ? null : r.blendTint(level, pos, resolver, dist);
		if (v != null && OwnMesher.VERIFY) {
			// vanilla's own blend for the same position (the hook stands aside while it runs)
			OwnRegion.suspend(true);
			int ref;
			try {
				ref = level.calculateBlockTint(pos, resolver);
			} finally {
				OwnRegion.suspend(false);
			}
			CHECKED.increment();
			if (ref != v) {
				BAD.increment();
				if (BAD.sum() <= 5) System.out.println("cadence-own mesh verify: TINT MISMATCH " + pos + " ours " + Integer.toHexString(v) + " vanilla " + Integer.toHexString(ref));
			}
		}
		return v;
	}

	private static final java.util.concurrent.atomic.LongAdder CHECKED = new java.util.concurrent.atomic.LongAdder(), BAD = new java.util.concurrent.atomic.LongAdder();

	static void log() {
		if (OwnRegion.TINT) System.out.println("cadence-own mesh verify: tint blends checked " + CHECKED.sum() + ", mismatched " + BAD.sum());
	}
}
