package dev.cadence.metal.mixin.logic;

import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.schedule.Activity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence Logic {@code brain}: every tick, every brain-driven mob (villagers, piglins, hoglins, axolotls, goats, frogs, allays,
 * camels, sniffers, wardens...) walks its behaviour table, a sorted map of priority -> hash map of activity -> ordered set,
 * twice: once to start behaviours whose activity is active, once to build a fresh list of the running ones and tick them.
 * Each walk allocates several iterators per mob per tick. The table only changes in addActivity and removeAllBehaviors, so it
 * is flattened once into arrays in exactly the order those walks take, and rebuilt after either changes. The same
 * behaviours are checked and started in the same order, the active-activity test is made at the same points (once per
 * priority and activity), and the running ones are collected before any is ticked, as vanilla does.
 */
@Mixin(Brain.class)
abstract class BrainMixin<E extends LivingEntity> {
	@Shadow @Final private Map<Integer, Map<Activity, Set<BehaviorControl<? super E>>>> availableBehaviorsByPriority;
	@Shadow @Final private Set<Activity> activeActivities;

	/** Bumped when the table changes; the flat copy is rebuilt when it differs from cadence$builtVersion. */
	@Unique private int cadence$version;
	@Unique private int cadence$builtVersion = -1;
	/** Behaviours in table order; segment s is (priority, activity) cadence$segActivity[s], spanning [segStart[s], segStart[s+1]). */
	@Unique private BehaviorControl<? super E>[] cadence$flat;
	@Unique private Activity[] cadence$segActivity;
	@Unique private int[] cadence$segStart;
	@Unique private int cadence$segments;
	/** Scratch for the running behaviours of one tick; null while it is in use (a re-entrant call allocates its own). */
	@Unique private BehaviorControl<? super E>[] cadence$running;

	@Inject(method = "addActivity", at = @At("TAIL"))
	private void cadence$addedActivity(CallbackInfo ci) {
		this.cadence$version++;
	}

	@Inject(method = "removeAllBehaviors", at = @At("TAIL"))
	private void cadence$removedBehaviors(CallbackInfo ci) {
		this.cadence$version++;
	}

	@SuppressWarnings("unchecked")
	@Unique
	private void cadence$flatten() {
		int total = 0, segments = 0;
		for (Map<Activity, Set<BehaviorControl<? super E>>> byActivity : this.availableBehaviorsByPriority.values()) {
			segments += byActivity.size();
			for (Set<BehaviorControl<? super E>> set : byActivity.values()) total += set.size();
		}
		BehaviorControl<? super E>[] flat = new BehaviorControl[total];
		Activity[] segActivity = new Activity[segments];
		int[] segStart = new int[segments + 1];
		int i = 0, s = 0;
		for (Map<Activity, Set<BehaviorControl<? super E>>> byActivity : this.availableBehaviorsByPriority.values()) {
			for (Map.Entry<Activity, Set<BehaviorControl<? super E>>> entry : byActivity.entrySet()) {
				segActivity[s] = entry.getKey();
				segStart[s] = i;
				for (BehaviorControl<? super E> behavior : entry.getValue()) flat[i++] = behavior;
				s++;
			}
		}
		segStart[s] = i;
		this.cadence$flat = flat;
		this.cadence$segActivity = segActivity;
		this.cadence$segStart = segStart;
		this.cadence$segments = segments;
		this.cadence$running = new BehaviorControl[total];
		this.cadence$builtVersion = this.cadence$version;
	}

	/**
	 * @author Cadence
	 * @reason the same behaviours started in the same order, from a flat copy of the table (no iterators per tick)
	 */
	@Overwrite
	private void startEachNonRunningBehavior(ServerLevel level, E body) {
		long time = level.getGameTime();
		if (this.cadence$builtVersion != this.cadence$version) cadence$flatten();
		BehaviorControl<? super E>[] flat = this.cadence$flat;
		Activity[] segActivity = this.cadence$segActivity;
		int[] segStart = this.cadence$segStart;
		for (int s = 0, segments = this.cadence$segments; s < segments; s++) {
			if (!this.activeActivities.contains(segActivity[s])) continue;
			for (int i = segStart[s], end = segStart[s + 1]; i < end; i++) {
				BehaviorControl<? super E> behavior = flat[i];
				if (behavior.getStatus() == Behavior.Status.STOPPED) behavior.tryStart(level, body, time);
			}
		}
	}

	/**
	 * @author Cadence
	 * @reason the running behaviours collected in table order, then ticked, as vanilla, without a new list per tick
	 */
	@SuppressWarnings("unchecked")
	@Overwrite
	private void tickEachRunningBehavior(ServerLevel level, E body) {
		long time = level.getGameTime();
		if (this.cadence$builtVersion != this.cadence$version) cadence$flatten();
		BehaviorControl<? super E>[] flat = this.cadence$flat;
		BehaviorControl<? super E>[] running = this.cadence$running;
		this.cadence$running = null;
		if (running == null) running = new BehaviorControl[flat.length];
		int count = 0;
		for (BehaviorControl<? super E> behavior : flat) if (behavior.getStatus() == Behavior.Status.RUNNING) running[count++] = behavior;
		try {
			for (int i = 0; i < count; i++) running[i].tickOrStop(level, body, time);
		} finally {
			java.util.Arrays.fill(running, 0, count, null);
			if (running.length == flat.length && this.cadence$flat == flat) this.cadence$running = running;
		}
	}
}
