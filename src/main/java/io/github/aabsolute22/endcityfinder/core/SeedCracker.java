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
 */
public final class SeedCracker {
	private static final long MULT = LegacyRandom.MULTIPLIER;
	private static final long ADD = LegacyRandom.ADDEND;
	private static final long MASK = LegacyRandom.MASK;
	private static final int BLOCK = 64;

	/** A possible start chunk of an observed city; {@code rotation} is -1 when unknown. */
	public record Candidate(int chunkX, int chunkZ, int rotation) {
	}

	/** One observed city: it started in one of these chunks. */
	public record City(List<Candidate> candidates) {
	}

	public record Result(List<Long> seeds, boolean overflow, boolean cancelled) {
	}

	private static final class CityCheck {
		final long[] regionK;
		final int[] candRegion;
		final int[] candOffsets;
		final int[] candRotation;
		final int[] candX;
		final int[] candZ;
		final double selectivity;

		CityCheck(long[] regionK, int[] candRegion, int[] candOffsets, int[] candRotation, int[] candX, int[] candZ, double selectivity) {
			this.regionK = regionK;
			this.candRegion = candRegion;
			this.candOffsets = candOffsets;
			this.candRotation = candRotation;
			this.candX = candX;
			this.candZ = candZ;
			this.selectivity = selectivity;
		}
	}

	private final Placement placement;
	private final int[] keys;
	private final CityCheck[] checks;
	private final int maxSeeds;
	private final AtomicBoolean cancel = new AtomicBoolean();
	private final AtomicLong done = new AtomicLong();
	private final long total;

	public SeedCracker(Placement placement, int[] keys, List<City> cities, int maxSeeds) {
		this.placement = placement;
		this.keys = keys.clone();
		this.maxSeeds = maxSeeds;
		List<CityCheck> list = new ArrayList<>();
		for (City city : cities) {
			CityCheck check = compile(city);
			if (check != null) {
				list.add(check);
			}
		}
		list.sort(Comparator.comparingDouble(c -> c.selectivity));
		this.checks = list.toArray(new CityCheck[0]);
		this.total = (long) this.keys.length * (1 << 16);
	}

	public int usableCities() {
		return checks.length;
	}

	/** Expected number of false positives per key; above a few thousand the search should not start. */
	public double expectedSeeds() {
		double e = (double) (1L << 32) * keys.length;
		for (CityCheck c : checks) {
			e *= Math.min(1.0, c.selectivity);
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
		List<int[]> cands = new ArrayList<>();
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
			}
			cands.add(new int[] {index, ox << 8 | oz, c.rotation(), c.chunkX(), c.chunkZ()});
			selectivity += offsetProbability(ox) * offsetProbability(oz) * (c.rotation() >= 0 ? 0.25 : 1.0);
		}
		if (cands.isEmpty()) {
			return null;
		}
		int n = cands.size();
		int[] candRegion = new int[n];
		int[] candOffsets = new int[n];
		int[] candRotation = new int[n];
		int[] candX = new int[n];
		int[] candZ = new int[n];
		for (int i = 0; i < n; i++) {
			int[] c = cands.get(i);
			candRegion[i] = c[0];
			candOffsets[i] = c[1];
			candRotation[i] = c[2];
			candX[i] = c[3];
			candZ[i] = c[4];
		}
		long[] regionK = new long[regions.size()];
		for (int i = 0; i < regionK.length; i++) {
			regionK[i] = regions.get(i);
		}
		return new CityCheck(regionK, candRegion, candOffsets, candRotation, candX, candZ, selectivity);
	}

	private static int nextInt(long[] st, int bound) {
		long s = (st[0] * MULT + ADD) & MASK;
		int bits = (int) (s >>> 17);
		if ((bound & (bound - 1)) == 0) {
			st[0] = s;
			return (int) ((long) bound * bits >> 31);
		}
		int value = bits % bound;
		while (bits - value + (bound - 1) < 0) {
			s = (s * MULT + ADD) & MASK;
			bits = (int) (s >>> 17);
			value = bits % bound;
		}
		st[0] = s;
		return value;
	}

	private int offsets(long seed, long k, long[] st) {
		st[0] = ((seed + k) ^ MULT) & MASK;
		int limit = placement.limit();
		int x;
		int z;
		if (placement.triangular()) {
			x = (nextInt(st, limit) + nextInt(st, limit)) / 2;
			z = (nextInt(st, limit) + nextInt(st, limit)) / 2;
		} else {
			x = nextInt(st, limit);
			z = nextInt(st, limit);
		}
		return x << 8 | z;
	}

	private static long step(long s) {
		return (s * MULT + ADD) & MASK;
	}

	private static int rotation(long seed, long a, long b, int chunkX, int chunkZ) {
		long s = (((long) chunkX * a ^ (long) chunkZ * b ^ seed) ^ MULT) & MASK;
		return (int) (step(s) >>> 46);
	}

	private boolean check(long seed, long[] st, int[] offs) {
		long a = 0;
		long b = 0;
		boolean ab = false;
		for (CityCheck c : checks) {
			for (int r = 0; r < c.regionK.length; r++) {
				offs[r] = offsets(seed, c.regionK[r], st);
			}
			boolean ok = false;
			for (int i = 0; i < c.candRegion.length; i++) {
				if (offs[c.candRegion[i]] != c.candOffsets[i]) {
					continue;
				}
				if (c.candRotation[i] >= 0) {
					if (!ab) {
						long t1 = step((seed ^ MULT) & MASK);
						long t2 = step(t1);
						long t3 = step(t2);
						long t4 = step(t3);
						a = ((long) (int) (t1 >>> 16) << 32) + (int) (t2 >>> 16);
						b = ((long) (int) (t3 >>> 16) << 32) + (int) (t4 >>> 16);
						ab = true;
					}
					if (rotation(seed, a, b, c.candX[i], c.candZ[i]) != c.candRotation[i]) {
						continue;
					}
				}
				ok = true;
				break;
			}
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	/** Runs the search on {@code threads} threads and blocks until it finishes or is cancelled. */
	public Result run(int threads) {
		ConcurrentLinkedQueue<Long> found = new ConcurrentLinkedQueue<>();
		AtomicInteger foundCount = new AtomicInteger();
		AtomicBoolean overflow = new AtomicBoolean();
		AtomicInteger nextBlock = new AtomicInteger();
		int blocksPerKey = (1 << 16) / BLOCK;
		int totalBlocks = keys.length * blocksPerKey;
		int maxRegions = 1;
		for (CityCheck c : checks) {
			maxRegions = Math.max(maxRegions, c.regionK.length);
		}
		final int regionsScratch = maxRegions;
		int[][] lowCache = new int[keys.length][];
		Object lock = new Object();
		Runnable worker = () -> {
			long[] st = new long[1];
			int[] offs = new int[regionsScratch];
			while (!cancel.get() && !overflow.get()) {
				int block = nextBlock.getAndIncrement();
				if (block >= totalBlocks) {
					return;
				}
				int keyIndex = block / blocksPerKey;
				int[] lows;
				synchronized (lock) {
					lows = lowCache[keyIndex];
					if (lows == null) {
						lows = EndSpikes.lowSeedBits(keys[keyIndex]);
						lowCache[keyIndex] = lows;
					}
				}
				int u0 = (block % blocksPerKey) * BLOCK;
				for (int u = u0; u < u0 + BLOCK; u++) {
					long low = lows[u] & 0xFFFFFFFFL;
					for (long hi = 0; hi < 1 << 16; hi++) {
						long seed = hi << 32 | low;
						if (check(seed, st, offs)) {
							found.add(seed);
							if (foundCount.incrementAndGet() > maxSeeds) {
								overflow.set(true);
								return;
							}
						}
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
