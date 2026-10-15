// Adapted from minecraft-metal-renderer (MIT, Copyright (c) 2026 Minecraft Metal Renderer contributors),
// https://github.com/Kausik-Velaga/minecraft-metal-renderer — see shaders/LICENSE-MIT-shader-loader.
package dev.cadence.metal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Fail-closed arithmetic policy inspection; SPIR-V validation remains the compiler's job. */
final class SpirvMathPolicy {
  private SpirvMathPolicy() {}

  static boolean permitsRelaxedFragment(byte[] source) {
    if (source.length < 20 || source.length % 4 != 0) return false;
    var words = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
    if (words.get(0) != 0x07230203 || words.get(4) != 0) return false;
    // A future IR revision can add numerical controls not recognized by this inspector.
    int version = words.get(1);
    if (version < 0x00010000 || version > 0x00010600) return false;
    for (int p = 5; p < words.limit(); ) {
      int instruction = words.get(p), size = instruction >>> 16, op = instruction & 0xffff;
      if (size == 0 || size > words.limit() - p) return false;
      if (op == 16) { // OpExecutionMode: permit only understood graphics modes.
        if (size < 3) return false;
        int mode = words.get(p + 2);
        if (mode != 7
            && mode != 8
            && mode != 9
            && mode != 12
            && mode != 14
            && mode != 15
            && mode != 16) return false;
      } else if (op == 331 || op == 332 || op == 5632 || op == 5633) {
        // ExecutionModeId, DecorateId and string decorations may carry extension controls.
        return false;
      } else if (op == 71 || op == 72) { // OpDecorate / OpMemberDecorate, including groups.
        int index = op == 71 ? 2 : 3;
        if (size <= index) return false;
        int decoration = words.get(p + index);
        if (decoration == 18
            || decoration == 39
            || decoration == 40
            || decoration == 42
            || decoration >= 4096) return false;
      } else if (op == 17) { // Unknown extension capabilities can change numerical semantics.
        if (size != 2) return false;
        int capability = words.get(p + 1);
        // DemoteToHelperInvocation changes fragment coverage, not arithmetic precision.
        if (capability >= 4096 && capability != 5379) return false;
      }
      p += size;
    }
    return true;
  }
}
