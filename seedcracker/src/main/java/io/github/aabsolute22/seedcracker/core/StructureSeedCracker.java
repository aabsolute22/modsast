package io.github.aabsolute22.seedcracker.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Recovers the 48-bit structure seed from observed structure starts.
 * <p>
 * Every {@code random_spread} region seeds a {@code java.util.Random} with {@code seed + K} (K depends on the region
 * and the salt), so the first output state is {@code s1 = ((seed + K) ^ M) * a + c}. The search uses three facts:
 * <ol>
 * <li><b>Lifting.</b> If the region's bound has a factor {@code 2^k}, the offset reveals bits 17..17+k-1 of
 * {@code s1}, which depend only on the low 17+k bits of the seed. Those low bits are filtered first.</li>
 * <li><b>The top 13 bits.</b> {@code M} is only 35 bits wide, so with the low 35 bits of the seed fixed, the top 13
 * bits of {@code s1} are {@code (B + h * a) mod 2^13} for the seed's top 13 bits {@code h}. A buried treasure
 * ({@code nextFloat() < 0.01}) or a power-of-two bound pins that value to an interval, and an odd bound {@code m}
 * pins it to one residue mod {@code m}; the candidates for {@code h} are listed directly instead of tried.</li>
 * <li>Everything else (other offsets, rotations, outpost rules) is checked per candidate with early exit.</li>
 * </ol>
 */
public final class StructureSeedCracker {
	static final long A = LegacyRandom.MULTIPLIER;
	static final long C = LegacyRandom.ADDEND;
	static final long MASK48 = LegacyRandom.MASK;
	static final long MASK35 = (1L << 35) - 1;
	static final int TOP = 8192;
	static final int TOP_MASK = TOP - 1;
	static final int A_INV_13 = inverseMod2k(A, 13);
	private static final int MAX_LOW_BITS = 21;
	private static final int MAX_INTERVALS = 3;
	private static final int MAX_CONGRUENCES = 3;

	public record Result(List<Long> seeds, boolean overflow, boolean cancelled) {
	}

	/** Low-bit filter from an even region bound: bits 17.. of the first two outputs. */
	private record Lift(long k, int mask, int offX, int offZ) {
	}

	/** The top 13 bits of {@code s1} for region constant {@code k} lie in {@code [lo, hi]}. */
	private record Interval(long k, int lo, int hi) {
	}

	/** {@code (s1 >>> 17) % n == off} restricted to the odd part {@code m} of {@code n}. */
	private record Congruence(long k, int m, int inv18, int off) {
	}

	/** Fast placement test for one candidate chunk; the full {@link Observation#matches} runs only if it passes. */
	private record Check(Observation observation, long[] k, int[] offX, int[] offZ, int[] candidate, int limit, boolean pow2,
			boolean triangular, boolean placementOnly, double selectivity) {
	}

	private final List<Observation> observations;
	private final Check[] checks;
	private final Lift[] lifts;
	private final Interval[] intervals;
	private final Congruence[] congruences;
	/** CRT folding of congruence j into the first j: gcd, modulus of the first j, inverse for the step. */
	private final long[] crtGcd;
	private final long[] crtModulus;
	private final long[] crtInv;
	private final int lowBits;
	private final long[] lows;
	private final int midBits;
	private final int blockBits;
	private final long blocksPerLow;
	private final long totalBlocks;
	private final int maxSeeds;
	private final AtomicBoolean cancel = new AtomicBoolean();
	private final AtomicLong nextBlock = new AtomicLong();
	private final AtomicLong doneBlocks = new AtomicLong();

	public StructureSeedCracker(List<Observation> observations, int maxSeeds) {
		this.observations = List.copyOf(observations);
		this.maxSeeds = maxSeeds;
		List<Check> checkList = new ArrayList<>();
		List<Lift> liftList = new ArrayList<>();
		List<Interval> intervalList = new ArrayList<>();
		List<Congruence> congruenceList = new ArrayList<>();
		int lowBits = 0;
		for (Observation o : this.observations) {
			Check check = compile(o);
			if (check == null) {
				continue;
			}
			checkList.add(check);
			if (!o.isExact() || check.k.length != 1) {
				continue;
			}
			Spread s = o.spread();
			int n = s.limit();
			int ox = check.offX[0];
			int oz = check.offZ[0];
			long k = check.k[0];
			if (s.frequency() < 1f && s.reducer() == Spread.Reducer.LEGACY_TYPE_2) {
				// nextFloat() < f on the treasure random: s1 < threshold * 2^24.
				int threshold = floatThreshold(s.frequency());
				long kt = (long) o.candidates().get(0).chunkX() * Spread.REGION_X + (long) o.candidates().get(0).chunkZ() * Spread.REGION_Z
						+ Spread.LEGACY_2_SALT;
				intervalList.add(new Interval(kt, 0, (int) ((((long) threshold << 24) - 1) >>> 35)));
			}
			if (s.triangular() || n <= 1) {
				continue;
			}
			if ((n & (n - 1)) == 0) {
				int p = Integer.numberOfTrailingZeros(n);
				if (p <= 13) {
					int width = 1 << (13 - p);
					intervalList.add(new Interval(k, ox * width, ox * width + width - 1));
				}
				continue;
			}
			int twos = Integer.numberOfTrailingZeros(n);
			if (twos > 0 && 17 + twos <= MAX_LOW_BITS) {
				liftList.add(new Lift(k, (1 << twos) - 1, ox, oz));
				lowBits = Math.max(lowBits, 17 + twos);
			}
			int m = n >> twos;
			if (m > 1) {
				int r18 = (int) ((1L << 18) % m);
				congruenceList.add(new Congruence(k, m, inverseMod(r18, m), ox));
			}
		}
		checkList.sort(Comparator.comparingDouble(Check::selectivity));
		this.checks = checkList.toArray(new Check[0]);
		this.lifts = liftList.toArray(new Lift[0]);
		intervalList.sort(Comparator.comparingInt(i -> i.hi - i.lo));
		this.intervals = intervalList.subList(0, Math.min(MAX_INTERVALS, intervalList.size())).toArray(new Interval[0]);
		congruenceList.sort(Comparator.comparingInt(Congruence::m).reversed());
		this.congruences = pickCongruences(congruenceList);
		int nc = congruences.length;
		this.crtGcd = new long[nc + 1];
		this.crtModulus = new long[nc + 1];
		this.crtInv = new long[nc + 1];
		long modulus = 1;
		for (int j = 0; j < nc; j++) {
			long m = congruences[j].m;
			long g = gcd(modulus, m);
			crtGcd[j] = g;
			crtModulus[j] = modulus;
			long mg = m / g;
			crtInv[j] = mg > 1 ? inverseMod((int) Math.floorMod(modulus / g, mg), (int) mg) : 0;
			modulus = modulus / g * m;
		}
		crtModulus[nc] = modulus;
		this.lowBits = lowBits;
		this.lows = liftLowBits();
		this.midBits = 35 - lowBits;
		this.blockBits = Math.min(12, midBits);
		this.blocksPerLow = 1L << (midBits - blockBits);
		this.totalBlocks = lows.length * blocksPerLow;
	}

	/** The two largest moduli, preferring a second one that adds information (not a repeat of the first). */
	private static Congruence[] pickCongruences(List<Congruence> list) {
		List<Congruence> picked = new ArrayList<>();
		for (Congruence c : list) {
			if (picked.size() >= MAX_CONGRUENCES) {
				break;
			}
			picked.add(c);
		}
		return picked.toArray(new Congruence[0]);
	}

	private static Check compile(Observation o) {
		Spread s = o.spread();
		List<Long> ks = new ArrayList<>();
		List<int[]> offs = new ArrayList<>();
		List<Integer> index = new ArrayList<>();
		for (int i = 0; i < o.candidates().size(); i++) {
			Observation.Candidate c = o.candidates().get(i);
			int rx = s.region(c.chunkX());
			int rz = s.region(c.chunkZ());
			int ox = c.chunkX() - rx * s.spacing();
			int oz = c.chunkZ() - rz * s.spacing();
			if (ox >= s.limit() || oz >= s.limit()) {
				continue;
			}
			ks.add(s.regionConstant(rx, rz));
			offs.add(new int[] {ox, oz});
			index.add(i);
		}
		if (ks.isEmpty()) {
			return null;
		}
		int size = ks.size();
		long[] k = new long[size];
		int[] offX = new int[size];
		int[] offZ = new int[size];
		int[] candidate = new int[size];
		for (int i = 0; i < size; i++) {
			k[i] = ks.get(i);
			offX[i] = offs.get(i)[0];
			offZ[i] = offs.get(i)[1];
			candidate[i] = index.get(i);
		}
		int n = s.limit();
		boolean placementOnly = s.frequency() >= 1f && !o.outpost() && o.pick() < 0
				&& o.candidates().stream().allMatch(c -> c.rotation() < 0);
		return new Check(o, k, offX, offZ, candidate, n, (n & (n - 1)) == 0, s.triangular(), placementOnly, o.selectivity());
	}

	/** Smallest {@code t} with {@code t * 2^-24 >= f} in float arithmetic, i.e. {@code nextFloat() < f} iff {@code next(24) < t}. */
	static int floatThreshold(float f) {
		int t = (int) Math.floor(f * 16777216.0);
		while (t > 0 && (t - 1) * 5.9604645E-8F >= f) {
			t--;
		}
		while (t * 5.9604645E-8F < f) {
			t++;
		}
		return t;
	}

	static int inverseMod2k(long a, int k) {
		long mod = 1L << k;
		long x = 1;
		for (int i = 0; i < 64; i++) {
			x = x * (2 - a * x) & (mod - 1);
		}
		return (int) x;
	}

	static int inverseMod(int a, int m) {
		for (int x = 1; x < m; x++) {
			if ((long) a * x % m == 1) {
				return x;
			}
		}
		return 0;
	}

	private long[] liftLowBits() {
		if (lowBits == 0) {
			return new long[] {0L};
		}
		long size = 1L << lowBits;
		long mask = size - 1;
		long[] out = new long[16];
		int count = 0;
		for (long low = 0; low < size; low++) {
			if (liftOk(low, mask)) {
				if (count == out.length) {
					out = java.util.Arrays.copyOf(out, count * 2);
				}
				out[count++] = low;
			}
		}
		return java.util.Arrays.copyOf(out, count);
	}

	private boolean liftOk(long low, long mask) {
		for (Lift l : lifts) {
			long s = ((low + l.k) ^ A) & mask;
			s = (s * A + C) & mask;
			if (((int) (s >>> 17) & l.mask) != (l.offX & l.mask)) {
				return false;
			}
			s = (s * A + C) & mask;
			if (((int) (s >>> 17) & l.mask) != (l.offZ & l.mask)) {
				return false;
			}
		}
		return true;
	}

	public List<Observation> observations() {
		return observations;
	}

	public int usableObservations() {
		return checks.length;
	}

	public int lowBits() {
		return lowBits;
	}

	public int lowCandidates() {
		return lows.length;
	}

	/** Total bits of information in the observations. */
	public double informationBits() {
		double bits = 0;
		for (Check c : checks) {
			bits += -Math.log(c.selectivity) / Math.log(2);
		}
		return bits;
	}

	/** Expected number of wrong seeds that pass all checks by chance. */
	public double expectedFalseSeeds() {
		return Math.pow(2, 48 - informationBits());
	}

	/** Expected candidates for the top 13 bits per (low, mid) pair. */
	public double candidatesPerPair() {
		double e = TOP;
		for (Interval i : intervals) {
			e *= (i.hi - i.lo + 1) / (double) TOP;
		}
		for (Congruence c : congruences) {
			e /= c.m;
		}
		return e;
	}

	/** Number of (low, mid) pairs that will be visited; the work is roughly proportional to it. */
	public double pairs() {
		return (double) lows.length * Math.pow(2, midBits);
	}

	/** Rough work estimate in nanoseconds of one core. */
	public double estimatedNanos() {
		double overhead = 12 + 6 * (intervals.length + congruences.length);
		return pairs() * (overhead + 4 * candidatesPerPair());
	}

	public double progress() {
		return totalBlocks == 0 ? 1.0 : (double) doneBlocks.get() / totalBlocks;
	}

	public void cancel() {
		cancel.set(true);
	}

	public Result run(int threads) {
		ConcurrentLinkedQueue<Long> found = new ConcurrentLinkedQueue<>();
		AtomicInteger count = new AtomicInteger();
		AtomicBoolean overflow = new AtomicBoolean();
		if (checks.length == 0) {
			return new Result(List.of(), true, false);
		}
		List<Thread> workers = new ArrayList<>();
		for (int t = 0; t < Math.max(1, threads); t++) {
			Thread worker = new Thread(() -> work(found, count, overflow), "seedcracker-" + t);
			worker.setDaemon(true);
			worker.setPriority(Thread.MIN_PRIORITY);
			worker.start();
			workers.add(worker);
		}
		for (Thread worker : workers) {
			try {
				worker.join();
			} catch (InterruptedException e) {
				cancel.set(true);
				Thread.currentThread().interrupt();
			}
		}
		List<Long> seeds = new ArrayList<>(found);
		seeds.sort(Long::compare);
		return new Result(seeds, overflow.get(), cancel.get() && !overflow.get());
	}

	private void work(ConcurrentLinkedQueue<Long> found, AtomicInteger count, AtomicBoolean overflow) {
		Worker w = new Worker();
		long blockSize = 1L << blockBits;
		while (!cancel.get()) {
			long block = nextBlock.getAndIncrement();
			if (block >= totalBlocks) {
				return;
			}
			long low = lows[(int) (block / blocksPerLow)];
			long midStart = (block % blocksPerLow) * blockSize;
			for (long mid = midStart; mid < midStart + blockSize; mid++) {
				w.pair(low | mid << lowBits);
				if (w.hitCount > 0) {
					for (int i = 0; i < w.hitCount; i++) {
						found.add(w.hits[i]);
						if (count.incrementAndGet() > maxSeeds) {
							overflow.set(true);
							cancel.set(true);
						}
					}
					w.hitCount = 0;
				}
			}
			doneBlocks.incrementAndGet();
		}
	}

	/** Checks a full 48-bit seed against every observation. */
	public boolean accepts(long seed) {
		return new Worker().check(seed & MASK48);
	}

	/** Per-thread state: no allocation in the hot loop. */
	private final class Worker {
		private long state;
		final long[] hits = new long[64];
		int hitCount;
		private final int[] base = new int[MAX_INTERVALS + MAX_CONGRUENCES];
		private final int[] residue = new int[MAX_CONGRUENCES];
		// Linear intervals [start, end) of the anchor's top 13 bits that are still possible.
		private final int[] segStart = new int[16];
		private final int[] segEnd = new int[16];
		private final int[] tmpStart = new int[16];
		private final int[] tmpEnd = new int[16];
		private final int[] cuts = new int[MAX_CONGRUENCES + 2];
		private final int[] shift = new int[MAX_CONGRUENCES];

		private int nextInt(int n, boolean pow2) {
			state = (state * A + C) & MASK48;
			int bits = (int) (state >>> 17);
			if (pow2) {
				return (int) ((long) n * bits >> 31);
			}
			int value = bits % n;
			while (bits - value + (n - 1) < 0) {
				state = (state * A + C) & MASK48;
				bits = (int) (state >>> 17);
				value = bits % n;
			}
			return value;
		}

		boolean check(long seed) {
			for (Check c : checks) {
				boolean any = false;
				for (int i = 0; i < c.k.length && !any; i++) {
					state = ((seed + c.k[i]) ^ A) & MASK48;
					int x;
					int z;
					if (c.triangular) {
						x = (nextInt(c.limit, c.pow2) + nextInt(c.limit, c.pow2)) / 2;
						if (x != c.offX[i]) {
							continue;
						}
						z = (nextInt(c.limit, c.pow2) + nextInt(c.limit, c.pow2)) / 2;
					} else {
						x = nextInt(c.limit, c.pow2);
						if (x != c.offX[i]) {
							continue;
						}
						z = nextInt(c.limit, c.pow2);
					}
					if (z != c.offZ[i]) {
						continue;
					}
					any = c.placementOnly || c.observation.matches(seed, c.observation.candidates().get(c.candidate[i]));
				}
				if (!any) {
					return false;
				}
			}
			return true;
		}

		/** Top 13 bits of {@code s1} are {@code (B + h * a) mod 2^13}; returns B and stores (s1 & MASK35) in {@code low35}. */
		private long low35;

		private int topBase(long lower35, long k) {
			long u = (lower35 + k) & MASK48;
			long l0 = (u & MASK35) ^ A;
			long x = (l0 * A + C) & MASK48;
			low35 = x & MASK35;
			return (int) ((x >>> 35) + (u >>> 35) * A) & TOP_MASK;
		}

		void pair(long lower35) {
			int ni = intervals.length;
			int nc = congruences.length;
			if (ni + nc == 0) {
				for (int h = 0; h < TOP; h++) {
					test(lower35 | (long) h << 35);
				}
				return;
			}
			// Anchor: first interval, else first congruence. t = top 13 bits of the anchor's s1.
			for (int i = 0; i < ni; i++) {
				base[i] = topBase(lower35, intervals[i].k);
			}
			for (int j = 0; j < nc; j++) {
				Congruence c = congruences[j];
				base[ni + j] = topBase(lower35, c.k);
				int r18 = (int) (low35 >>> 17);
				residue[j] = Math.floorMod((long) (c.off - r18) * c.inv18, c.m);
			}
			int anchor = base[0];
			int segments = 1;
			segStart[0] = 0;
			segEnd[0] = TOP;
			for (int i = 0; i < ni && segments > 0; i++) {
				int d = (base[i] - anchor) & TOP_MASK;
				int s = (intervals[i].lo - d) & TOP_MASK;
				int len = intervals[i].hi - intervals[i].lo + 1;
				segments = intersect(segments, s, len);
			}
			for (int g = 0; g < segments; g++) {
				enumerate(lower35, anchor, segStart[g], segEnd[g], ni, nc);
			}
		}

		/** Intersects the current segments with the circular arc {@code [s, s + len)} mod 8192. */
		private int intersect(int segments, int s, int len) {
			int out = 0;
			int arcs = s + len > TOP ? 2 : 1;
			for (int a = 0; a < arcs; a++) {
				int as = a == 0 ? s : 0;
				int ae = a == 0 ? Math.min(TOP, s + len) : s + len - TOP;
				for (int g = 0; g < segments; g++) {
					int lo = Math.max(as, segStart[g]);
					int hi = Math.min(ae, segEnd[g]);
					if (lo < hi && out < tmpStart.length) {
						tmpStart[out] = lo;
						tmpEnd[out] = hi;
						out++;
					}
				}
			}
			System.arraycopy(tmpStart, 0, segStart, 0, out);
			System.arraycopy(tmpEnd, 0, segEnd, 0, out);
			return out;
		}

		/** Lists anchor values in {@code [from, to)} meeting the congruences, splitting where each one wraps. */
		private void enumerate(long lower35, int anchor, int from, int to, int ni, int nc) {
			if (nc == 0) {
				for (int t = from; t < to; t++) {
					emit(lower35, anchor, t);
				}
				return;
			}
			// Congruence j sees t + d_j, which wraps past 8192 at t = 8192 - d_j: cut [from, to) there.
			int cutCount = 0;
			cuts[cutCount++] = from;
			for (int j = 0; j < nc; j++) {
				int d = (base[ni + j] - anchor) & TOP_MASK;
				shift[j] = d;
				int split = d == 0 ? TOP : TOP - d;
				if (split > from && split < to) {
					cuts[cutCount++] = split;
				}
			}
			cuts[cutCount++] = to;
			for (int i = 1; i < cutCount; i++) {
				int v = cuts[i];
				int k = i - 1;
				while (k >= 0 && cuts[k] > v) {
					cuts[k + 1] = cuts[k];
					k--;
				}
				cuts[k + 1] = v;
			}
			for (int p = 0; p + 1 < cutCount; p++) {
				int lo = cuts[p];
				int hi = cuts[p + 1];
				if (lo >= hi) {
					continue;
				}
				// t + d_j - 8192 * wrap == residue_j (mod m_j), folded into one progression with the precomputed CRT steps.
				long rem = 0;
				boolean ok = true;
				for (int j = 0; j < nc; j++) {
					Congruence c = congruences[j];
					int d = shift[j];
					int wrap = lo + d >= TOP ? 1 : 0;
					long r = Math.floorMod((long) residue[j] - d + (long) TOP * wrap, c.m);
					if (j == 0) {
						rem = r;
						continue;
					}
					long g = crtGcd[j];
					long diff = r - rem;
					if (g > 1 && Math.floorMod(diff, g) != 0) {
						ok = false;
						break;
					}
					long mg = c.m / g;
					if (mg > 1) {
						long k = Math.floorMod(diff / g % mg * crtInv[j], mg);
						rem += crtModulus[j] * k;
					}
				}
				if (!ok) {
					continue;
				}
				long mod = crtModulus[nc];
				long first = lo + Math.floorMod(rem - lo, mod);
				for (long t = first; t < hi; t += mod) {
					emit(lower35, anchor, (int) t);
				}
			}
		}

		private int splitPoint(int anchor, int ni, int j) {
			int d = (base[ni + j] - anchor) & TOP_MASK;
			return d == 0 ? TOP : TOP - d;
		}

		private void emit(long lower35, int anchor, int t) {
			int v = (t - anchor) & TOP_MASK;
			int h = (v * A_INV_13) & TOP_MASK;
			test(lower35 | (long) h << 35);
		}

		private void test(long seed) {
			if (check(seed) && hitCount < hits.length) {
				hits[hitCount++] = seed;
			}
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** Combines {@code x = r1 mod m1} and {@code x = r2 mod m2}; null if inconsistent. */
	static long[] crt(long r1, long m1, long r2, long m2) {
		long g = gcd(m1, m2);
		if (Math.floorMod(r2 - r1, g) != 0) {
			return null;
		}
		long l = m1 / g * m2;
		long m2g = m2 / g;
		if (m2g == 1) {
			return new long[] {Math.floorMod(r1, l), l};
		}
		long inv = inverseMod((int) Math.floorMod(m1 / g, m2g), (int) m2g);
		long k = Math.floorMod((r2 - r1) / g % m2g * inv, m2g);
		return new long[] {Math.floorMod(r1 + m1 * k, l), l};
	}

	private static long gcd(long a, long b) {
		while (b != 0) {
			long t = a % b;
			a = b;
			b = t;
		}
		return a;
	}
}
