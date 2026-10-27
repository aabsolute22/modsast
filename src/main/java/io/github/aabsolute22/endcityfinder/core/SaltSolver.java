package io.github.aabsolute22.endcityfinder.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Finds the seed when the End city placement salt was changed (Spigot's {@code seed-endcity}).
 * <p>
 * Placement only ever sees {@code seed + salt}, but the city rotation, the biomes, the terrain and the pillars
 * depend on the seed alone. So the seed is found first from those (stage A), and only then the 32-bit salt is
 * brute-forced against the city positions (stage B).
 */
public final class SaltSolver {
	private static final long MULT = LegacyRandom.MULTIPLIER;
	private static final long ADD = LegacyRandom.ADDEND;
	private static final long MASK = LegacyRandom.MASK;
	private static final int BLOCK = 64;
	private static final int MAX_STAGE_A = 2_000_000;
	private static final int MAX_SEEDS = 16;
	private static final int MAX_SALTS_PER_SEED = 4096;

	/** A seed together with the salt that explains the observed city positions. */
	public record Solution(long seed, int salt) {
		public Placement placement(Placement base) {
			return new Placement(base.spacing(), base.separation(), salt, base.triangular());
		}
	}

	public record Result(List<Solution> solutions, String problem, boolean cancelled) {
	}

	private final Placement base;
	private final int[] keys;
	private final List<SeedCracker.City> cities;
	private final List<Long> emptyChunks;
	private final List<CandidateFilter.TerrainSample> samples;
	/** Exact cities with a known rotation, the only ones usable before the salt is known: {chunkX, chunkZ, rotation}. */
	private final int[][] rotated;
	private final AtomicBoolean cancel = new AtomicBoolean();
	private final AtomicBoolean overflow = new AtomicBoolean();
	private final AtomicLong done = new AtomicLong();
	private volatile long total = 1;

	public SaltSolver(Placement base, int[] keys, List<SeedCracker.City> cities, List<Long> emptyChunks,
			List<CandidateFilter.TerrainSample> samples) {
		this.base = base;
		this.keys = keys.clone();
		this.cities = cities;
		this.emptyChunks = emptyChunks;
		this.samples = samples;
		List<int[]> list = new ArrayList<>();
		for (SeedCracker.City city : cities) {
			if (city.candidates().size() == 1 && city.candidates().get(0).rotation() >= 0) {
				SeedCracker.Candidate c = city.candidates().get(0);
				list.add(new int[] {c.chunkX(), c.chunkZ(), c.rotation()});
			}
		}
		this.rotated = list.toArray(new int[0][]);
	}

	/** Cities with a known start chunk and rotation; at least 3 are needed for a search of reasonable length. */
	public int rotatedCities() {
		return rotated.length;
	}

	public double progress() {
		return Math.min(1.0, (double) done.get() / total);
	}

	public void cancel() {
		cancel.set(true);
	}

	private static long step(long s) {
		return (s * MULT + ADD) & MASK;
	}

	/** Rotation filter, then the cache-free biome check of every observed city. */
	private boolean stageA(long seed) {
		long t1 = step((seed ^ MULT) & MASK);
		long t2 = step(t1);
		long t3 = step(t2);
		long t4 = step(t3);
		long a = ((long) (int) (t1 >>> 16) << 32) + (int) (t2 >>> 16);
		long b = ((long) (int) (t3 >>> 16) << 32) + (int) (t4 >>> 16);
		for (int[] c : rotated) {
			long s = (((long) c[0] * a ^ (long) c[1] * b ^ seed) ^ MULT) & MASK;
			if ((int) (step(s) >>> 46) != c[2]) {
				return false;
			}
		}
		SimplexNoise noise = EndIslands.noise(seed);
		for (SeedCracker.City city : cities) {
			boolean any = false;
			for (SeedCracker.Candidate c : city.candidates()) {
				if (EndIslands.allowsEndCity(noise, c.chunkX(), c.chunkZ())) {
					any = true;
					break;
				}
			}
			if (!any) {
				return false;
			}
		}
		return true;
	}

	/** Every observed city must really generate (terrain height, rotation), and the terrain must match. */
	private boolean stageAExact(long seed) {
		EndWorld world = new EndWorld(seed, base);
		for (SeedCracker.City city : cities) {
			boolean ok = false;
			for (SeedCracker.Candidate c : city.candidates()) {
				EndCity generated = world.cityAt(c.chunkX(), c.chunkZ());
				if (generated != null && (c.rotation() < 0 || generated.rotation().ordinal() == c.rotation())) {
					ok = true;
					break;
				}
			}
			if (!ok) {
				return false;
			}
		}
		return CandidateFilter.terrainMatches(world, samples);
	}

	private void parallel(int threads, int blocks, java.util.function.IntConsumer body) {
		AtomicInteger next = new AtomicInteger();
		List<Thread> pool = new ArrayList<>();
		for (int i = 0; i < Math.max(1, threads); i++) {
			Thread t = new Thread(() -> {
				while (!cancel.get()) {
					int block = next.getAndIncrement();
					if (block >= blocks) {
						return;
					}
					body.accept(block);
				}
			}, "endcityfinder-salt-" + i);
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
	}

	/** Stage A: seeds that fit the pillars, rotations, biomes and terrain, ignoring city positions. */
	List<Long> findSeeds(int threads) {
		int blocksPerKey = (1 << 16) / BLOCK;
		total = (long) keys.length * (1 << 16);
		done.set(0);
		ConcurrentLinkedQueue<Long> found = new ConcurrentLinkedQueue<>();
		AtomicInteger count = new AtomicInteger();
		int[][] lowCache = new int[keys.length][];
		parallel(threads, keys.length * blocksPerKey, block -> {
			if (overflow.get()) {
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
			for (int u = u0; u < u0 + BLOCK && !cancel.get(); u++) {
				long low = lows[u] & 0xFFFFFFFFL;
				for (long hi = 0; hi < 1 << 16; hi++) {
					long seed = hi << 32 | low;
					if (stageA(seed)) {
						found.add(seed);
						if (count.incrementAndGet() > MAX_STAGE_A) {
							overflow.set(true);
							return;
						}
					}
				}
				done.incrementAndGet();
			}
		});
		List<Long> seeds = new ArrayList<>(found.parallelStream().filter(this::stageAExact).toList());
		Collections.sort(seeds);
		return seeds;
	}

	/** Stage B: every salt that puts the observed cities where they are, then the full filter. */
	List<Integer> findSalts(long seed, int threads) {
		List<SeedCracker.City> positionsOnly = new ArrayList<>();
		for (SeedCracker.City city : cities) {
			List<SeedCracker.Candidate> list = new ArrayList<>();
			for (SeedCracker.Candidate c : city.candidates()) {
				// Rotation depends on the seed, not on seed + salt, so it is checked separately.
				list.add(new SeedCracker.Candidate(c.chunkX(), c.chunkZ(), -1));
			}
			positionsOnly.add(new SeedCracker.City(list));
		}
		SeedCracker positions = new SeedCracker(new Placement(base.spacing(), base.separation(), 0, base.triangular()), new int[0], positionsOnly, 0);
		ConcurrentLinkedQueue<Integer> found = new ConcurrentLinkedQueue<>();
		AtomicInteger count = new AtomicInteger();
		int blocks = 1 << 16;
		parallel(threads, blocks, block -> {
			long first = ((long) block << 16) + Integer.MIN_VALUE;
			for (long salt = first; salt < first + (1 << 16); salt++) {
				if (positions.check((seed + salt) & MASK) && count.incrementAndGet() <= MAX_SALTS_PER_SEED) {
					found.add((int) salt);
				}
			}
			done.incrementAndGet();
		});
		List<Integer> salts = new ArrayList<>();
		for (int salt : found) {
			Placement placement = new Placement(base.spacing(), base.separation(), salt, base.triangular());
			if (new CandidateFilter(placement, cities, emptyChunks, List.of()).check(seed).accepted()) {
				salts.add(salt);
			}
		}
		Collections.sort(salts);
		return salts;
	}

	public Result run(int threads) {
		if (rotated.length < 3) {
			return new Result(List.of(), "нужно хотя бы 3 города с точным чанком и поворотом (пролёт над городом или кнопка L в нижнем зале)", false);
		}
		List<Long> seeds = findSeeds(threads);
		if (cancel.get()) {
			return new Result(List.of(), null, true);
		}
		if (overflow.get()) {
			return new Result(List.of(), "слишком много вариантов, найди ещё города (пролётом, чтобы был известен поворот)", false);
		}
		if (seeds.isEmpty()) {
			return new Result(List.of(), "ни один сид не подходит даже без учёта соли, проверь города", false);
		}
		if (seeds.size() > MAX_SEEDS) {
			return new Result(List.of(), "слишком много вариантов сида (" + seeds.size() + "), найди ещё города или полетай, чтобы набрать рельеф", false);
		}
		done.set(0);
		total = (long) seeds.size() * (1 << 16);
		List<Solution> solutions = new ArrayList<>();
		for (long seed : seeds) {
			for (int salt : findSalts(seed, threads)) {
				solutions.add(new Solution(seed, salt));
			}
			if (cancel.get()) {
				return new Result(List.of(), null, true);
			}
		}
		if (solutions.isEmpty()) {
			return new Result(List.of(), "нашёлся сид, но ни одна соль не объясняет позиции городов, проверь города", false);
		}
		return new Result(solutions, null, false);
	}
}
