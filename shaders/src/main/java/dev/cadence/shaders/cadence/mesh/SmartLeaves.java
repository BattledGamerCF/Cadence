package dev.cadence.shaders.cadence.mesh;

import dev.cadence.shaders.cadence.ShaderToggle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Cadence: "Leaves: Smart" (Video Settings, opt-in; it changes what's drawn). With see-through leaves, vanilla draws every
 * face between two leaf blocks, so a canopy is drawn as a solid stack of alpha-tested layers. Alpha testing turns off the
 * GPU's hidden-surface removal on Apple's tiled GPUs, so every layer is shaded: in a dark forest without shaders leaves took
 * the frame from 2.7 ms (solid leaves) to 4.9 ms. Smart keeps the outer two layers of every canopy exactly as vanilla draws
 * them and skips a leaf face only when the leaf it faces has another leaf behind it (two leaves deep). From outside a tree
 * looks the same; through its gaps you see one layer less far in. Only while see-through leaves are on (vanilla's option);
 * only in chunk meshing (the block being built is known from SectionCompiler.compile).
 */
public final class SmartLeaves {
	private static volatile boolean enabled = "true".equalsIgnoreCase(
		System.getProperty("cadence.smartLeaves", ShaderToggle.configValue("smartLeaves", "false")).strip());
	private static final ThreadLocal<SmartLeaves> CURRENT = ThreadLocal.withInitial(SmartLeaves::new);
	private RenderSectionRegion region;
	private BlockPos pos;
	private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

	private SmartLeaves() {
	}

	public static boolean enabled() {
		return enabled;
	}

	/** Saved, and every chunk rebuilt (render thread). */
	public static void setEnabled(boolean on) {
		enabled = on;
		ShaderToggle.saveKey("smartLeaves", Boolean.toString(on));
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) mc.levelExtractor.allChanged();
	}

	/** SectionCompiler.compile HEAD / RETURN (worker thread). */
	public static void begin(RenderSectionRegion region) {
		CURRENT.get().region = region;
	}

	public static void end() {
		SmartLeaves s = CURRENT.get();
		s.region = null;
		s.pos = null;
	}

	/** The block about to be meshed (its position from the compile loop). */
	public static BlockPos block(BlockPos pos) {
		if (enabled) CURRENT.get().pos = pos;
		return pos;
	}

	/** LeavesBlock.skipRendering's answer, made smart: skip a face into a leaf that has a leaf behind it. */
	public static boolean skip(boolean vanilla, BlockState neighbor, Direction direction) {
		if (vanilla || !enabled || !(neighbor.getBlock() instanceof LeavesBlock)) return vanilla;
		SmartLeaves s = CURRENT.get();
		if (s.region == null || s.pos == null) return false;
		s.probe.setWithOffset(s.pos, direction.getStepX() * 2, direction.getStepY() * 2, direction.getStepZ() * 2);
		return s.region.getBlockState(s.probe).getBlock() instanceof LeavesBlock;
	}
}
