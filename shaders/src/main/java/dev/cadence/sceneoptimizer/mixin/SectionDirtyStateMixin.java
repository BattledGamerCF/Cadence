// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer.mixin;

import dev.cadence.sceneoptimizer.DirtySectionMembership;
import dev.cadence.sceneoptimizer.TrackedDirtyState;
import net.minecraft.client.SectionUpdateTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionUpdateTracker.SectionDirtyState.class)
public abstract class SectionDirtyStateMixin implements TrackedDirtyState {
  @Shadow public abstract long getSectionNode();
  @Shadow public abstract boolean isDirty();
  @Unique private DirtySectionMembership scene$membership;

  @Override
  public void scene$bindDirtyMembership(DirtySectionMembership membership) {
    if (scene$membership != null) scene$membership.remove(getSectionNode(), this);
    scene$membership = membership;
    membership.update(getSectionNode(), this, isDirty());
  }

  @Inject(method = {"setDirty", "setNotDirty", "setSectionNode"}, at = @At("RETURN"))
  private void scene$updateMembership(CallbackInfo callback) {
    if (scene$membership != null) scene$membership.update(getSectionNode(), this, isDirty());
  }

  @Inject(method = "setSectionNode", at = @At("HEAD"))
  private void scene$removeOldNode(long node, CallbackInfo callback) {
    if (scene$membership != null) scene$membership.remove(getSectionNode(), this);
  }
}
