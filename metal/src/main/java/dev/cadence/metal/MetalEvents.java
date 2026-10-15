// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/** Opt-in CPU-side spans. JFR timestamps line these up with GC, samples and benchmark frames. */
final class MetalEvents {
	private static final boolean ENABLED = Boolean.getBoolean("cadence.metal.jfr");

	@Name("cadence.MetalOperation")
	@Label("Metal operation")
	@Category("Cadence")
	@Threshold("1 ms")
	@StackTrace(true)
	static final class Operation extends Event {
		@Label("Operation") String operation;
		@Label("Submit") long submit;
		@Label("Bytes") @jdk.jfr.DataAmount long bytes;
		@Label("Arena") boolean arena;
		@Label("Main command buffer GPU microseconds") double gpuMicros;
	}

	static Operation begin(String operation, long submit, long bytes) {
		if (!ENABLED) return null;
		Operation event = new Operation();
		if (!event.isEnabled()) return null;
		event.operation = operation;
		event.submit = submit;
		event.bytes = bytes;
		event.begin();
		return event;
	}

	@Name("cadence.MetalArenaTransfer")
	@Label("Metal arena transfer")
	@Category("Cadence")
	@StackTrace(true)
	static final class ArenaTransfer extends Event {
		String operation;
		@jdk.jfr.DataAmount long bytes;
		@jdk.jfr.DataAmount long sourceCapacity;
		@jdk.jfr.DataAmount long destinationCapacity;
	}

	/** Fast-to-encode copies can still cause a later GPU wait. Keep their sizes even below the operation threshold. */
	static void arenaTransfer(String operation, long bytes, long sourceCapacity, long destinationCapacity) {
		if (!ENABLED) return;
		ArenaTransfer event = new ArenaTransfer();
		if (!event.isEnabled()) return;
		event.operation = operation;
		event.bytes = bytes;
		event.sourceCapacity = sourceCapacity;
		event.destinationCapacity = destinationCapacity;
		event.commit();
	}

	static void end(Operation event) {
		if (event == null) return;
		event.end();
		event.commit();
	}
}
