package dev.cadence.shaders.cadence.mixin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence (ParallelWorldgen): a structure template's per-block cache is filled with computeIfAbsent while pieces are placed;
 * two chunks may now place pieces of one template (houses of two villages) at once, so it is a concurrent map (still
 * empty at this point). Same contents.
 */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructurePaletteCacheMixin {
	@Shadow @Final @Mutable
	private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void cadence$concurrentCache(CallbackInfo ci) {
		this.cache = new ConcurrentHashMap<>();
	}
}
