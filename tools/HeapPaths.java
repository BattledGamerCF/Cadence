import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What keeps an object alive: for every instance of a class in a heap dump (.hprof, for example from the self-test's
 * "heapdump" step), the shortest chains of strong references from GC roots to it, found by searching backwards from the
 * instance one full pass over the dump per step. Weak, soft and phantom references (Reference.referent) don't count.
 *
 * <p>java tools/HeapPaths.java HEAP.hprof [CLASS] [MAX_PATHS]   (CLASS default net.minecraft.client.multiplayer.ClientLevel)
 */
public final class HeapPaths {
	static Buf in;
	static int idSize;
	static final Map<Long, String> strings = new HashMap<>();
	static final Map<Long, String> classNames = new HashMap<>();
	static final Map<Long, ClassInfo> classes = new HashMap<>();
	static final Map<Long, String> roots = new HashMap<>();  // object id -> what kind of root
	static final List<long[]> segments = new ArrayList<>();  // heap dump segments: {start, end}

	static final class ClassInfo {
		long id, superId;
		long[] fieldNames;
		byte[] fieldTypes;
		List<long[]> staticRefs = new ArrayList<>();  // {name id, object id}
		int[] refOffsets;  // where an instance's references are, this class's own fields first, then its super's
		String[] refNames;
	}

	public static void main(String[] args) throws IOException {
		String target = (args.length > 1 ? args[1] : "net.minecraft.client.multiplayer.ClientLevel").replace('.', '/');
		int maxPaths = args.length > 2 ? Integer.parseInt(args[2]) : 6;
		in = new Buf(args[0]);
		readRecords();
		System.out.println(classes.size() + " classes, " + roots.size() + " roots");
		List<Long> targets = new ArrayList<>();
		scan((id, cls, refs, names) -> {
			if (target.equals(classNames.get(cls))) targets.add(id);
			return false;
		});
		System.out.println(targets.size() + " instances of " + target);
		for (long t : targets) {
			System.out.println("\n=== " + target + " @" + Long.toHexString(t));
			paths(t, maxPaths);
		}
	}

	interface Visitor {
		/** Each object: its references and their field names (array elements as [i]). */
		boolean visit(long id, long classId, long[] refs, String[] names);
	}

	record Link(long to, String field, long classId) {
	}

	static void paths(long target, int maxPaths) {
		Map<Long, Link> parent = new HashMap<>();  // referrer -> what it refers to on the way to the target
		Set<Long> seen = new HashSet<>();
		seen.add(target);
		Set<Long> frontier = new HashSet<>(Set.of(target));
		int found = 0;
		for (int depth = 1; depth <= 40 && !frontier.isEmpty() && found < maxPaths; depth++) {
			// roots and class statics that reach the frontier
			for (long f : frontier) {
				String r = roots.get(f);
				if (r != null && found < maxPaths) {
					found++;
					print(r, f, parent);
				}
			}
			for (ClassInfo c : classes.values()) {
				for (long[] s : c.staticRefs) {
					if (frontier.contains(s[1]) && found < maxPaths) {
						found++;
						print("static " + classNames.get(c.id).replace('/', '.') + "." + strings.get(s[0]), s[1], parent);
					}
				}
			}
			if (found >= maxPaths) break;
			Set<Long> next = new HashSet<>();
			Set<Long> cur = frontier;
			scan((id, cls, refs, names) -> {
				if (seen.contains(id)) return false;
				for (int i = 0; i < refs.length; i++) {
					if (refs[i] != 0 && cur.contains(refs[i])) {
						parent.put(id, new Link(refs[i], names[i], cls));
						next.add(id);
						return false;
					}
				}
				return false;
			});
			seen.addAll(next);
			System.out.println("  depth " + depth + ": " + next.size() + " referrers");
			frontier = next;
		}
		if (found == 0) System.out.println("  no path from a root found");
	}

	static void print(String root, long from, Map<Long, Link> parent) {
		StringBuilder sb = new StringBuilder("  ROOT ").append(root);
		long at = from;
		while (true) {
			Link l = parent.get(at);
			if (l == null) break;
			sb.append("\n    ").append(name(l.classId)).append(" @").append(Long.toHexString(at)).append(" .").append(l.field);
			at = l.to;
		}
		sb.append("\n    -> target");
		System.out.println(sb);
	}

	static String name(long classId) {
		String n = classNames.get(classId);
		return n == null ? "?" : n.replace('/', '.');
	}

	// ---- the file

	static void readRecords() {
		long p = 0;
		while (in.u1(p) != 0) p++;
		p++;
		idSize = in.u4(p);
		p += 12;
		while (p < in.size) {
			int tag = in.u1(p);
			long len = in.u4(p + 5) & 0xFFFFFFFFL;
			long body = p + 9;
			if (tag == 0x01) {
				byte[] b = new byte[(int) (len - idSize)];
				for (int i = 0; i < b.length; i++) b[i] = (byte) in.u1(body + idSize + i);
				strings.put(in.id(body), new String(b, java.nio.charset.StandardCharsets.UTF_8));
			} else if (tag == 0x02) {
				classNames.put(in.id(body + 4), strings.get(in.id(body + 8 + idSize)));
			} else if (tag == 0x0C || tag == 0x1C) {
				segments.add(new long[] {body, body + len});
				readSegment(body, body + len);
			}
			p = body + len;
		}
		for (ClassInfo c : classes.values()) layout(c);
	}

	static void readSegment(long p, long end) {
		while (p < end) {
			int t = in.u1(p++);
			switch (t) {
				case 0xFF -> { roots.putIfAbsent(in.id(p), "unknown"); p += idSize; }
				case 0x01 -> { roots.putIfAbsent(in.id(p), "JNI global"); p += 2L * idSize; }
				case 0x02 -> { roots.putIfAbsent(in.id(p), "JNI local (thread " + in.u4(p + idSize) + ")"); p += idSize + 8; }
				case 0x03 -> { roots.putIfAbsent(in.id(p), "Java frame (thread " + in.u4(p + idSize) + ", frame " + in.u4(p + idSize + 4) + ")"); p += idSize + 8; }
				case 0x04 -> { roots.putIfAbsent(in.id(p), "native stack"); p += idSize + 4; }
				case 0x05 -> { roots.putIfAbsent(in.id(p), "system class"); p += idSize; }
				case 0x06 -> { roots.putIfAbsent(in.id(p), "thread block"); p += idSize + 4; }
				case 0x07 -> { roots.putIfAbsent(in.id(p), "monitor"); p += idSize; }
				case 0x08 -> { roots.putIfAbsent(in.id(p), "thread object (thread " + in.u4(p + idSize) + ")"); p += idSize + 8; }
				case 0x20 -> p = classDump(p);
				case 0x21 -> p += 2L * idSize + 4 + 4 + (in.u4(p + 2L * idSize + 4) & 0xFFFFFFFFL);
				case 0x22 -> p += idSize + 8 + idSize + (long) in.u4(p + idSize + 4) * idSize;
				case 0x23 -> p += idSize + 9 + (long) in.u4(p + idSize + 4) * size(in.u1(p + idSize + 8));
				default -> throw new IllegalStateException("subrecord " + Integer.toHexString(t) + " at " + p);
			}
		}
	}

	static long classDump(long p) {
		ClassInfo c = new ClassInfo();
		c.id = in.id(p);
		c.superId = in.id(p + idSize + 4);
		p += 7L * idSize + 8;
		int cp = in.u2(p);
		p += 2;
		for (int i = 0; i < cp; i++) p += 3 + size(in.u1(p + 2));
		int statics = in.u2(p);
		p += 2;
		for (int i = 0; i < statics; i++) {
			long name = in.id(p);
			int type = in.u1(p + idSize);
			if (type == 2) {
				long v = in.id(p + idSize + 1);
				if (v != 0) c.staticRefs.add(new long[] {name, v});
			}
			p += idSize + 1 + size(type);
		}
		int fields = in.u2(p);
		p += 2;
		c.fieldNames = new long[fields];
		c.fieldTypes = new byte[fields];
		for (int i = 0; i < fields; i++) {
			c.fieldNames[i] = in.id(p);
			c.fieldTypes[i] = (byte) in.u1(p + idSize);
			p += idSize + 1;
		}
		classes.put(c.id, c);
		return p;
	}

	static void layout(ClassInfo c) {
		List<Integer> offs = new ArrayList<>();
		List<String> names = new ArrayList<>();
		int off = 0;
		for (ClassInfo k = c; k != null; k = classes.get(k.superId)) {
			String kn = classNames.get(k.id);
			for (int i = 0; i < k.fieldNames.length; i++) {
				String fn = strings.get(k.fieldNames[i]);
				if (k.fieldTypes[i] == 2 && !("java/lang/ref/Reference".equals(kn) && "referent".equals(fn))) {
					offs.add(off);
					names.add(fn);
				}
				off += size(k.fieldTypes[i]);
			}
		}
		c.refOffsets = offs.stream().mapToInt(Integer::intValue).toArray();
		c.refNames = names.toArray(new String[0]);
	}

	static final String[] NO_NAMES = new String[0];

	static void scan(Visitor v) {
		for (long[] s : segments) {
			long p = s[0];
			while (p < s[1]) {
				int t = in.u1(p++);
				switch (t) {
					case 0xFF, 0x05, 0x07 -> p += idSize;
					case 0x01 -> p += 2L * idSize;
					case 0x02, 0x03, 0x08 -> p += idSize + 8;
					case 0x04, 0x06 -> p += idSize + 4;
					case 0x20 -> p = skipClass(p);
					case 0x21 -> {
						long id = in.id(p), cls = in.id(p + idSize + 4);
						long n = in.u4(p + 2L * idSize + 4) & 0xFFFFFFFFL;
						long data = p + 2L * idSize + 8;
						ClassInfo c = classes.get(cls);
						long[] refs = new long[c == null ? 0 : c.refOffsets.length];
						for (int i = 0; i < refs.length; i++) refs[i] = in.id(data + c.refOffsets[i]);
						v.visit(id, cls, refs, c == null ? NO_NAMES : c.refNames);
						p = data + n;
					}
					case 0x22 -> {
						long id = in.id(p);
						int n = in.u4(p + idSize + 4);
						long cls = in.id(p + idSize + 8);
						long data = p + 2L * idSize + 8;
						long[] refs = new long[n];
						String[] names = new String[n];
						for (int i = 0; i < n; i++) {
							refs[i] = in.id(data + (long) i * idSize);
							names[i] = "[" + i + "]";
						}
						v.visit(id, cls, refs, names);
						p = data + (long) n * idSize;
					}
					case 0x23 -> p += idSize + 9 + (long) in.u4(p + idSize + 4) * size(in.u1(p + idSize + 8));
					default -> throw new IllegalStateException("subrecord " + Integer.toHexString(t));
				}
			}
		}
	}

	static long skipClass(long p) {
		p += 7L * idSize + 8;
		int cp = in.u2(p);
		p += 2;
		for (int i = 0; i < cp; i++) p += 3 + size(in.u1(p + 2));
		int statics = in.u2(p);
		p += 2;
		for (int i = 0; i < statics; i++) p += idSize + 1 + size(in.u1(p + idSize));
		return p + 2 + (long) in.u2(p) * (idSize + 1);
	}

	static int size(int type) {
		return switch (type) {
			case 2 -> idSize;
			case 4, 8 -> 1;
			case 5, 9 -> 2;
			case 6, 10 -> 4;
			case 7, 11 -> 8;
			default -> throw new IllegalStateException("type " + type);
		};
	}

	/** The file, mapped 1 GB at a time. */
	static final class Buf {
		final MappedByteBuffer[] parts;
		final long size;

		Buf(String path) throws IOException {
			try (RandomAccessFile f = new RandomAccessFile(path, "r"); FileChannel ch = f.getChannel()) {
				size = ch.size();
				parts = new MappedByteBuffer[(int) ((size >> 30) + 1)];
				for (int i = 0; i < parts.length; i++) {
					long start = (long) i << 30;
					parts[i] = ch.map(FileChannel.MapMode.READ_ONLY, start, Math.min(size - start, (1L << 30) + 16));
				}
			}
		}

		int u1(long p) {
			return parts[(int) (p >> 30)].get((int) (p & ((1 << 30) - 1))) & 0xFF;
		}

		int u2(long p) {
			return (u1(p) << 8) | u1(p + 1);
		}

		int u4(long p) {
			int i = (int) (p >> 30), o = (int) (p & ((1 << 30) - 1));
			if (o + 4 <= parts[i].limit()) return parts[i].getInt(o);
			return (u1(p) << 24) | (u1(p + 1) << 16) | (u1(p + 2) << 8) | u1(p + 3);
		}

		long id(long p) {
			if (idSize == 4) return u4(p) & 0xFFFFFFFFL;
			int i = (int) (p >> 30), o = (int) (p & ((1 << 30) - 1));
			if (o + 8 <= parts[i].limit()) return parts[i].getLong(o);
			return ((long) u4(p) << 32) | (u4(p + 4) & 0xFFFFFFFFL);
		}
	}
}
