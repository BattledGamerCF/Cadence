// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.system;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Thread QoS roles, on by default (measured on the test Macs): the render thread user-interactive and the integrated
 * server user-initiated; -Dcadence.qos=off turns them all off (the mixins then don't apply). {@code -Dcadence.qos.ROLE=CLASS}
 * for ROLE render (the render thread), server (the integrated server thread), mesh (Sodium's chunk builders), cull (Sodium's cull
 * thread), worker (Worker-Main) or io (IO-Worker); CLASS background, utility, default, initiated or interactive. Each thread puts
 * itself in its class through libSystem's pthread_set_qos_class_self_np as it starts (a thread may only set its own class).
 */
public final class Qos {
	private static final Map<String, Integer> LEVELS = new HashMap<>();
	private static MethodHandle setSelf;

	/**
	 * -Dcadence.qos=on|off: the measured default set, render user-interactive and server user-initiated (measured:
	 * Neo busy area flight +17% fps, 1% lows +63%, MSPT -25%; hot laptop MSPT p99 -57..-71%), with the meshers deliberately left
	 * alone (raising them cost the laptop's 0.1% lows 21%). DEFAULT_MODE is what applies with no property. A role's own property
	 * overrides the set; "none" removes that role.
	 */
	static final String DEFAULT_MODE = "on";

	static {
		if (!"off".equals(System.getProperty("cadence.qos", DEFAULT_MODE))) {
			LEVELS.put("render", 0x21);
			LEVELS.put("server", 0x19);
		}
		for (String role : new String[] {"render", "server", "mesh", "cull", "worker", "io"}) {
			String v = System.getProperty("cadence.qos." + role);
			if ("none".equals(v)) LEVELS.remove(role);
			int cls = parse(v);
			if (cls != 0) LEVELS.put(role, cls);
		}
	}

	private Qos() {
	}

	private static int parse(String name) {
		if (name == null) return 0;
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "background" -> 0x09;
			case "utility" -> 0x11;
			case "default" -> 0x15;
			case "initiated" -> 0x19;
			case "interactive" -> 0x21;
			default -> 0;
		};
	}

	public static boolean configured() {
		return !LEVELS.isEmpty();
	}

	public static boolean has(String role) {
		return LEVELS.containsKey(role);
	}

	/** Called on a thread of this role from its own body: puts the calling thread in the role's QoS class, if one is set. */
	public static void self(String role) {
		Integer cls = LEVELS.get(role);
		if (cls == null) return;
		try {
			synchronized (Qos.class) {
				if (setSelf == null) {
					Linker linker = Linker.nativeLinker();
					setSelf = linker.downcallHandle(linker.defaultLookup().find("pthread_set_qos_class_self_np").orElseThrow(),
						FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
				}
			}
			int rc = (int) setSelf.invokeExact((int) cls, 0);
			if (rc != 0 || Boolean.getBoolean("cadence.qos.log")) System.out.println("cadence: QoS 0x" + Integer.toHexString(cls) + " for " + role + " (" + Thread.currentThread().getName() + "): " + rc);
		} catch (Throwable t) {
			System.err.println("cadence: QoS for " + role + ": " + t);
		}
	}
}
