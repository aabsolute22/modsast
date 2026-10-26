package io.github.aabsolute22.endcityfinder.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Recovers 48-bit structure seeds from the pillar layout and observed End city starts.
 * <p>
 * The pillars leave 2^32 seeds per spike key (see {@link EndSpikes#lowSeedBits}); each of those is checked
 * against the city placement ({@code nextInt} offsets of the region random) and, when known, the city rotation.
 * The hot path is written with plain locals and a constant divisor so the JIT reliably compiles it well.
 */
public final class SeedCracker {
	private static final long MULT = LegacyRandom.MULTIPLIER;
	private static final long ADD = LegacyRandom.ADDEND;
	private static final long MASK = LegacyRandom.MASK;
	private static final int BLOCK = 64;
	/** spacing - separation of End cities; specialised so {@code % 9} compiles to a multiply. */
	private static final int END_LIMIT = 9;

	/** A possible start chunk of an observed city; {@code rotation} is -1 when unknown. */
	public record Candidate(int chunkX, int chunkZ, int rotation) {
	}

	/** One observed city: it started in one of these chunks. */
	public record City(List<Candidate> candidates) {
	}

	public record Result(List<Long> seeds, boolean overflow, boolean cancelled) {
	}

	/** Candidates of one city grouped by placement region. */
	private record CityCheck(long[] regionK, int[] regionXMask, int[] regionStart, int[] offsets, int[] rotation,
			int[] chunkX, int[] chunkZ, double selectivity) {
	}

	private final Placement placement;
	private final boolean fastPath;
	private final int[] keys;
	private final CityCheck[] checks;
	private final int maxSeeds;
	private final AtomicBoolean cancel = new AtomicBoolean();
	private final AtomicLong done = new AtomicLong();
	private final long total;

	public SeedCracker(Placement placement, int[] keys, List<City> cities, int maxSeeds) {
		this.placement = placement;
		this.fastPath = placement.triangular() && placement.limit() == END_LIMIT;
		this.keys = keys.clone();
		this.maxSeeds = maxSeeds;
		List<CityCheck> list = new ArrayList<>();
		for (City city : cities) {
			CityCheck check = compile(city);
			if (check != null) {
				list.add(check);
			}
		}
		list.sort(Comparator.comparingDouble(CityCheck::selectivity));
		this.checks = list.toArray(new CityCheck[0]);
		this.total = (long) this.keys.length * (1 << 16);
	}

	public int usableCities() {
		return checks.length;
	}

	/** Expected number of seeds that pass by chance; above a few thousand the search should not start. */
	public double expectedSeeds() {
		double e = (double) (1L << 32) * keys.length;
		for (CityCheck c : checks) {
			e *= Math.min(1.0, c.selectivity());
		}
		return e;
	}

	public double progress() {
		return total == 0 ? 1.0 : (double) done.get() / total;
	}

	public void cancel() {
		cancel.set(true);
	}

	private double offsetProbability(int offset) {
		int limit = placement.limit();
		if (!placement.triangular()) {
			return 1.0 / limit;
		}
		int ways = 0;
		for (int a = 0; a < limit; a++) {
			for (int b = 0; b < limit; b++) {
				if ((a + b) / 2 == offset) {
					ways++;
				}
			}
		}
		return (double) ways / (limit * limit);
	}

	private CityCheck compile(City city) {
		List<Long> regions = new ArrayList<>();
		List<List<int[]>> byRegion = new ArrayList<>();
		double selectivity = 0;
		for (Candidate c : city.candidates()) {
			int rx = placement.region(c.chunkX());
			int rz = placement.region(c.chunkZ());
			int ox = c.chunkX() - rx * placement.spacing();
			int oz = c.chunkZ() - rz * placement.spacing();
			if (ox >= placement.limit() || oz >= placement.limit()) {
				continue;
			}
			long k = (long) rx * 341873128712L + (long) rz * 132897987541L + (long) placement.salt();
			int index = regions.indexOf(k);
			if (index < 0) {
				index = regions.size();
				regions.add(k);
				byRegion.add(new ArrayList<>());
			}
			byRegion.get(index).add(new int[] {ox << 8 | oz, c.rotation(), c.chunkX(), c.chunkZ()});
			selectivity += offsetProbability(ox) * offsetProbability(oz) * (c.rotation() >= 0 ? 0.25 : 1.0);
		}
		if (regions.isEmpty()) {
			return null;
		}
		int n = byRegion.stream().mapToInt(List::size).sum();
		long[] regionK = new long[regions.size()];
		int[] regionXMask = new int[regions.size()];
		int[] regionStart = new int[regions.size() + 1];
		int[] offsets = new int[n];
		int[] rotation = new int[n];
		int[] chunkX = new int[n];
		int[] chunkZ = new int[n];
		int i = 0;
		for (int r = 0; r < regions.size(); r++) {
			regionK[r] = regions.get(r);
			regionStart[r] = i;
			for (int[] c : byRegion.get(r)) {
				offsets[i] = c[0];
				rotation[i] = c[1];
				chunkX[i] = c[2];
				chunkZ[i] = c[3];
				regionXMask[r] |= 1 << (c[0] >>> 8);
				i++;
			}
		}
		regionStart[regions.size()] = n;
		return new CityCheck(regionK, regionXMask, regionStart, offsets, rotation, chunkX, chunkZ, selectivity);
	}

	/**
	 * Packed {@code x << 8 | z} region offsets for End city placement, or -1 as soon as x rules out every
	 * candidate. Falls back to the general path in the (2^-28 rare) case {@code nextInt} would re-roll.
	 */
	private int endOffsets(long seed, long k, int xMask) {
		long s = ((seed + k) ^ MULT) & MASK;
		s = (s * MULT + ADD) & MASK;
		int r = (int) (s >>> 17);
		int a = r % END_LIMIT;
		if (r - a + (END_LIMIT - 1) < 0) {
			return generalOffsets(seed, k, xMask);
		}
		s = (s * MULT + ADD) & MASK;
		r = (int) (s >>> 17);
		int b = r % END_LIMIT;
		if (r - b + (END_LIMIT - 1) < 0) {
			return generalOffsets(seed, k, xMask);
		}
		int x = (a + b) >> 1;
		if ((xMask >>> x & 1) == 0) {
			return -1;
		}
		s = (s * MULT + ADD) & MASK;
		r = (int) (s >>> 17);
		a = r % END_LIMIT;
		if (r - a + (END_LIMIT - 1) < 0) {
			return generalOffsets(seed, k, xMask);
		}
		s = (s * MULT + ADD) & MASK;
		r = (int) (s >>> 17);
		b = r % END_LIMIT;
		if (r - b + (END_LIMIT - 1) < 0) {
			return generalOffsets(seed, k, xMask);
		}
		return x << 8 | (a + b) >> 1;
	}

	private int generalOffsets(long seed, long k, int xMask) {
		LegacyRandom random = new LegacyRandom((seed + k));
		int limit = placement.limit();
		boolean triangular = placement.triangular();
		int x = triangular ? (random.nextInt(limit) + random.nextInt(limit)) / 2 : random.nextInt(limit);
		if ((xMask >>> x & 1) == 0) {
			return -1;
		}
		int z = triangular ? (random.nextInt(limit) + random.nextInt(limit)) / 2 : random.nextInt(limit);
		return x << 8 | z;
	}

	private static long step(long s) {
		return (s * MULT + ADD) & MASK;
	}

	/** {@code nextInt(4)} of {@code setLargeFeatureSeed(seed, chunkX, chunkZ)}, given its two nextLong values. */
	private static int rotation(long seed, long a, long b, int chunkX, int chunkZ) {
		long s = (((long) chunkX * a ^ (long) chunkZ * b ^ seed) ^ MULT) & MASK;
		return (int) (step(s) >>> 46);
	}

	boolean check(long seed) {
		long a = 0;
		long b = 0;
		boolean haveAB = false;
		CityCheck[] checks = this.checks;
		for (int ci = 0; ci < checks.length; ci++) {
			CityCheck c = checks[ci];
			long[] regionK = c.regionK;
			boolean ok = false;
			for (int r = 0; r < regionK.length && !ok; r++) {
				int off = fastPath ? endOffsets(seed, regionK[r], c.regionXMask[r]) : generalOffsets(seed, regionK[r], c.regionXMask[r]);
				if (off < 0) {
					continue;
				}
				int end = c.regionStart[r + 1];
				for (int i = c.regionStart[r]; i < end; i++) {
					if (c.offsets[i] != off) {
						continue;
					}
					int rot = c.rotation[i];
					if (rot >= 0) {
						if (!haveAB) {
							long t1 = step((seed ^ MULT) & MASK);
							long t2 = step(t1);
							long t3 = step(t2);
							long t4 = step(t3);
							a = ((long) (int) (t1 >>> 16) << 32) + (int) (t2 >>> 16);
							b = ((long) (int) (t3 >>> 16) << 32) + (int) (t4 >>> 16);
							haveAB = true;
						}
						if (rotation(seed, a, b, c.chunkX[i], c.chunkZ[i]) != rot) {
							continue;
						}
					}
					ok = true;
					break;
				}
			}
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	/** Checks all 2^16 seeds with these low 32 bits. Returns true when the result limit overflowed. */
	private boolean scanLow(long low, ConcurrentLinkedQueue<Long> found, AtomicInteger foundCount) {
		for (long hi = 0; hi < 1 << 16; hi++) {
			long seed = hi << 32 | low;
			if (check(seed)) {
				found.add(seed);
				if (foundCount.incrementAndGet() > maxSeeds) {
					return true;
				}
			}
		}
		return false;
	}

	/** Runs the search on {@code threads} threads and blocks until it finishes or is cancelled. */
	public Result run(int threads) {
		ConcurrentLinkedQueue<Long> found = new ConcurrentLinkedQueue<>();
		AtomicInteger foundCount = new AtomicInteger();
		AtomicBoolean overflow = new AtomicBoolean();
		AtomicInteger nextBlock = new AtomicInteger();
		int blocksPerKey = (1 << 16) / BLOCK;
		int totalBlocks = keys.length * blocksPerKey;
		int[][] lowCache = new int[keys.length][];
		Runnable worker = () -> {
			while (!cancel.get() && !overflow.get()) {
				int block = nextBlock.getAndIncrement();
				if (block >= totalBlocks) {
					return;
				}
				int keyIndex = block / blocksPerKey;
				int[] lows;
				synchronized (lowCache) {
					lows = lowCache[keyIndex];
					if (lows == null) {
						lows = EndSpikes.lowSeedBits(keys[keyIndex]);
						lowCache[keyIndex] = lows;
					}
				}
				int u0 = (block % blocksPerKey) * BLOCK;
				for (int u = u0; u < u0 + BLOCK; u++) {
					if (scanLow(lows[u] & 0xFFFFFFFFL, found, foundCount)) {
						overflow.set(true);
						return;
					}
					done.incrementAndGet();
					if (cancel.get()) {
						return;
					}
				}
			}
		};
		List<Thread> pool = new ArrayList<>();
		for (int i = 0; i < Math.max(1, threads); i++) {
			Thread t = new Thread(worker, "endcityfinder-cracker-" + i);
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			t.start();
			pool.add(t);
		}
		for (Thread t : pool) {
			try {
				t.join();
			} catch (InterruptedException e) {
				cancel.set(true);
				Thread.currentThread().interrupt();
			}
		}
		List<Long> seeds = new ArrayList<>(found);
		Collections.sort(seeds);
		return new Result(seeds, overflow.get(), cancel.get());
	}
}
