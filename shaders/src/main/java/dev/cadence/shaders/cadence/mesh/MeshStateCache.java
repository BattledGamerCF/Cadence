package dev.cadence.shaders.cadence.mesh;

import java.util.concurrent.atomic.LongAdder;
import org.slf4j.LoggerFactory;

/**
 * Cadence: chunk meshing reads each block's neighbours many times (face culling, ambient occlusion, light, fluids), and every
 * read decodes the section's palette again (bit storage + palette lookup: 16-18% of meshing time in profiles). A section is
 * meshed from a RenderSectionRegion, an immutable copy of the 3x3x3 sections around it made when the task was created; its
 * block states for the section and a one-block border (18x18x18) are remembered on first read (MeshStateCacheMixin). Exact:
 * the copy never changes and the same BlockState object is returned. Reads outside the border go to the copy as before.
 * -Dcadence.meshCache=false: off (switchable at runtime: meshcache= self-test step).
 *
 * <p>Self-tests also time every section compile (worker CPU spent meshing) for A/Bs: {@link #report}.
 */
public final class MeshStateCache {
	/** CompileTaskReleaseMixin: a finished compile task drops its region copy. */
	public static volatile boolean releaseRegions = !"false".equals(System.getProperty("cadence.releaseRegions"));
	public static volatile boolean enabled = !"false".equals(System.getProperty("cadence.meshCache"));
	public static final boolean TIMING = System.getProperty("cadence.selftest") != null || Boolean.getBoolean("cadence.probe");
	/** -Dcadence.meshCacheVerify: every cached read is checked against the copy (mismatches counted and reported). */
	public static final boolean VERIFY = Boolean.getBoolean("cadence.meshCacheVerify");
	private static final LongAdder hits = new LongAdder(), mismatches = new LongAdder();
	private static final LongAdder nanos = new LongAdder(), sections = new LongAdder();

	private MeshStateCache() {
	}

	/** Implemented on RenderSectionRegion by MeshStateCacheMixin: the build is over, drop the cached states. */
	public interface Holder {
		void cadence$dropStates();
	}

	/** Self-tests: how many times each section was meshed since the last report. */
	private static final java.util.concurrent.ConcurrentHashMap<Long, Integer> perSection = new java.util.concurrent.ConcurrentHashMap<>();

	public static void meshedSection(long sectionKey) {
		perSection.merge(sectionKey, 1, Integer::sum);
	}

	private static final LongAdder drawNanos = new LongAdder(), drawCalls = new LongAdder();

	/** Per face-group mode (0 objects, 1 packed): draw-list time, for interleaved A/Bs (ab=facepacked). */
	private static final LongAdder[] modeNanos = {new LongAdder(), new LongAdder()}, modeCalls = {new LongAdder(), new LongAdder()};

	public static void drawList(long ns) {
		drawNanos.add(ns);
		drawCalls.increment();
		int m = dev.cadence.shaders.cadence.facecull.FaceCull.packedGroups ? 1 : 0;
		modeNanos[m].add(ns);
		modeCalls[m].increment();
	}

	public static String drawListModes() {
		String r = "";
		for (int m = 0; m < 2; m++) {
			long n = modeNanos[m].sumThenReset(), c = modeCalls[m].sumThenReset();
			r += (m == 0 ? "objects " : ", packed ") + (c == 0 ? "-" : n / c / 1000 + " us") + " (" + c + " frames)";
		}
		return r;
	}

	public static void compiled(long ns) {
		nanos.add(ns);
		sections.increment();
	}

	private static final LongAdder lightHits = new LongAdder(), lightMismatches = new LongAdder();

	public static void verifyLight(int cached, int actual) {
		lightHits.increment();
		if (cached != actual) lightMismatches.increment();
	}

	public static void verify(Object cached, Object actual) {
		hits.increment();
		if (cached != actual) mismatches.increment();
	}

	/** Logs the meshing time since the last report: total ms, sections, µs per section. */
	public static void report(String label) {
		long n = nanos.sumThenReset(), s = sections.sumThenReset();
		LoggerFactory.getLogger("cadence").info("[mesh] {}: {} sections meshed, {} ms worker time, {} us per section (cache {})", label, s,
			n / 1_000_000, s == 0 ? 0 : n / s / 1000, enabled ? "on" : "off");
		long dn = drawNanos.sumThenReset(), dc = drawCalls.sumThenReset();
		if (dc > 0) LoggerFactory.getLogger("cadence").info("[mesh] {}: chunk draw lists {} us per call over {} calls (face groups {})", label,
			dn / dc / 1000, dc, dev.cadence.shaders.cadence.facecull.FaceCull.packedGroups ? "packed" : "objects");
		if (!perSection.isEmpty()) {
			int[] hist = new int[6];
			for (int c : perSection.values()) hist[Math.min(c, 5)]++;
			LoggerFactory.getLogger("cadence").info("[mesh] {}: {} distinct sections; meshed once {}, twice {}, 3x {}, 4x {}, 5+ {}", label,
				perSection.size(), hist[1], hist[2], hist[3], hist[4], hist[5]);
			perSection.clear();
		}
		if (VERIFY) LoggerFactory.getLogger("cadence").info("[mesh] verify: {} cached block reads checked, {} mismatches; {} cached light reads, {} differ now (light changed meanwhile)",
			hits.sum(), mismatches.sum(), lightHits.sum(), lightMismatches.sum());
	}
}
