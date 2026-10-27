package io.github.aabsolute22.seedcracker.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Self-consistency of the structure-seed cracker on synthetic observations (no Minecraft needed). */
class CrackerTest {
	private static final int THREADS = Math.max(1, Runtime.getRuntime().availableProcessors());

	/** The real start of {@code spread} in region (rx, rz), with the rotation if {@code withRotation}. */
	static Observation observe(long seed, Spread spread, int rx, int rz, boolean withRotation) {
		long p = spread.potentialChunk(seed, rx, rz);
		int cx = Packed.x(p);
		int cz = Packed.z(p);
		int rotation = withRotation ? Spread.startRotation(seed, cx, cz) : -1;
		return Observation.exact(spread, cx, cz, rotation);
	}

	/** A buried treasure near (cx, cz): the first chunk at or after it that passes the 1% draw. */
	static Observation treasure(long seed, int cx, int cz) {
		for (int x = cx; ; x++) {
			if (Spread.BURIED_TREASURES.frequencyAccepts(seed, x, cz)) {
				return Observation.exact(Spread.BURIED_TREASURES, x, cz);
			}
		}
	}

	static Observation outpost(long seed, int rx0, int rz0) {
		for (int r = 0; ; r++) {
			int rx = rx0 + r;
			long p = Spread.PILLAGER_OUTPOSTS.potentialChunk(seed, rx, rz0);
			int cx = Packed.x(p);
			int cz = Packed.z(p);
			if (Spread.PILLAGER_OUTPOSTS.isStructureChunk(seed, cx, cz) && !Spread.outpostExcluded(seed, cx, cz)) {
				return Observation.exact(Spread.PILLAGER_OUTPOSTS, cx, cz, -1);
			}
		}
	}

	private static long crackOne(List<Observation> obs, long seed, String name) {
		StructureSeedCracker cracker = new StructureSeedCracker(obs, 4096);
		long t0 = System.nanoTime();
		StructureSeedCracker.Result result = cracker.run(THREADS);
		long ms = (System.nanoTime() - t0) / 1_000_000;
		System.out.printf("%s: low bits %d (%d left), %.1f bits, %.2f cand/pair, est %.1f s/core, took %d ms, %d seeds%n", name,
				cracker.lowBits(), cracker.lowCandidates(), cracker.informationBits(), cracker.candidatesPerPair(),
				cracker.estimatedNanos() / 1e9, ms, result.seeds().size());
		assertFalse(result.overflow(), name + " overflow");
		assertTrue(result.seeds().contains(seed & LegacyRandom.MASK), name + " missed the real seed");
		return ms;
	}

	@Test
	void spreadMatchesReferenceRandom() {
		Random r = new Random(1);
		for (int i = 0; i < 2000; i++) {
			long seed = r.nextLong();
			int rx = r.nextInt(200) - 100;
			int rz = r.nextInt(200) - 100;
			Spread s = Spread.ALL.get(r.nextInt(Spread.ALL.size()));
			Random ref = new Random(rx * 341873128712L + rz * 132897987541L + seed + s.salt());
			int limit = s.limit();
			int x = s.triangular() ? (ref.nextInt(limit) + ref.nextInt(limit)) / 2 : ref.nextInt(limit);
			int z = s.triangular() ? (ref.nextInt(limit) + ref.nextInt(limit)) / 2 : ref.nextInt(limit);
			long p = s.potentialChunk(seed, rx, rz);
			assertEquals(rx * s.spacing() + x, Packed.x(p));
			assertEquals(rz * s.spacing() + z, Packed.z(p));
		}
	}

	@Test
	void helpers() {
		assertEquals(1L, (StructureSeedCracker.A * StructureSeedCracker.A_INV_13) & 8191);
		long[] c = StructureSeedCracker.crt(3, 23, 7, 25);
		assertNotNull(c);
		assertEquals(3, c[0] % 23);
		assertEquals(7, c[0] % 25);
		assertEquals(575, c[1]);
		c = StructureSeedCracker.crt(2, 5, 12, 25);
		assertNotNull(c);
		assertEquals(12, c[0]);
		assertEquals(25, c[1]);
		assertEquals(null, StructureSeedCracker.crt(1, 5, 12, 25));
		int t = StructureSeedCracker.floatThreshold(0.01f);
		assertTrue((t - 1) * 5.9604645E-8F < 0.01f);
		assertFalse(t * 5.9604645E-8F < 0.01f);
	}

	@Test
	void hashMatchesKnownVector() {
		// SHA-256 of eight zero bytes starts with af5570f5a1810b7a (little-endian long below).
		assertEquals(0x7a0b81a1f57055afL, SeedHash.obfuscate(0L));
		long seed = 0x1234_5678_9abc_def0L;
		List<Long> full = SeedHash.fullSeeds(seed & LegacyRandom.MASK, SeedHash.obfuscate(seed));
		assertEquals(List.of(seed), full);
	}

	@Test
	void templesAndVillages() {
		long seed = new Random(11).nextLong();
		List<Observation> obs = new ArrayList<>();
		obs.add(observe(seed, Spread.DESERT_PYRAMIDS, 1, 0, false));
		obs.add(observe(seed, Spread.JUNGLE_TEMPLES, -2, 3, false));
		obs.add(observe(seed, Spread.VILLAGES, 0, 0, true));
		obs.add(observe(seed, Spread.VILLAGES, 1, -1, true));
		obs.add(observe(seed, Spread.SHIPWRECKS, 2, 2, true));
		crackOne(obs, seed, "2 temples + 2 villages + shipwreck");
	}

	/** A structure as a player would add it by hand standing near a chunk corner: the 2x2 chunks around its start. */
	static Observation around(Observation exact) {
		Observation.Candidate c = exact.candidates().get(0);
		List<Observation.Candidate> list = new ArrayList<>();
		for (int dx = -1; dx <= 0; dx++) {
			for (int dz = 0; dz <= 1; dz++) {
				list.add(new Observation.Candidate(c.chunkX() + dx, c.chunkZ() + dz, -1));
			}
		}
		return new Observation(exact.spread(), list, -1, null, exact.outpost());
	}

	@Test
	void ambiguousManualStructuresBranch() {
		long seed = new Random(13).nextLong();
		List<Observation> obs = new ArrayList<>();
		obs.add(observe(seed, Spread.VILLAGES, 0, 0, true));
		obs.add(observe(seed, Spread.VILLAGES, 1, -1, true));
		obs.add(around(observe(seed, Spread.DESERT_PYRAMIDS, 1, 0, false)));
		obs.add(around(observe(seed, Spread.JUNGLE_TEMPLES, -2, 3, false)));
		obs.add(observe(seed, Spread.SHIPWRECKS, 2, 2, true));
		StructureSeedCracker whole = new StructureSeedCracker(obs, 4096);
		List<List<Observation>> plan = Branching.plan(obs, 4096);
		double branched = Branching.cost(plan, 4096);
		System.out.printf("ambiguous: whole %.1f s/core, %d branches %.2f s/core%n", whole.estimatedNanos() / 1e9, plan.size(), branched / 1e9);
		assertTrue(plan.size() > 1, "should branch");
		assertTrue(branched < whole.estimatedNanos() / 10, "branching should pay");
		java.util.Set<Long> seeds = new java.util.TreeSet<>();
		for (List<Observation> branch : plan) {
			StructureSeedCracker.Result r = new StructureSeedCracker(branch, 4096).run(THREADS);
			assertFalse(r.overflow());
			for (long s : r.seeds()) {
				if (whole.accepts(s)) {
					seeds.add(s);
				}
			}
		}
		assertTrue(seeds.contains(seed & LegacyRandom.MASK), "branches missed the real seed");
		assertTrue(seeds.size() < 50, "too many seeds: " + seeds.size());
	}

	@Test
	void netherOnlyWithCongruences() {
		long seed = new Random(12).nextLong();
		List<Observation> obs = new ArrayList<>();
		obs.add(observe(seed, Spread.RUINED_PORTALS, 0, 0, false));
		obs.add(observe(seed, Spread.NETHER_COMPLEXES, 1, 0, false));
		obs.add(observe(seed, Spread.NETHER_COMPLEXES, 0, 1, false));
		obs.add(observe(seed, Spread.NETHER_COMPLEXES, -1, 0, false));
		obs.add(observe(seed, Spread.RUINED_PORTALS, -1, -1, false));
		StructureSeedCracker cracker = new StructureSeedCracker(obs, 4096);
		System.out.printf("nether-only: est %.0f s/core, %.2f cand/pair, %.3g pairs%n", cracker.estimatedNanos() / 1e9,
				cracker.candidatesPerPair(), cracker.pairs());
		// The full run is minutes; spot-check that the real seed's (low, mid) pair yields it.
		assertTrue(cracker.accepts(seed));
	}

	@Test
	void buriedTreasureEnumeration() {
		long seed = new Random(13).nextLong();
		List<Observation> obs = new ArrayList<>();
		obs.add(treasure(seed, 10, 10));
		obs.add(treasure(seed, -40, 7));
		obs.add(treasure(seed, 3, -60));
		obs.add(observe(seed, Spread.SWAMP_HUTS, 0, 0, false));
		obs.add(observe(seed, Spread.VILLAGES, 2, 0, false));
		obs.add(observe(seed, Spread.OCEAN_RUINS, 1, 1, true));
		crackOne(obs, seed, "3 treasures + hut + village + ruin");
	}

	@Test
	void outpostsAndAncientCity() {
		long seed = new Random(14).nextLong();
		List<Observation> obs = new ArrayList<>();
		obs.add(outpost(seed, 0, 0));
		obs.add(outpost(seed, 5, 3));
		obs.add(observe(seed, Spread.ANCIENT_CITIES, 0, 0, true));
		obs.add(observe(seed, Spread.IGLOOS, 1, 1, true));
		obs.add(observe(seed, Spread.TRAIL_RUINS, 0, 0, true));
		crackOne(obs, seed, "2 outposts + ancient city + igloo + trail ruins");
	}

	@Test
	void netherPickAndRotation() {
		long seed = new Random(15).nextLong();
		Observation o = observe(seed, Spread.NETHER_COMPLEXES, 3, 4, false);
		int cx = o.candidates().get(0).chunkX();
		int cz = o.candidates().get(0).chunkZ();
		int pick = Spread.firstWeightedPick(seed, cx, cz, Observation.NETHER_WEIGHTS);
		assertTrue(o.withPick(pick, Observation.NETHER_WEIGHTS).matches(seed));
		assertFalse(o.withPick(1 - pick, Observation.NETHER_WEIGHTS).matches(seed));
	}
}
