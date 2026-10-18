package dev.cadence.shaders.cadence;

import dev.cadence.metal.own.DynamicLightList;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Cadence: dynamic lights (Video Settings, "Dynamic Lights"; off by default). A torch in your hand lights the world around you,
 * as a placed torch would, and so do other light sources that move: with "All", also dropped light items, burning mobs and
 * arrows, blazes, magma cubes, fireballs, lit TNT, glowing items in frames, falling light blocks, an enderman carrying one.
 *
 * <p>How: as each frame's extraction begins, the sources near the camera are gathered (the 16 nearest; DynamicLightList).
 * Terrain is lit on the GPU: Cadence's terrain vertex stage raises a vertex's block light to the brightest source's level
 * minus its distance (OwnTerrain, terrain.metal), so a moving light rebuilds nothing (LambDynamicLights rebuilds the chunks
 * around each light as it moves). Entities, the hand and held item, block entities (chests, signs) and particles take the same
 * light where their light is looked up (mixins: DynamicLightsMixin). Light passes through walls, as in LambDynamicLights.
 *
 * <p>With a shader pack on, dynamic lights step aside: the pack draws terrain with its own programs, and most packs light held
 * items themselves (Complementary's Held Lighting, ...). Without Cadence's terrain renderer (-Dcadence.own=false) only
 * entities, block entities and particles would be lit, so it steps aside there too.
 */
public final class DynamicLights {
	public enum Mode {
		OFF("Off"), HELD("Held Items"), ALL("All");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	private static volatile Mode mode = parse(System.getProperty("cadence.dynamicLights", ShaderToggle.configValue("dynamicLights", "off")));
	private static final int MAX = DynamicLightList.MAX;
	/** Sources farther from the camera than this aren't gathered (their light reaches 15 blocks at most). */
	private static final double REACH = 96;

	// this frame's sources (render thread): world position, level
	private static final double[] pos = new double[MAX * 3];
	private static final float[] level = new float[MAX];
	private static final double[] dist2 = new double[MAX];
	private static int count;
	/** Whether this frame's entity / block entity / particle light is raised (dynamic lights on and drawing). */
	private static boolean active;

	private DynamicLights() {
	}

	private static Mode parse(String s) {
		return switch (s.strip().toLowerCase(Locale.ROOT)) {
			case "held", "on", "true" -> Mode.HELD;
			case "all" -> Mode.ALL;
			default -> Mode.OFF;
		};
	}

	public static Mode mode() {
		return mode;
	}

	/** Video Settings: the next mode (Off, Held Items, All), saved. */
	public static void cycle() {
		mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
		ShaderToggle.saveKey("dynamicLights", mode == Mode.HELD ? "held" : mode.name().toLowerCase(Locale.ROOT));
	}

	/** Self-test: set the mode without saving. */
	public static void setForTest(Mode m) {
		mode = m;
	}

	/** Whether they can draw now: on, Cadence's terrain renderer in use, and no shader pack. */
	public static boolean available() {
		return dev.cadence.metal.own.OwnTerrain.FLAG && dev.cadence.shaders.runtime.ShaderRuntime.get() == null;
	}

	/** LevelExtractor.extract HEAD: this frame's sources. */
	public static void beginFrame(Camera camera, float partial) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel world = mc.level;
		count = 0;
		Mode m = mode;
		active = m != Mode.OFF && world != null && available();
		if (active) {
			Vec3 cam = camera.position();
			for (Entity e : world.entitiesForRendering()) {
				float l = luminance(e, m);
				if (l <= 0) continue;
				Vec3 p = e.getPosition(partial);
				double x = p.x, y = p.y + e.getBbHeight() * (e instanceof LivingEntity ? 0.75 : 0.5), z = p.z;
				double d2 = (x - cam.x) * (x - cam.x) + (y - cam.y) * (y - cam.y) + (z - cam.z) * (z - cam.z);
				if (d2 > REACH * REACH) continue;
				add(x, y, z, l, d2);
			}
			active = count > 0;
		}
		DynamicLightList.set(count, pos, level);
	}

	/** Keeps the MAX nearest: a farther one replaces the farthest kept. */
	private static void add(double x, double y, double z, float l, double d2) {
		int at = count;
		if (count == MAX) {
			int far = 0;
			for (int i = 1; i < MAX; i++) if (dist2[i] > dist2[far]) far = i;
			if (dist2[far] <= d2) return;
			at = far;
		} else count++;
		pos[at * 3] = x;
		pos[at * 3 + 1] = y;
		pos[at * 3 + 2] = z;
		level[at] = l;
		dist2[at] = d2;
	}

	/** Whether this frame raises entity, block entity and particle light (on, drawing, and a source near the camera). */
	public static boolean active() {
		return active;
	}

	/** The packed light coordinates at (x, y, z) with this frame's dynamic light: the block part raised, smooth (1/16 levels). */
	public static int raise(int packed, double x, double y, double z) {
		if (!active) return packed;
		float best = 0;
		for (int i = 0; i < count; i++) {
			double dx = x - pos[i * 3], dy = y - pos[i * 3 + 1], dz = z - pos[i * 3 + 2];
			best = Math.max(best, level[i] - (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		if (best <= 0) return packed;
		int block = Math.min(240, Math.round(best * 16));
		return block > (packed & 0xFFFF) ? (packed & 0xFFFF0000) | block : packed;
	}

	/** How bright {@code e} is as a light source in mode m (0 for none). */
	private static float luminance(Entity e, Mode m) {
		float l = 0;
		boolean wet = e.isUnderWater();
		if (e instanceof LivingEntity living) l = Math.max(held(living.getMainHandItem(), wet), held(living.getOffhandItem(), wet));
		if (m != Mode.ALL) return l;
		if (e.displayFireAnimation()) l = 15;
		EntityType<?> type = e.getType();
		if (e instanceof ItemEntity item) l = Math.max(l, held(item.getItem(), wet));
		else if (e instanceof ItemFrame frame) l = Math.max(l, held(frame.getItem(), false));
		else if (e instanceof FallingBlockEntity falling) l = Math.max(l, falling.getBlockState().getLightEmission());
		else if (e instanceof Enderman enderman && enderman.getCarriedBlock() != null) l = Math.max(l, enderman.getCarriedBlock().getLightEmission());
		else if (type == EntityTypes.BLAZE) l = Math.max(l, 10);
		else if (type == EntityTypes.MAGMA_CUBE) l = Math.max(l, 8);
		else if (type == EntityTypes.FIREBALL) l = Math.max(l, 14);
		else if (type == EntityTypes.SMALL_FIREBALL || type == EntityTypes.DRAGON_FIREBALL) l = Math.max(l, 12);
		else if (type == EntityTypes.TNT) l = Math.max(l, 10);
		else if (type == EntityTypes.SPECTRAL_ARROW) l = Math.max(l, 8);
		else if (type == EntityTypes.GLOW_SQUID) l = Math.max(l, 8);
		else if (type == EntityTypes.END_CRYSTAL) l = Math.max(l, 10);
		return l;
	}

	/** An item's light: a block item's block as placed, a few others by hand; fire goes out under water. */
	private static float held(ItemStack stack, boolean underWater) {
		if (stack.isEmpty()) return 0;
		int v = ITEMS.computeIfAbsent(stack.getItem(), DynamicLights::itemLight);
		if (v < 0) return underWater ? 0 : -v;
		return v;
	}

	/** Cached per item: the level, negative when fire (it goes out under water). */
	private static final Map<Item, Integer> ITEMS = new IdentityHashMap<>();

	private static int itemLight(Item item) {
		int l = 0;
		if (item instanceof BlockItem block) l = block.getBlock().defaultBlockState().getLightEmission();
		if (item == Items.LAVA_BUCKET) l = 15;
		else if (item == Items.BLAZE_ROD || item == Items.FIRE_CHARGE) l = 10;
		else if (item == Items.GLOW_BERRIES || item == Items.GLOW_INK_SAC || item == Items.NETHER_STAR) l = Math.max(l, 10);
		else if (item == Items.GLOWSTONE_DUST || item == Items.BLAZE_POWDER || item == Items.MAGMA_CREAM || item == Items.SPECTRAL_ARROW) l = 8;
		String id = BuiltInRegistries.ITEM.getKey(item).getPath();
		boolean fire = item == Items.LAVA_BUCKET || item == Items.FIRE_CHARGE || item == Items.BLAZE_ROD || id.contains("campfire")
			|| (id.contains("torch") && !id.contains("redstone"));
		return fire ? -l : l;
	}
}
