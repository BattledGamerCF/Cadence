// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.scene.mixin;

import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ViewArea.class)
public interface ViewAreaSceneAccessor {
  @Invoker("getRenderSection")
  net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection scene$getRenderSection(
      long sectionNode);

  @Accessor("sections")
  RotatingSectionStorage<RenderSection> scene$sections();
}
