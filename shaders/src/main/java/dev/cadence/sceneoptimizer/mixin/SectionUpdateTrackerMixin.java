// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer.mixin;

import dev.cadence.sceneoptimizer.DirtySectionMembership;
import dev.cadence.sceneoptimizer.TrackedDirtySections;
import dev.cadence.sceneoptimizer.TrackedDirtyState;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.SectionUpdateTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionUpdateTracker.class)
public abstract class SectionUpdateTrackerMixin implements TrackedDirtySections {
  @Shadow @Final
  private RotatingSectionStorage<SectionUpdateTracker.SectionDirtyState> storage;

  @Unique private DirtySectionMembership scene$dirty;

  @Inject(method = "<init>", at = @At("RETURN"))
  private void scene$bindStates(CallbackInfo callback) {
    scene$dirty = new DirtySectionMembership();
    for (var state : storage) ((TrackedDirtyState) state).scene$bindDirtyMembership(scene$dirty);
  }

  @Override
  public DirtySectionMembership scene$dirtySections() {
    return scene$dirty;
  }
}
