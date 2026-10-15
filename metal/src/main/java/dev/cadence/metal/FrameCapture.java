// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Frame capture for tools/replay (-Dcadence.own.capture=DIR; off by default: unset, every hook in Native and OwnNative is a
 * constant-false branch, the GPU work is unchanged, and mcmetal.m / mcown.m are not touched by any of this).
 * <ul>
 * <li>mode=frame (-Dcadence.own.capture.mode, the default): every backend call (Native) and every call of the own renderer (OwnNative)
 * of a frame, in order, with its arguments; the libraries' sources, pipelines, depth states and samplers they use (recorded as
 * they are made, from boot); each CPU-written shared buffer as deduplicated 64 KiB pages at every commit; after a burst's first
 * frame (a discovery frame, not replayed) the contents of every texture and private buffer it used, which is what the second frame
 * starts from; the presented image of the image frames. tools/replay replays frames 1.. through the same mcmetal.m / mcown.m calls.</li>
 * <li>mode=terrain: our terrain's calls only (OwnCapture), with the level pass split around them in image frames.</li>
 * </ul>
 * Bursts: -Dcadence.own.capture.at (comma list of PHASE:SECONDS: hold:S after the bench's settle, spin:S, move:S, flight:S into that
 * bench phase, frame:N for the N-th frame) of -Dcadence.own.capture.frames consecutive frames each (24 by default, a multiple of 6:
 * the own renderer's 3 frame slots and 2 pipe sets line up when the replay loops). With -Dcadence.bench.maxStepMs the bench's clock
 * advances at most that much a frame while a burst is written (cadence.own.capture.active), so capture frames keep the camera's
 * per-frame motion.
 */
public final class FrameCapture {
	public static final String DIR = System.getProperty("cadence.own.capture");
	public static final boolean ON = DIR != null;
	/** Whole frames (the backend's calls too), or our terrain's calls only. */
	public static final boolean FULL = ON && !"terrain".equals(System.getProperty("cadence.own.capture.mode", "frame"));
	private static final String COMPLETED = System.getProperty("cadence.own.capture.completed", "off");
	private static final Map<String, Long> completedOwn = new LinkedHashMap<>();
	private static final int FRAMES = Integer.getInteger("cadence.own.capture.frames", 24);
	private static final String IMAGES = System.getProperty("cadence.own.capture.images", "ends");  // ends | all | none
	private static final List<String[]> TRIGGERS = new ArrayList<>();
	static {
		if (ON) for (String t : System.getProperty("cadence.own.capture.at", "hold:5").split(",")) if (!t.isBlank()) TRIGGERS.add(t.trim().split(":"));
	}

	// the registries (from boot, while ON): what made each library, pipeline, depth state and sampler
	private static final Map<Long, String> LIBS = new HashMap<>();  // by library id (LIB_IDS): handles are reused after a resource reload
	private static final Map<Long, Long> LIB_IDS = new HashMap<>();
	private static long libSerial;
	private static final Map<Long, String> PSOS = new HashMap<>();  // the record's JSON body
	private static final Map<Long, String> DEPTHS = new HashMap<>();
	private static final Map<Long, String> SAMPLERS = new HashMap<>();

	private static boolean active, images, storeOpen;
	private static int frameIndex, frames, nextTrigger;
	private static String burstName = "";
	private static Path burstDir;
	private static BufferedWriter log;
	private static OutputStream snaps;
	private static long snapsAt;
	private static final Map<Long, Long> bufLen = new HashMap<>();
	private static final Set<Long> texSeen = new HashSet<>(), objSeen = new HashSet<>();
	/** FULL: the buffers and textures this frame referenced (shared buffers are snapshot at its commit). */
	private static final Set<Long> frameBufs = new LinkedHashSet<>(), frameTexs = new LinkedHashSet<>();
	/** FULL: every texture / private buffer whose contents were captured this burst. */
	private static final Set<Long> texCaptured = new HashSet<>(), bufCaptured = new HashSet<>();
	private static long presentTex, lastPresentTex;

	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle BUFFER_INFO = ON ? fn("mcc_buffer_info", null, JAVA_LONG, JAVA_LONG) : null;
	private static final MethodHandle TEXTURE_INFO = ON ? fn("mcc_texture_info", null, JAVA_LONG, JAVA_LONG) : null;
	private static final MethodHandle STORE_OPEN = ON ? fn("mcc_store_open", JAVA_INT, JAVA_LONG) : null;
	private static final MethodHandle STORE_CLOSE = ON ? fn("mcc_store_close", null) : null;
	private static final MethodHandle SNAP = ON ? fn("mcc_snap", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG) : null;
	private static final MethodHandle COPY_BUFFERS_COMPLETED = ON && !COMPLETED.equals("off")
		? fn("mcc_copy_buffers_completed", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG) : null;
	private static final MethodHandle COPY_OUT = ON ? fn("mcc_copy_out", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG) : null;

	private FrameCapture() {
	}

	private static MethodHandle fn(String name, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		return LINKER.downcallHandle(Native.lookup().find(name).orElseThrow(), d);
	}

	private static RuntimeException rethrow(Throwable t) {
		return t instanceof RuntimeException r ? r : new IllegalStateException(t);
	}

	// ---- bursts ----

	/** Seconds into the trigger's phase now, or -1 (not in it): the bench's state (mcopt.bench.Bench) by reflection, on its clock. */
	private static double phaseSeconds(String phase) {
		if (phase.equals("frame")) return frames;
		try {
			Class<?> b = Class.forName("mcopt.bench.Bench", false, FrameCapture.class.getClassLoader());
			var pf = b.getDeclaredField("phase");
			pf.setAccessible(true);
			String now = String.valueOf(pf.get(null));
			var cv = b.getDeclaredField("clockVirtual");
			cv.setAccessible(true);
			long t = cv.getLong(null);
			if (t == 0) t = System.nanoTime();
			if (phase.equals("hold")) {
				if (!now.equals("SETTLE")) return -1;
				var sf = b.getDeclaredField("settledAt");
				sf.setAccessible(true);
				long s = sf.getLong(null);
				return s == 0 ? -1 : (t - s) / 1e9;
			}
			if (!now.equalsIgnoreCase(phase)) return -1;
			var st = b.getDeclaredField("phaseStart");
			st.setAccessible(true);
			return (t - st.getLong(null)) / 1e9;
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return -1;
		}
	}

	/** A frame begins (terrain mode: our cull; frame mode: after the last commit). Ends a finished burst, starts a due one. */
	public static void frameStart() {
		frames++;
		if (active && frameIndex >= FRAMES + (FULL ? 1 : 0)) endBurst();
		if (!active && nextTrigger < TRIGGERS.size()) {
			String[] t = TRIGGERS.get(nextTrigger);
			if (phaseSeconds(t[0]) >= Double.parseDouble(t[1])) {
				nextTrigger++;
				startBurst((t.length > 2 ? t[2] : t[0]) + "-" + t[1]);  // (PHASE:S:NAME names the burst NAME-S: e.g. extra water points in flight)
			}
		}
		if (!active) return;
		frameBufs.clear();
		frameTexs.clear();
		completedOwn.clear();
		presentTex = 0;
		images = IMAGES.equals("all") || IMAGES.equals("ends") && (frameIndex == (FULL ? 1 : 0) || frameIndex == FRAMES - (FULL ? 0 : 1));
		line("{\"t\":\"frame\",\"n\":" + frameIndex + ",\"images\":" + images + ",\"nanos\":" + System.nanoTime() + "}");
		frameIndex++;
	}

	private static void startBurst(String name) {
		try {
			burstName = name;
			burstDir = Path.of(DIR, name);
			Files.createDirectories(burstDir);
			log = Files.newBufferedWriter(burstDir.resolve("log.jsonl"), StandardCharsets.UTF_8);
			snaps = new java.io.BufferedOutputStream(new FileOutputStream(burstDir.resolve("snaps.bin").toFile()));
			snapsAt = 0;
			if (!storeOpen) {
				// one page store for every burst of the run (DIR/pages.bin): the arena is mostly the same pages from burst to burst
				try (MemoryStack stack = MemoryStack.stackPush()) {
					long path = MemoryUtil.memAddress(stack.UTF8(Path.of(DIR, "pages.bin").toString()));
					if ((int) STORE_OPEN.invokeExact(path) != 0) throw new IOException("pages.bin");
				}
				storeOpen = true;
			}
			bufLen.clear();
			texSeen.clear();
			objSeen.clear();
			libsSeen.clear();
			texCaptured.clear();
			bufCaptured.clear();
			frameIndex = 0;
			active = true;
			System.setProperty("cadence.own.capture.active", "1");
			StringBuilder p = new StringBuilder();
			for (String k : System.getProperties().stringPropertyNames()) {
				if (!k.startsWith("cadence.")) continue;
				if (p.length() > 0) p.append(',');
				p.append(str(k)).append(':').append(str(System.getProperty(k)));
			}
			line("{\"t\":\"init\",\"burst\":" + str(name) + ",\"mode\":\"" + (FULL ? "frame" : "terrain") + "\",\"frames\":" + FRAMES + ",\"compact\":" + Own.compact
				+ ",\"fat\":" + Own.fat + ",\"cpuClip\":" + Own.cpuClip + ",\"exactPos\":" + Own.exactPos + ",\"prefix\":" + str(Own.prefix) + ",\"build\":" + str(System.getProperty("cadence.own.capture.build", "unknown")) + ",\"hiz\":" + Own.hizBits + ",\"props\":{" + p + "}}");
			System.out.println("cadence-own capture: burst " + name + " (" + (FULL ? "whole frames" : "our terrain") + ") -> " + burstDir);
		} catch (Throwable t) {
			System.out.println("cadence-own capture: can't start " + name + ": " + t);
			active = false;
			System.clearProperty("cadence.own.capture.active");
		}
	}

	private static void endBurst() {
		active = false;
		System.clearProperty("cadence.own.capture.active");
		try {
			line("{\"t\":\"end\"}");
			log.close();
			snaps.close();
			if (nextTrigger >= TRIGGERS.size()) {
				STORE_CLOSE.invokeExact();
				storeOpen = false;
			}
			Files.writeString(burstDir.resolve("done"), "frames " + frameIndex + "\n");
			System.out.println("cadence-own capture: burst " + burstName + " done (" + frameIndex + " frames)");
		} catch (Throwable t) {
			System.out.println("cadence-own capture: can't close " + burstName + ": " + t);
		}
	}

	public static boolean active() {
		return active;
	}

	public static boolean imageFrame() {
		return active && images;
	}

	public static Path burstDir() {
		return burstDir;
	}

	public static int frameIndex() {
		return frameIndex - 1;
	}

	// ---- the log ----

	public static void line(String s) {
		try {
			log.write(s);
			log.write('\n');
		} catch (IOException e) {
			active = false;
		}
	}

	public static String str(String s) {
		StringBuilder b = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			if (c == '"' || c == '\\') b.append('\\').append(c);
			else if (c < 32) b.append(String.format("\\u%04x", (int) c));
			else b.append(c);
		}
		return b.append('"').toString();
	}

	public static String hex(long addr, long len) {
		StringBuilder b = new StringBuilder((int) len * 2 + 2).append('"');
		for (long i = 0; i < len; i++) {
			int v = MemoryUtil.memGetByte(addr + i) & 255;
			b.append(Character.forDigit(v >> 4, 16)).append(Character.forDigit(v & 15, 16));
		}
		return b.append('"').toString();
	}

	private static String ints(long addr, int n) {
		StringBuilder b = new StringBuilder("[");
		for (int i = 0; i < n; i++) b.append(i > 0 ? "," : "").append(MemoryUtil.memGetInt(addr + 4L * i));
		return b.append(']').toString();
	}

	private static String longs(long addr, int n) {
		StringBuilder b = new StringBuilder("[");
		for (int i = 0; i < n; i++) b.append(i > 0 ? "," : "").append(MemoryUtil.memGetLong(addr + 8L * i));
		return b.append(']').toString();
	}

	/**
	 * A buffer argument: its properties once a burst (or when its length changed). Terrain mode: a CPU-written shared one (kind 1:
	 * shared, untracked) is snapshot once a segment (snapped). Frame mode: every shared one at the frame's commit.
	 */
	public static String buf(long h, Set<Long> snapped, int segment) {
		if (h == 0) return "0";
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long o = stack.nmalloc(8, 32);
			BUFFER_INFO.invokeExact(h, o);
			long len = MemoryUtil.memGetLong(o), sm = MemoryUtil.memGetLong(o + 8), hz = MemoryUtil.memGetLong(o + 16);
			Long was = bufLen.get(h);
			if (was == null || was != len) {
				bufLen.put(h, len);
				line("{\"t\":\"buf\",\"h\":" + h + ",\"len\":" + len + ",\"sm\":" + sm + ",\"hz\":" + hz + "}");
			}
			if (FULL) frameBufs.add(h);
			else if (snapped != null && sm == 0 && hz == 1 && snapped.add(h)) snap(h, len, segment);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		return Long.toString(h);
	}

	private static void snap(long h, long len, int segment) throws Throwable {
		int pages = (int) ((len + 65535) / 65536);
		long idx = MemoryUtil.nmemAlloc(4L * pages);
		try {
			long n = (long) SNAP.invokeExact(h, idx, (long) pages);
			if (n == pages) {
				byte[] bytes = new byte[pages * 4];
				MemoryUtil.memByteBuffer(idx, pages * 4).get(bytes);
				snaps.write(bytes);
				line("{\"t\":\"snap\",\"h\":" + h + ",\"at\":" + snapsAt + ",\"n\":" + pages + ",\"seg\":" + segment + "}");
				snapsAt += bytes.length;
			} else {
				line("{\"t\":\"error\",\"what\":\"snap " + h + "\"}");
			}
		} finally {
			MemoryUtil.nmemFree(idx);
		}
	}

	/** A texture argument: its descriptor (and its parent's, for a view) once a burst. */
	public static String tex(long h) {
		if (h == 0) return "0";
		if (FULL) frameTexs.add(h);
		if (texSeen.add(h)) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long o = stack.nmalloc(8, 19 * 8);
				TEXTURE_INFO.invokeExact(h, o);
				long parent = MemoryUtil.memGetLong(o + 15 * 8), buffer = MemoryUtil.memGetLong(o + 16 * 8);
				if (parent != 0 && FULL) tex(parent);
				if (buffer != 0) buf(buffer, null, 0);  // (a texture over a buffer: the buffer is its contents)
				StringBuilder b = new StringBuilder("{\"t\":\"tex\",\"h\":" + h + ",\"i\":[");
				for (int i = 0; i < 19; i++) b.append(i > 0 ? "," : "").append(MemoryUtil.memGetLong(o + i * 8L));
				line(b.append("]}").toString());
			} catch (Throwable t) {
				throw rethrow(t);
			}
		}
		return Long.toString(h);
	}

	private static String obj(Map<Long, String> registry, String kind, long h) {
		if (h == 0) return "0";
		if (objSeen.add(h)) {
			String body = registry.get(h);
			if (body == null) line("{\"t\":\"error\",\"what\":\"no record of " + kind + " " + h + " (made before the capture started?)\"}");
			else line("{\"t\":\"" + kind + "\",\"h\":" + h + "," + body + "}");
		}
		return Long.toString(h);
	}

	// ---- the registries (Native: as the objects are made) ----

	static void library(long h, long source) {
		if (h == 0) return;
		long id = ++libSerial;
		LIB_IDS.put(h, id);
		LIBS.put(id, MemoryUtil.memUTF8(source));
	}

	static void pipeline(long h, long vlib, long vname, long flib, long fname, long desc) {
		if (h == 0) return;
		// desc's length from its layout (mcmetal.m mc_pipeline_new)
		int i = 0;
		int buffers = MemoryUtil.memGetInt(desc);
		i = 1 + buffers * 3;
		int attributes = MemoryUtil.memGetInt(desc + 4L * i);
		i += 1 + attributes * 4;
		int colors = MemoryUtil.memGetInt(desc + 4L * i);
		i += 1 + colors * 9 + 2;
		// (the libraries by id: the ones this pipeline was made from, even if their handles are reused later)
		PSOS.put(h, "\"vlib\":" + LIB_IDS.getOrDefault(vlib, 0L) + ",\"vname\":" + str(MemoryUtil.memUTF8(vname)) + ",\"flib\":" + LIB_IDS.getOrDefault(flib, 0L)
			+ ",\"fname\":" + str(MemoryUtil.memUTF8(fname))
			+ ",\"desc\":" + ints(desc, i));
	}

	static void depthState(long h, int compare, int write) {
		if (h != 0) DEPTHS.put(h, "\"compare\":" + compare + ",\"write\":" + write);
	}

	static void sampler(long h, int u, int v, int min, int mag, int mip, int aniso, float maxLod) {
		if (h != 0) SAMPLERS.put(h, "\"p\":[" + u + "," + v + "," + min + "," + mag + "," + mip + "," + aniso + "," + maxLod + "]");
	}

	/** A sampler made outside Native.samplerNew (OwnCapture's terrain mode reads MetalSampler's parameters). */
	public static void samplerParams(long h, float[] p) {
		if (h != 0 && !SAMPLERS.containsKey(h)) sampler(h, (int) p[0], (int) p[1], (int) p[2], (int) p[3], (int) p[4], (int) p[5], p[6]);
	}

	public static String sampler(long h) {
		return obj(SAMPLERS, "smp", h);
	}

	// ---- frame mode: the backend's calls (Native) ----

	private static void call(String body) {
		line("{\"t\":\"mc\"," + body + "}");
	}

	static void renderBegin(long enc, int count, long colors, long clears, long depth, int clearDepth, float depthValue, int w, int h) {
		StringBuilder c = new StringBuilder("[");
		for (int i = 0; i < count; i++) c.append(i > 0 ? "," : "").append(tex(MemoryUtil.memGetLong(colors + 8L * i)));
		StringBuilder f = new StringBuilder("[");
		for (int i = 0; i < count * 5; i++) f.append(i > 0 ? "," : "").append(MemoryUtil.memGetFloat(clears + 4L * i));
		call("\"c\":\"begin\",\"colors\":" + c + "],\"clears\":" + f + "],\"depth\":" + tex(depth) + ",\"clearDepth\":" + clearDepth + ",\"depthValue\":" + depthValue
			+ ",\"w\":" + w + ",\"h\":" + h);
	}

	static void discard(long texture) {
		call("\"c\":\"discard\",\"tex\":" + tex(texture));
	}

	static void pipeline(long pso, long depth, int cull, int wireframe, float biasConstant, float biasSlope, int primitive) {
		if (pso != 0 && !objSeen.contains(pso)) {
			// the pipeline's libraries first
			String body = PSOS.get(pso);
			if (body != null) {
				for (String k : new String[] {"\"vlib\":", "\"flib\":"}) {
					int at = body.indexOf(k) + k.length();
					long id = Long.parseLong(body.substring(at, body.indexOf(',', at)));
					if (libsSeen.add(id)) {
						String src = LIBS.get(id);
						if (src == null) line("{\"t\":\"error\",\"what\":\"no record of library " + id + "\"}");
						else line("{\"t\":\"lib\",\"h\":" + id + ",\"src\":" + str(src) + "}");
					}
				}
			}
		}
		call("\"c\":\"pipe\",\"pso\":" + obj(PSOS, "pso", pso) + ",\"ds\":" + obj(DEPTHS, "ds", depth) + ",\"cull\":" + cull + ",\"wire\":" + wireframe + ",\"bc\":"
			+ biasConstant + ",\"bs\":" + biasSlope + ",\"prim\":" + primitive);
	}

	private static final Set<Long> libsSeen = new HashSet<>();

	static void buffer(boolean vertexOnly, int index, long buffer, long offset) {
		call("\"c\":\"" + (vertexOnly ? "vbuf" : "buf") + "\",\"i\":" + index + ",\"b\":" + buf(buffer, null, 0) + ",\"o\":" + offset);
	}

	static void bytes(int index, long bytes, int length) {
		call("\"c\":\"bytes\",\"i\":" + index + ",\"d\":" + hex(bytes, length));
	}

	static void texture(int index, long texture, long sampler) {
		call("\"c\":\"tex\",\"i\":" + index + ",\"tx\":" + tex(texture) + ",\"s\":" + sampler(sampler));
	}

	static void scissor(int x, int y, int w, int h) {
		call("\"c\":\"scissor\",\"r\":[" + x + "," + y + "," + w + "," + h + "]");
	}

	static void index(long buffer, int intIndices) {
		call("\"c\":\"index\",\"b\":" + buf(buffer, null, 0) + ",\"int\":" + intIndices);
	}

	static void use(long buffer) {
		call("\"c\":\"use\",\"b\":" + buf(buffer, null, 0));
	}

	static void draw(String kind, int a, int b, int c, int d, int e) {
		call("\"c\":\"" + kind + "\",\"a\":[" + a + "," + b + "," + c + "," + d + "," + e + "]");
	}

	static void multiDraw(boolean indexed, long params, int drawCount, int instances, int firstInstance) {
		call("\"c\":\"" + (indexed ? "mdrawi" : "mdraw") + "\",\"p\":" + ints(params, drawCount * (indexed ? 3 : 2)) + ",\"n\":" + drawCount + ",\"inst\":" + instances
			+ ",\"first\":" + firstInstance);
	}

	static void multiDrawIndexedSeparate(long offsets, long counts, long baseVertices, int drawCount) {
		call("\"c\":\"mdrawis\",\"off\":" + longs(offsets, drawCount) + ",\"cnt\":" + ints(counts, drawCount) + ",\"base\":" + ints(baseVertices, drawCount));
	}

	static void multiDrawSeparate(long firsts, long counts, int drawCount) {
		call("\"c\":\"mdraws\",\"first\":" + ints(firsts, drawCount) + ",\"cnt\":" + ints(counts, drawCount));
	}

	static void drawIndirect(boolean indexed, long buffer, long offset, int drawCount) {
		call("\"c\":\"" + (indexed ? "drawii" : "drawind") + "\",\"b\":" + buf(buffer, null, 0) + ",\"o\":" + offset + ",\"n\":" + drawCount);
	}

	static void clearRect(long color, long depth, float r, float g, float b, float a, float depthValue, int x, int y, int w, int h, int mip) {
		call("\"c\":\"clear\",\"color\":" + tex(color) + ",\"depth\":" + tex(depth) + ",\"v\":[" + r + "," + g + "," + b + "," + a + "," + depthValue + "],\"r\":[" + x + ","
			+ y + "," + w + "," + h + "," + mip + "]");
	}

	static void blitBuffer(boolean pre, long src, long srcOff, long dst, long dstOff, long size) {
		call("\"c\":\"" + (pre ? "preblit" : "blit") + "\",\"src\":" + buf(src, null, 0) + ",\"so\":" + srcOff + ",\"dst\":" + buf(dst, null, 0) + ",\"do\":" + dstOff
			+ ",\"n\":" + size);
	}

	static void fill(boolean pre, long buffer, long offset, long length, int value) {
		call("\"c\":\"" + (pre ? "prefill" : "fill") + "\",\"b\":" + buf(buffer, null, 0) + ",\"o\":" + offset + ",\"n\":" + length + ",\"v\":" + value);
	}

	static void blitBufferToTexture(long src, long off, int bytesPerRow, int bytesPerImage, long dst, int slice, int mip, int x, int y, int w, int h) {
		call("\"c\":\"b2t\",\"src\":" + buf(src, null, 0) + ",\"a\":[" + off + "," + bytesPerRow + "," + bytesPerImage + "," + slice + "," + mip + "," + x + "," + y + "," + w
			+ "," + h + "],\"dst\":" + tex(dst));
	}

	static void blitTextureToBuffer(long src, int mip, int x, int y, int w, int h, long dst, long off, int bytesPerRow) {
		call("\"c\":\"t2b\",\"src\":" + tex(src) + ",\"a\":[" + mip + "," + x + "," + y + "," + w + "," + h + "," + off + "," + bytesPerRow + "],\"dst\":" + buf(dst, null, 0));
	}

	static void blitTextureToTexture(long src, long dst, int mip, int dx, int dy, int sx, int sy, int w, int h) {
		call("\"c\":\"t2t\",\"src\":" + tex(src) + ",\"dst\":" + tex(dst) + ",\"a\":[" + mip + "," + dx + "," + dy + "," + sx + "," + sy + "," + w + "," + h + "]");
	}

	static void present(long texture) {
		presentTex = lastPresentTex = texture;
		call("\"c\":\"present\",\"tx\":" + tex(texture));
	}

	/** A call the replay leaves out on purpose (the clouds' cull: mcterrain.m, outside the clean room); its output buffer is still recorded. */
	static void excluded(String what, long out) {
		line("{\"t\":\"mc\",\"c\":\"unsupported\",\"what\":" + str(what) + ",\"out\":" + buf(out, null, 0) + "}");
	}

	static void unsupported(String what) {
		if (active) line("{\"t\":\"mc\",\"c\":\"unsupported\",\"what\":" + str(what) + "}");
	}

	/**
	 * Before the frame's commit (Native.encCommit): every shared buffer the frame referenced as pages (what the frame's GPU work reads:
	 * the CPU never rewrites what a frame in flight reads), then the commit record.
	 */
	static void beforeCommit() {
		try {
			for (long h : frameBufs) {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					long o = stack.nmalloc(8, 32);
					BUFFER_INFO.invokeExact(h, o);
					if (MemoryUtil.memGetLong(o + 8) == 0) snap(h, MemoryUtil.memGetLong(o), 0);
				}
			}
		} catch (Throwable t) {
			throw rethrow(t);
		}
		call("\"c\":\"commit\"");
	}

	/**
	 * After the frame's commit: after the burst's first frame, the contents of every texture and private buffer it referenced (copied on a
	 * command buffer of their own, queued after the frame: the state the next frame starts from); in later frames those first
	 * referenced then ("late"). In image frames, the presented image. Then the next frame starts.
	 */
	static void afterCommit(long ctx) {
		if (!active) {
			frameStart();
			return;
		}
		List<Long> texs = new ArrayList<>(), bufs = new ArrayList<>();
		for (long h : frameTexs) if (texCaptured.add(h)) texs.add(h);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long o = stack.nmalloc(8, 32);
			for (long h : frameBufs) {
				BUFFER_INFO.invokeExact(h, o);
				if (MemoryUtil.memGetLong(o + 8) != 0 && bufCaptured.add(h)) bufs.add(h);
			}
		} catch (Throwable t) {
			throw rethrow(t);
		}
		int f = frameIndex - 1;
		// Queued after the main frame and before frameStart permits the next cull/reset.
		copyCompletedOwn(ctx, f);
		copyOut(texs, bufs, f == 0 ? "start" : "late", f);
		// (presents are paced: most frames present nothing; the final target is the one last presented)
		long image = presentTex != 0 ? presentTex : lastPresentTex;
		if (images && image != 0) {
			tex(image);
			copyOut(List.of(image), List.of(), "image", f);
		}
		frameStart();
	}

	/** Optional completed producer bundle for CPU analysis; never changes replay's start state. */
	public static void completedOwnBuffer(String role, long h) {
		if (h != 0 && active && FULL && (COMPLETED.equals("all") || COMPLETED.equals("first") && frameIndex - 1 == 1))
			completedOwn.put(role, h);
	}

	private static void copyCompletedOwn(long ctx, int frame) {
		if (completedOwn.isEmpty()) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int n = completedOwn.size(), i = 0;
			long objects = stack.nmalloc(8, 8 * n), paths = stack.nmalloc(8, 8 * n);
			StringBuilder files = new StringBuilder("[");
			for (var e : completedOwn.entrySet()) {
				long h = e.getValue();
				Long bytes = bufLen.get(h);
				if (bytes == null) throw new IllegalStateException("completed buffer without descriptor: " + h);
				String name = "f" + frame + "-completed-" + e.getKey() + "-b" + Long.toHexString(h) + ".buf";
				MemoryUtil.memPutLong(objects + 8L * i, h);
				MemoryUtil.memPutLong(paths + 8L * i, MemoryUtil.memAddress(stack.UTF8(burstDir.resolve(name).toString())));
				files.append(i++ > 0 ? "," : "").append("{\"role\":").append(str(e.getKey())).append(",\"h\":").append(h)
					.append(",\"bytes\":").append(bytes).append(",\"file\":").append(str(name)).append('}');
			}
			int r = (int) COPY_BUFFERS_COMPLETED.invokeExact(ctx, n, objects, paths);
			line("{\"t\":\"completed\",\"schema\":1,\"frame\":" + frame + ",\"r\":" + r
				+ ",\"queueOrder\":\"after-main-commit-before-next-frame\",\"files\":" + files.append(']') + "}");
			if (r != 1) line("{\"t\":\"error\",\"what\":\"completed own buffer readback failed\"}");
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static void copyOut(List<Long> texs, List<Long> bufs, String what, int frame) {
		int n = texs.size() + bufs.size();
		if (n == 0) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long t = stack.nmalloc(8, 8 * n), kinds = stack.nmalloc(4, 4 * n), p = stack.nmalloc(8, 8 * n);
			StringBuilder files = new StringBuilder("[");
			int i = 0;
			for (int k = 0; k < n; k++, i++) {
				boolean isTex = k < texs.size();
				long h = isTex ? texs.get(k) : bufs.get(k - texs.size());
				String file = "f" + frame + "-" + what + "-" + (isTex ? "t" : "b") + Long.toHexString(h) + (isTex ? ".tex" : ".buf");
				MemoryUtil.memPutLong(t + 8L * k, h);
				MemoryUtil.memPutInt(kinds + 4L * k, isTex ? 0 : 1);
				MemoryUtil.memPutLong(p + 8L * k, MemoryUtil.memAddress(stack.UTF8(burstDir.resolve(file).toString())));
				files.append(k > 0 ? "," : "").append("[").append(h).append(',').append(str(file)).append(']');
			}
			int r = (int) COPY_OUT.invokeExact(Own.ctx, n, t, kinds, p);
			line("{\"t\":\"contents\",\"what\":\"" + what + "\",\"frame\":" + frame + ",\"r\":" + r + ",\"files\":" + files + "]}");
		} catch (Throwable e) {
			throw rethrow(e);
		}
	}

	/** What the own renderer passes at its setup (OwnNative) and the backend's context (MetalDevice), for the init record and copies. */
	public static final class Own {
		public static boolean compact, fat, cpuClip, exactPos;
		public static String prefix = "";
		public static int hizBits;
		public static long ctx;

		private Own() {
		}
	}
}
