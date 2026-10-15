// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Predicate;

/** Bounded, coalesced publication notifications; workers never mutate the selected membership. */
public final class EventSectionMembership<T> {
  private final List<T> sections;
  private final IdentityHashMap<T, Integer> ordinals = new IdentityHashMap<>();
  private final AtomicLongArray changed;
  private final BitSet members = new BitSet();

  public EventSectionMembership(List<T> sections) {
    this.sections = List.copyOf(sections);
    changed = new AtomicLongArray((sections.size() + 63) >>> 6);
    for (int i = 0; i < sections.size(); i++) ordinals.put(sections.get(i), i);
  }

  public int ordinal(T section) {
    return ordinals.getOrDefault(section, -1);
  }

  /** Install the publication listener before this one-time initial scan. */
  public void initialize(Predicate<T> predicate) {
    for (int i = 0; i < sections.size(); i++) members.set(i, predicate.test(sections.get(i)));
  }

  public void changed(T section) {
    int ordinal = ordinal(section);
    if (ordinal < 0) return;
    long bit = 1L << (ordinal & 63);
    changed.getAndUpdate(ordinal >>> 6, previous -> previous | bit);
  }

  public BitSet snapshot(Predicate<T> predicate) {
    for (int word = 0; word < changed.length(); word++) {
      // Clear before reading live state: any later publication leaves another notification.
      long bits = changed.getAndSet(word, 0);
      while (bits != 0) {
        int ordinal = (word << 6) + Long.numberOfTrailingZeros(bits);
        members.set(ordinal, predicate.test(sections.get(ordinal)));
        bits &= bits - 1;
      }
    }
    return (BitSet) members.clone();
  }
}
