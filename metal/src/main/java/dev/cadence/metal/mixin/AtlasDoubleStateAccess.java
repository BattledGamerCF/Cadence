// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** -Dcadence.metal.atlasDouble: an animation's dirty flag (set for a sprite the spare copy lacks, so vanilla redraws its current frame there). */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimationState")
public interface AtlasDoubleStateAccess {
	@Accessor("isDirty")
	boolean cadence$adDirty();

	@Accessor("isDirty")
	void cadence$adSetDirty(boolean dirty);
}
