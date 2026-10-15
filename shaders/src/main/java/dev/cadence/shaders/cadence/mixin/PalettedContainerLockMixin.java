package dev.cadence.shaders.cadence.mixin;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence: the same "two threads wrote one chunk section at once" crash, without five lock objects per container.
 *
 * <p>Every PalettedContainer (blocks and biomes of every chunk section, client and integrated server) carried a
 * ThreadingDetector: the detector, a Semaphore, its Sync, a ReentrantLock and its Sync, ~130 bytes; 133,000 of them (17 MB)
 * at RD 12, growing with render and simulation distance. They exist only to turn a mod's bug into a crash report. Here one
 * field holds the thread inside acquire/release: taken with one compare-and-set, cleared with one write. A second thread
 * arriving meanwhile gets the same ReportedException vanilla builds (ThreadingDetector.makeThreadingException, with the
 * holding thread's stack); vanilla first waited for the holder and threw on both threads, here the arriving thread throws
 * at once (the game crashes with the report either way). -Dcadence.sectionLocks=vanilla: vanilla's detector.
 */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerLockMixin {
	@Unique
	private static final boolean CADENCE$VANILLA = "vanilla".equals(System.getProperty("cadence.sectionLocks"));
	@Unique
	private static final VarHandle CADENCE$HOLDER;

	static {
		try {
			CADENCE$HOLDER = MethodHandles.lookup().findVarHandle(PalettedContainer.class, "cadence$holder", Thread.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	/** One detector for every container (its acquire/release are never called here); Mixin forbids a null. */
	@Unique
	private static final ThreadingDetector CADENCE$SHARED = new ThreadingDetector("PalettedContainer");

	@Unique
	private volatile Thread cadence$holder;

	@Redirect(method = "<init>*", at = @At(value = "NEW", target = "(Ljava/lang/String;)Lnet/minecraft/util/ThreadingDetector;"),
		require = 0)
	private static ThreadingDetector cadence$noDetector(String name) {
		return CADENCE$VANILLA ? new ThreadingDetector(name) : CADENCE$SHARED;
	}

	@Inject(method = "acquire", at = @At("HEAD"), cancellable = true)
	private void cadence$acquire(CallbackInfo ci) {
		if (CADENCE$VANILLA) return;
		Thread self = Thread.currentThread();
		while (!CADENCE$HOLDER.compareAndSet(this, (Thread) null, self)) {
			Thread holder = (Thread) CADENCE$HOLDER.getVolatile(this);
			if (holder != null) throw ThreadingDetector.makeThreadingException("PalettedContainer", holder);
		}
		ci.cancel();
	}

	@Inject(method = "release", at = @At("HEAD"), cancellable = true)
	private void cadence$release(CallbackInfo ci) {
		if (CADENCE$VANILLA) return;
		CADENCE$HOLDER.setVolatile(this, (Thread) null);
		ci.cancel();
	}
}
