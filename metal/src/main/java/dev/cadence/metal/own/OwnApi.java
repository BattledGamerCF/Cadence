// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Mod compatibility of our mesher. With Fabric API's renderer API
 * (FRAPI, fabric-renderer-api-v1) loaded, vanilla's section compile is patched by Fabric API itself to tesselate every block
 * through the active FRAPI renderer (Indigo unless a mod brings another), so block models that emit their own quads (connected
 * textures, emissive, per-quad layers, Meshes) render. Our mesher replaces that compile, so it must do the same: OwnFrapi
 * (the only class that touches FRAPI types, loaded only when FRAPI is) drives the renderer's block tesselator and writes what
 * it emits into our compact quads. Vanilla types only here, so this class loads without Fabric API.
 * -Dcadence.own.api=false: vanilla's ModelBlockRenderer even with FRAPI loaded (FRAPI models then render as their vanilla parts).
 */
public final class OwnApi {
	/** FRAPI is loaded and our mesher goes through it. */
	public static final boolean FRAPI = FabricLoader.getInstance().isModLoaded("fabric-renderer-api-v1") && !"false".equals(System.getProperty("cadence.own.api"));

	private OwnApi() {
	}

	/** One section's block tesselator. */
	public interface Mesher {
		void block(float x, float y, float z, BlockAndTintGetter level, BlockPos pos, BlockState state, BlockStateModel model, long seed);
	}

	/**
	 * The FRAPI tesselator for one section on this thread, or null without FRAPI (then vanilla's ModelBlockRenderer). Solid and
	 * cutout quads go to quads, translucent ones to the consumer translucent supplies (asked once, on the first such quad).
	 */
	public static @Nullable Mesher mesher(boolean ambientOcclusion, BlockColors blockColors, OwnQuads quads, Supplier<VertexConsumer> translucent) {
		return FRAPI ? OwnFrapi.get(ambientOcclusion, blockColors, quads, translucent) : null;
	}
}
