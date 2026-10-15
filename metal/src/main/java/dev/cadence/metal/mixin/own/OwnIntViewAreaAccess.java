// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** OwnLife's scan: the view area's sections. */
@Mixin(ViewArea.class)
public interface OwnIntViewAreaAccess {
	@Accessor("sections")
	RotatingSectionStorage<SectionRenderDispatcher.RenderSection> cadence$sections();
}
