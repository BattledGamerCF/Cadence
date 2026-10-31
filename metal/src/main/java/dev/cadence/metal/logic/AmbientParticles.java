package dev.cadence.metal.logic;

import dev.cadence.metal.mixin.logic.AmbientSamplerAccess;
import dev.cadence.metal.mixin.logic.AmbientSystemAccess;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.attribute.EnvironmentAttributeLayer;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Cadence Logic {@code ambientParticles}: the biome particles (ash, spores, white ash, warped spores, ...) of the client's
 * animate tick. Every tick ClientLevel.animateTick picks 1,334 random positions within 31 blocks of the player, and at each one
 * that isn't a full block (most of them: air) asks the environment-attribute system for AMBIENT_PARTICLES at that position:
 * a biome lookup there and the attribute's layers, about half the animate tick on a server. Only a few biomes have ambient
 * particles. When no biome that sets the attribute is in any section the lookups can reach, the biome layer passes the value
 * through unchanged everywhere, and the answer at every position is the attribute's value without its positional layers,
 * which the system already keeps per tick (getDimensionValue). Same list, same particles, same random numbers drawn.
 *
 * <p>Checked once per tick: the biome palettes of the sections within 39 blocks of the player (the 31-block reach plus the
 * biome lookup's spread). Stays with vanilla's lookup whenever the attribute has a positional layer other than vanilla's
 * biome layer (another mod's), when plains (the biome of a missing chunk) sets it, and for doAnimateTick calls that aren't
 * the animate tick's own. -Dcadence.logic.ambientVerify=true computes both answers and logs any difference;
 * -Dcadence.logic.ambientTime=true (measurement) logs the animate tick's average time every 400 ticks, with this on or off.
 */
public final class AmbientParticles {
	/** Reach of the animate tick's positions (nextInt(32) - nextInt(32)), plus the biome lookup's spread (fuzzy zoom: +-5). */
	private static final int REACH = 31 + 8;
	private static final boolean VERIFY = Boolean.getBoolean("cadence.logic.ambientVerify");
	private static Object registries;
	private static Set<Holder<Biome>> setters = Set.of();
	private static boolean plainsSets;
	private static EnvironmentAttributeSystem judged;
	private static boolean vanillaLayers;
	private static boolean inTick, active;
	private static int cx, cy, cz;
	private static long verified, differed;
	private static final boolean TIME = Boolean.getBoolean("cadence.logic.ambientTime");
	/** Measurement: -Dcadence.logic.ambientShortcut=false keeps the hooks but never takes the shortcut (ambientTime's baseline). */
	private static final boolean SHORTCUT = !"false".equals(System.getProperty("cadence.logic.ambientShortcut"));
	private static long tickStart, tickNs, ticks, shortcutTicks;

	/** Measurement (ambientTime): the animate tick started, whatever this optimization does. */
	public static void timeStart() {
		if (TIME) tickStart = System.nanoTime();
	}

	/** Measurement (ambientTime): the animate tick ended. */
	public static void timeEnd() {
		if (!TIME) return;
		tickNs += System.nanoTime() - tickStart;
		if (active) shortcutTicks++;
		if (++ticks % 400 == 0) {
			System.out.println(String.format(java.util.Locale.ROOT, "cadence-logic: animate tick %.1f us average over 400 ticks (shortcut on %d of them)", tickNs / 400e3, shortcutTicks));
			tickNs = 0;
			shortcutTicks = 0;
		}
	}

	private AmbientParticles() {
	}

	/** ClientLevel.animateTick's start (render thread): whether this tick's lookups can skip the biome layer. */
	public static void beginTick(ClientLevel level, int x, int y, int z) {
		timeStart();
		inTick = true;
		cx = x;
		cy = y;
		cz = z;
		active = false;
		try {
			if (!layersAreVanilla(level.environmentAttributes())) return;
			if (level.registryAccess() != registries) {
				registries = level.registryAccess();
				ReferenceOpenHashSet<Holder<Biome>> s = new ReferenceOpenHashSet<>();
				level.registryAccess().lookupOrThrow(Registries.BIOME).listElements().forEach(b -> {
					if (b.value().getAttributes().contains(EnvironmentAttributes.AMBIENT_PARTICLES)) s.add(b);
				});
				setters = s;
				plainsSets = s.contains(level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
			}
			if (plainsSets) return;
			active = SHORTCUT && (setters.isEmpty() || !nearby(level, x, y, z));
		} catch (RuntimeException e) {
			active = false;  // (anything unexpected: vanilla's lookups)
		}
	}

	/** ClientLevel.animateTick's end. */
	public static void endTick() {
		timeEnd();
		inTick = false;
		active = false;
	}

	/** doAnimateTick's start: only the animate tick's own calls (its centre, its reach) take the shortcut. */
	public static boolean call(int x, int y, int z, int radius) {
		return inTick && active && x == cx && y == cy && z == cz && radius <= 32;
	}

	/** In place of environmentAttributes().getValue(AMBIENT_PARTICLES, pos) in doAnimateTick. */
	public static Object value(EnvironmentAttributeSystem system, net.minecraft.world.attribute.EnvironmentAttribute<?> attribute, BlockPos pos, boolean shortcut) {
		if (!shortcut || attribute != EnvironmentAttributes.AMBIENT_PARTICLES) return system.getValue(attribute, pos);
		Object fast = system.getDimensionValue(attribute);
		if (VERIFY) {
			Object slow = system.getValue(attribute, pos);
			verified++;
			if (!slow.equals(fast)) {
				differed++;
				System.out.println("cadence-logic: ambientParticles DIFFERS at " + pos + ": " + slow + " vs " + fast);
			}
			if ((verified & 0xFFFF) == 0) System.out.println("cadence-logic: ambientParticles verify: " + verified + " lookups, " + differed + " different");
			return slow;
		}
		return fast;
	}

	/** Whether any section within REACH of the position has a biome that sets the attribute in its palette. */
	private static boolean nearby(ClientLevel level, int x, int y, int z) {
		int minY = level.getMinY(), maxY = level.getMaxY();
		int i0 = level.getSectionIndex(Math.clamp(y - REACH, minY, maxY)), i1 = level.getSectionIndex(Math.clamp(y + REACH, minY, maxY));
		for (int sx = SectionPos.blockToSectionCoord(x - REACH); sx <= SectionPos.blockToSectionCoord(x + REACH); sx++) {
			for (int sz = SectionPos.blockToSectionCoord(z - REACH); sz <= SectionPos.blockToSectionCoord(z + REACH); sz++) {
				ChunkAccess chunk = level.getChunk(sx, sz, ChunkStatus.BIOMES, false);
				if (chunk == null || chunk instanceof EmptyLevelChunk) continue;  // (vanilla's answer there: plains, checked above)
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = Math.max(0, i0); i <= Math.min(sections.length - 1, i1); i++) {
					if (sections[i].getBiomes().maybeHas(setters::contains)) return true;
				}
			}
		}
		return false;
	}

	/**
	 * The attribute's layers in this system: at most vanilla's own positional layer (the biome layer, a lambda of
	 * EnvironmentAttributeSystem). Judged once per system.
	 */
	private static boolean layersAreVanilla(EnvironmentAttributeSystem system) {
		if (system == judged) return vanillaLayers;
		judged = system;
		vanillaLayers = false;
		Object sampler = ((AmbientSystemAccess) system).cadence$attributeSamplers().get(EnvironmentAttributes.AMBIENT_PARTICLES);
		if (sampler == null) return false;
		int positional = 0;
		for (EnvironmentAttributeLayer<?> layer : ((AmbientSamplerAccess) sampler).cadence$layers()) {
			if (!(layer instanceof EnvironmentAttributeLayer.Positional<?>)) continue;
			positional++;
			if (!layer.getClass().getName().startsWith("net.minecraft.world.attribute.EnvironmentAttributeSystem$$Lambda")) return false;
		}
		vanillaLayers = positional <= 1;
		if (!vanillaLayers) System.out.println("cadence-logic: ambientParticles off for this level: " + positional + " positional layers");
		return vanillaLayers;
	}
}
