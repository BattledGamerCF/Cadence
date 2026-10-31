// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import com.mojang.renderpearl.api.textures.GpuSampler;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import dev.cadence.metal.backend.FrameCapture;
import dev.cadence.metal.backend.MetalBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The own renderer's side of the frame capture (dev.cadence.metal.backend.FrameCapture, -Dcadence.own.capture; off by default: every hook in
 * OwnNative and OwnTerrain is a constant-false branch). Logs every OwnNative call that encodes GPU work or sets its state, with its
 * frame blobs and the uniform blocks each draw binds. In terrain mode (-Dcadence.own.capture.mode=terrain) it also keeps the level
 * pass's encoder serials around each call and, in image frames, splits the pass to copy its attachments before and after our opaque
 * block and our translucent draw.
 */
final class OwnCapture {
	static final boolean ON = FrameCapture.ON;
	private static final boolean TERRAIN = ON && !FrameCapture.FULL;

	/** Terrain mode: CPU-written shared buffers already snapshot in this segment (0 opaque, 1 translucent) of this frame. */
	private static final Set<Long> snapped = new HashSet<>();
	private static int segment;
	private static long lastSerialFull = -1;

	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle ENC_STATE = ON ? fn("mcc_enc_state", null, JAVA_LONG, JAVA_LONG) : null;
	private static final MethodHandle BYTES = ON ? fn("mcc_bytes", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG) : null;
	private static final MethodHandle SPLIT_COPY = ON ? fn("mcc_split_copy", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG) : null;

	private OwnCapture() {
	}

	private static MethodHandle fn(String name, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		return LINKER.downcallHandle(MetalBridge.library().find(name).orElseThrow(), d);
	}

	private static RuntimeException rethrow(Throwable t) {
		return t instanceof RuntimeException r ? r : new IllegalStateException(t);
	}

	/** Our terrain's frame begins (its cull): in terrain mode, the capture's frame boundary. */
	static void frameStart() {
		if (!TERRAIN) return;
		FrameCapture.frameStart();
		snapped.clear();
		segment = 0;
	}

	static boolean active() {
		return FrameCapture.active();
	}

	private static void line(String s) {
		FrameCapture.line(s);
	}

	private static String hex(long addr, int len) {
		return FrameCapture.hex(addr, len);
	}

	private static String buf(long h) {
		return FrameCapture.buf(h, snapped, segment);
	}

	private static String tex(long h) {
		return FrameCapture.tex(h);
	}

	/** Terrain mode: the level pass's state {open, serial}, with a record of its attachments when the serial is new. */
	private static String enc(long enc) {
		if (!TERRAIN) return "[0,0]";
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long o = stack.nmalloc(8, 24 * 8);
			ENC_STATE.invokeExact(enc, o);
			long open = MemoryUtil.memGetLong(o), serial = MemoryUtil.memGetLong(o + 8);
			if (open != 0 && serial != lastSerialFull) {
				lastSerialFull = serial;
				int colors = (int) MemoryUtil.memGetLong(o + 16);
				StringBuilder b = new StringBuilder("{\"t\":\"pass\",\"serial\":" + serial + ",\"w\":" + MemoryUtil.memGetLong(o + 24) + ",\"hgt\":"
					+ MemoryUtil.memGetLong(o + 32) + ",\"depth\":" + tex(MemoryUtil.memGetLong(o + 40)) + ",\"colors\":[");
				for (int i = 0; i < colors; i++) b.append(i > 0 ? "," : "").append(tex(MemoryUtil.memGetLong(o + (7 + 2 * i) * 8L)));
				line(b.append("]}").toString());
			}
			return "[" + open + "," + serial + "]";
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static String uniforms(long u) {
		// 4 (buffer, offset) pairs; 256 bytes of each (the structs are 16-80 bytes)
		StringBuilder b = new StringBuilder("[");
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long tmp = stack.nmalloc(16, 256);
			for (int i = 0; i < 4; i++) {
				long h = MemoryUtil.memGetLong(u + i * 16L), off = MemoryUtil.memGetLong(u + i * 16L + 8);
				if (FrameCapture.FULL) buf(h);  // (frame mode: the buffer itself is replayed as captured)
				int n = (int) BYTES.invokeExact(h, off, 256, tmp);
				b.append(i > 0 ? "," : "").append(n < 0 ? "null" : hex(tmp, n));
			}
		} catch (Throwable t) {
			throw rethrow(t);
		}
		return b.append(']').toString();
	}

	private static String uniformRefs(long u) {
		StringBuilder b = new StringBuilder("[");
		for (int i = 0; i < 4; i++)
			b.append(i > 0 ? "," : "").append(MemoryUtil.memGetLong(u + i * 16L)).append(',').append(MemoryUtil.memGetLong(u + i * 16L + 8));
		return b.append(']').toString();
	}

	// ---- the calls (OwnNative): pre is taken before the native call, the rest after it ----

	static String pre(long enc) {
		return enc(enc);
	}

	static void fragCull(String e0, long enc, long frame, int frameLength, long fragFrame, int fragLength, long sections, long recs, long args, long lists,
		long mask, int sectionCount, int listCount, int lanes, long vArgs, long vLists, long sig, int listCap) {
		FrameCapture.completedOwnBuffer("A.args", args);
		FrameCapture.completedOwnBuffer("A.lists", lists);
		line("{\"t\":\"call\",\"fn\":\"fragCull\",\"e0\":" + e0 + ",\"e1\":" + enc(enc) + ",\"frame\":" + hex(frame, frameLength) + ",\"frag\":" + hex(fragFrame, fragLength)
			+ ",\"sections\":" + buf(sections) + ",\"recs\":" + buf(recs) + ",\"args\":" + buf(args) + ",\"lists\":" + buf(lists) + ",\"mask\":" + buf(mask)
			+ ",\"sectionCount\":" + sectionCount + ",\"listCount\":" + listCount + ",\"lanes\":" + lanes + ",\"vArgs\":" + buf(vArgs) + ",\"vLists\":" + buf(vLists)
			+ ",\"sig\":" + buf(sig) + ",\"listCap\":" + listCap + "}");
	}

	static void preCommit(String e0, long enc) {
		line("{\"t\":\"call\",\"fn\":\"preCommit\",\"e0\":" + e0 + ",\"e1\":" + enc(enc) + "}");
	}

	static void fragUoccBuffers(long occVis, long tList, long bArgs, long listsB) {
		FrameCapture.completedOwnBuffer("B.args", bArgs);
		FrameCapture.completedOwnBuffer("B.lists", listsB);
		FrameCapture.completedOwnBuffer("tested", tList);
		FrameCapture.completedOwnBuffer("visibility", occVis);
		line("{\"t\":\"call\",\"fn\":\"fragUoccBuffers\",\"occVis\":" + buf(occVis) + ",\"tList\":" + buf(tList) + ",\"bArgs\":" + buf(bArgs) + ",\"listsB\":" + buf(listsB) + "}");
	}

	static void fragMasks(long masks, long stats, long statsOffset, boolean on) {
		line("{\"t\":\"call\",\"fn\":\"fragMasks\",\"masks\":" + buf(masks) + ",\"stats\":" + buf(stats) + ",\"statsOffset\":" + statsOffset + ",\"on\":" + on + "}");
	}

	static void fragUocc(String e0, long enc, long cullFrame, int cullLength, long fragFrame, int fragLength, long clip, long sections, long recs, long args, int lists,
		int fine, int result) {
		line("{\"t\":\"call\",\"fn\":\"fragUocc\",\"e0\":" + e0 + ",\"e1\":" + enc(enc) + ",\"cull\":" + hex(cullFrame, cullLength) + ",\"frag\":" + hex(fragFrame, fragLength)
			+ ",\"clip\":" + hex(clip, 64) + ",\"sections\":" + buf(sections) + ",\"recs\":" + buf(recs) + ",\"args\":" + buf(args) + ",\"lists\":" + lists + ",\"fine\":"
			+ fine + ",\"r\":" + result + "}");
	}

	static void draw(String e0, long enc, int kind, long frame, int frameLength, long arena, long sections, long recs, long lists, long args, long argsOffset, long u,
		long atlas, long atlasSampler, long light, long lightSampler, long indices, int result) {
		line("{\"t\":\"call\",\"fn\":\"draw\",\"e0\":" + e0 + ",\"e1\":" + enc(enc) + ",\"kind\":" + kind + ",\"frame\":" + hex(frame, frameLength) + ",\"arena\":"
			+ buf(arena) + ",\"sections\":" + buf(sections) + ",\"recs\":" + buf(recs) + ",\"lists\":" + buf(lists) + ",\"args\":" + buf(args) + ",\"argsOffset\":"
			+ argsOffset + ",\"u\":" + uniforms(u) + ",\"uref\":" + uniformRefs(u) + ",\"atlas\":" + tex(atlas) + ",\"atlasSampler\":" + FrameCapture.sampler(atlasSampler)
			+ ",\"light\":" + tex(light) + ",\"lightSampler\":" + FrameCapture.sampler(lightSampler) + ",\"indices\":" + buf(indices) + ",\"r\":" + result + "}");
	}

	static void split(String e0, long enc, int result) {
		line("{\"t\":\"call\",\"fn\":\"split\",\"e0\":" + e0 + ",\"e1\":" + enc(enc) + ",\"r\":" + result + "}");
	}

	// (state calls: no GPU work of their own, but the next cull / draws read what they set; logged where they happen in the frame)

	/** -Dcadence.own.frag.tieClose: the tie rings the cull reads (buffer 30); a shared, CPU-written buffer, snapshotted like the others. */
	static void fragTieRing(long ring) {
		line("{\"t\":\"call\",\"fn\":\"fragTieRing\",\"ring\":" + buf(ring) + "}");
	}

	static void fragTieVerify(long lists, boolean on) {
		line("{\"t\":\"call\",\"fn\":\"fragTieVerify\",\"lists\":" + buf(lists) + ",\"on\":" + on + "}");
	}

	/** A buffer the next cull zeroes first (each new occVis). */
	static void fragClear(long buffer) {
		line("{\"t\":\"call\",\"fn\":\"fragClear\",\"buf\":" + buf(buffer) + "}");
	}

	/** a1Exact / a2Exact: the lists with a table. */
	static void fragExactMask(int lo, int hi) {
		line("{\"t\":\"call\",\"fn\":\"fragExactMask\",\"lo\":" + lo + ",\"hi\":" + hi + "}");
	}

	/** a1Exact / a2Exact: mode, lists, entries a list, this frame's set. */
	static void fragA1Exact(int mode, int lists, int listCap, int set) {
		line("{\"t\":\"call\",\"fn\":\"fragA1Exact\",\"mode\":" + mode + ",\"lists\":" + lists + ",\"listCap\":" + listCap + ",\"set\":" + set + "}");
	}

	/**
	 * Texture animation's copies into the atlas mips (int.animOnePass / animCopy: mcown.m mco_anim_blit_mips / _copy_mips / _copy), in the
	 * frame's command buffer: what (blitMips, copyMips, copy), the textures, the rectangles (words uints), mip and the dispatch size.
	 */
	static void anim(String e0, long enc, String what, long src, long dst, int mip, long rects, int words, int count, int maxW, int maxH, int result) {
		line("{\"t\":\"call\",\"fn\":\"anim\",\"what\":\"" + what + "\",\"e0\":" + e0 + ",\"e1\":" + enc(enc) + ",\"src\":" + tex(src) + ",\"dst\":" + tex(dst)
			+ ",\"mip\":" + mip + ",\"rects\":" + hex(rects, 4 * Math.max(0, words)) + ",\"count\":" + count + ",\"maxW\":" + maxW + ",\"maxH\":" + maxH + ",\"r\":" + result + "}");
	}

	/** A call the replay doesn't support (the capture is marked unusable for it). */
	static void unsupported(String fn) {
		if (active()) line("{\"t\":\"call\",\"fn\":\"unsupported\",\"what\":" + FrameCapture.str(fn) + "}");
	}

	/** The samplers our draws bind (MetalSampler's mc_sampler_new arguments; frame mode records every sampler as it is made). */
	static void sampler(GpuSampler s) {
		if (active()) FrameCapture.samplerParams(MetalBridge.samplerHandle(s), MetalBridge.samplerParams(s));
	}

	/**
	 * Terrain mode. which: preO / postO (around our opaque block), preT / postT (around our translucent draw). In an image frame the level
	 * pass is split and its attachments, the atlas and the lightmap copied out (true: the caller restores the pass's state). Always
	 * marks the point in the log (segment boundaries for the replay).
	 */
	static boolean image(long enc, String which, long atlas, long light) {
		if (!TERRAIN || !active()) return false;
		if (which.equals("preT")) {
			segment = 1;
			snapped.clear();
		}
		if (!FrameCapture.imageFrame()) {
			line("{\"t\":\"img\",\"w\":\"" + which + "\",\"e1\":" + enc(enc) + "}");
			return false;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long o = stack.nmalloc(8, 24 * 8);
			ENC_STATE.invokeExact(enc, o);
			if (MemoryUtil.memGetLong(o) == 0) {
				line("{\"t\":\"img\",\"w\":\"" + which + "\",\"e1\":" + enc(enc) + ",\"none\":true}");
				return false;
			}
			int colors = (int) MemoryUtil.memGetLong(o + 16);
			List<Long> texs = new ArrayList<>();
			List<String> names = new ArrayList<>();
			for (int i = 0; i < colors; i++) {
				long c = MemoryUtil.memGetLong(o + (7 + 2 * i) * 8L);
				if (c != 0) {
					texs.add(c);
					names.add("color" + i);
				}
			}
			long depth = MemoryUtil.memGetLong(o + 40);
			if (depth != 0) {
				texs.add(depth);
				names.add("depth");
			}
			if (which.startsWith("pre")) {
				if (atlas != 0) {
					texs.add(atlas);
					names.add("atlas");
				}
				if (light != 0) {
					texs.add(light);
					names.add("light");
				}
			}
			long t = stack.nmalloc(8, 8 * texs.size()), p = stack.nmalloc(8, 8 * texs.size());
			StringBuilder files = new StringBuilder("{");
			for (int i = 0; i < texs.size(); i++) {
				String file = "f" + FrameCapture.frameIndex() + "-" + which + "-" + names.get(i) + ".tex";
				MemoryUtil.memPutLong(t + 8L * i, texs.get(i));
				MemoryUtil.memPutLong(p + 8L * i, MemoryUtil.memAddress(stack.UTF8(FrameCapture.burstDir().resolve(file).toString())));
				files.append(i > 0 ? "," : "").append(FrameCapture.str(names.get(i))).append(":[").append(FrameCapture.str(file)).append(',').append(texs.get(i)).append(']');
				tex(texs.get(i));
			}
			int r = (int) SPLIT_COPY.invokeExact(enc, texs.size(), t, p);
			line("{\"t\":\"img\",\"w\":\"" + which + "\",\"cap\":true,\"r\":" + r + ",\"e1\":" + enc(enc) + ",\"files\":" + files + "}}");
			return true;
		} catch (Throwable e) {
			throw rethrow(e);
		}
	}
}
