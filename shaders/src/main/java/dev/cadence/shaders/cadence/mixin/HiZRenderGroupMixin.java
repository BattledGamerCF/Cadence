package dev.cadence.shaders.cadence.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cadence.shaders.cadence.hiz.HiZ;
import dev.cadence.shaders.runtime.ShaderRenderPass;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;

/** Cadence (HiZ): the camera's opaque terrain in a pack pass drawn in two occlusion-culled phases. */
@Mixin(ChunkSectionsToRender.class)
public abstract class HiZRenderGroupMixin {
	@WrapMethod(method = "renderGroup")
	private void cadence$hiz(ChunkSectionLayerGroup group, RenderPass pass, GpuSampler sampler, GpuTextureView atlas, boolean flag,
		Operation<Void> original) {
		ChunkSectionsToRender self = (ChunkSectionsToRender) (Object) this;
		if (self instanceof ChunkSectionsToRender.DrawIndirect && !(pass instanceof ShaderRenderPass) && HiZ.appliesVanilla(self, group)) {
			HiZ.drawVanilla(self, () -> original.call(group, pass, sampler, atlas, flag));
			return;
		}
		if (!HiZ.applies(self, group, pass) || !(self instanceof ChunkSectionsToRender.DrawIndirect)) {
			original.call(group, pass, sampler, atlas, flag);
			return;
		}
		if (group == ChunkSectionLayerGroup.TRANSLUCENT) HiZ.drawTranslucent(self, (ShaderRenderPass) pass, () -> original.call(group, pass, sampler, atlas, flag));
		else HiZ.draw(self, (ShaderRenderPass) pass, () -> original.call(group, pass, sampler, atlas, flag));
	}
}
