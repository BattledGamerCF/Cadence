// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.backend;

import com.mojang.text2speech.Narrator;

/** The game's narrator, created by {@link Narrator#getNarrator()} on first use (same object, same calls, later). */
public final class LazyNarrator implements Narrator {
	private Narrator real;

	private synchronized Narrator real() {
		if (real == null) real = Narrator.getNarrator();
		return real;
	}

	@Override public void say(String msg, boolean interrupt, float volume) { real().say(msg, interrupt, volume); }
	@Override public void clear() { real().clear(); }
	@Override public boolean active() { return real().active(); }
	@Override public synchronized void destroy() { if (real != null) real.destroy(); }
}
