package dev.cadence.shaders.cadence.mixin.mesh;

import dev.cadence.shaders.cadence.mesh.MeshLightCache;
import it.unimi.dsi.fastutil.longs.Long2FloatLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (MeshLightCache): flat per-build light and shade values for the meshed section and its border. */
@Mixin(targets = "net.minecraft.client.renderer.block.BlockModelLighter$Cache")
public abstract class MeshLightCacheMixin {
	@Unique private static final int N = MeshLightCache.SPAN * MeshLightCache.SPAN * MeshLightCache.SPAN;
	@Shadow @Final private LightCoordsUtil.BrightnessGetter cachedBrightnessGetter;
	@Unique private int[] cadence$light, cadence$lightGen, cadence$shadeGen;
	@Unique private float[] cadence$shade;
	@Unique private int cadence$gen, cadence$ox, cadence$oy, cadence$oz;
	@Unique private boolean cadence$active;
	@Unique private final LightCoordsUtil.BrightnessGetter cadence$getter = this::cadence$packed;

	@Inject(method = "enable", at = @At("HEAD"))
	private void cadence$enable(CallbackInfo ci) {
		int[] origin = MeshLightCache.take();
		cadence$active = origin != null;
		if (origin == null) return;
		if (cadence$light == null) {
			cadence$light = new int[N];
			cadence$lightGen = new int[N];
			cadence$shade = new float[N];
			cadence$shadeGen = new int[N];
		}
		if (++cadence$gen == 0) {
			// wrapped after 4 billion builds: forget everything once
			java.util.Arrays.fill(cadence$lightGen, 0);
			java.util.Arrays.fill(cadence$shadeGen, 0);
			cadence$gen = 1;
		}
		cadence$ox = origin[0] - 1;
		cadence$oy = origin[1] - 1;
		cadence$oz = origin[2] - 1;
	}

	@Inject(method = "disable", at = @At("HEAD"))
	private void cadence$disable(CallbackInfo ci) {
		cadence$active = false;
	}

	@Unique
	private int cadence$index(int x, int y, int z) {
		x -= cadence$ox;
		y -= cadence$oy;
		z -= cadence$oz;
		int s = MeshLightCache.SPAN;
		if ((x | y | z) < 0 || x >= s || y >= s || z >= s) return -1;
		return (y * s + z) * s + x;
	}

	@Unique
	private int cadence$packed(BlockAndLightGetter level, BlockPos pos) {
		int i = cadence$active ? cadence$index(pos.getX(), pos.getY(), pos.getZ()) : -1;
		if (i < 0) return cachedBrightnessGetter.packedBrightness(level, pos);
		if (cadence$lightGen[i] == cadence$gen) {
			if (dev.cadence.shaders.cadence.mesh.MeshStateCache.VERIFY)
				dev.cadence.shaders.cadence.mesh.MeshStateCache.verifyLight(cadence$light[i], LightCoordsUtil.BrightnessGetter.DEFAULT.packedBrightness(level, pos));
			return cadence$light[i];
		}
		int v = LightCoordsUtil.BrightnessGetter.DEFAULT.packedBrightness(level, pos);
		cadence$light[i] = v;
		cadence$lightGen[i] = cadence$gen;
		return v;
	}

	@Redirect(method = "getLightCoords", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/renderer/block/BlockModelLighter$Cache;cachedBrightnessGetter:Lnet/minecraft/util/LightCoordsUtil$BrightnessGetter;"))
	private LightCoordsUtil.BrightnessGetter cadence$getter(net.minecraft.client.renderer.block.BlockModelLighter.Cache self) {
		return cadence$active ? cadence$getter : cachedBrightnessGetter;
	}

	@Redirect(method = "getShadeBrightness", at = @At(value = "INVOKE",
		target = "Lit/unimi/dsi/fastutil/longs/Long2FloatLinkedOpenHashMap;get(J)F"))
	private float cadence$shadeGet(Long2FloatLinkedOpenHashMap map, long key) {
		int i = cadence$active ? cadence$index(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key)) : -1;
		if (i < 0) return map.get(key);
		return cadence$shadeGen[i] == cadence$gen ? cadence$shade[i] : Float.NaN;
	}

	@Redirect(method = "getShadeBrightness", at = @At(value = "INVOKE",
		target = "Lit/unimi/dsi/fastutil/longs/Long2FloatLinkedOpenHashMap;put(JF)F"))
	private float cadence$shadePut(Long2FloatLinkedOpenHashMap map, long key, float value) {
		int i = cadence$active ? cadence$index(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key)) : -1;
		if (i < 0) return map.put(key, value);
		cadence$shade[i] = value;
		cadence$shadeGen[i] = cadence$gen;
		return Float.NaN;
	}
}
