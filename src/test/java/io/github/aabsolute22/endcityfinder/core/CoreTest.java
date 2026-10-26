package io.github.aabsolute22.endcityfinder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Self-consistency tests of the core that do not need Minecraft. */
class CoreTest {
	@Test
	void legacyRandomMatchesJavaRandom() {
		Random rnd = new Random(42);
		for (int i = 0; i < 100; i++) {
			long seed = rnd.nextLong();
			Random java = new Random(seed);
			LegacyRandom ours = new LegacyRandom(seed);
			for (int j = 0; j < 50; j++) {
				assertEquals(java.nextInt(9), ours.nextInt(9));
				assertEquals(java.nextInt(16), ours.nextInt(16));
				assertEquals(java.nextLong(), ours.nextLong());
				assertEquals(java.nextDouble(), ours.nextDouble());
				assertEquals(java.nextBoolean(), ours.nextBoolean());
			}
			LegacyRandom skipped = new LegacyRandom(seed);
			skipped.skip(1234);
			LegacyRandom stepped = new LegacyRandom(seed);
			for (int j = 0; j < 1234; j++) {
				stepped.nextInt();
			}
			assertEquals(stepped.state(), skipped.state());
		}
	}

	@Test
	void spikeLowBitsContainTheSeed() {
		Random rnd = new Random(7);
		for (int i = 0; i < 20; i++) {
			long seed = rnd.nextLong();
			int[] lows = EndSpikes.lowSeedBits(EndSpikes.key(seed));
			boolean found = false;
			for (int low : lows) {
				found |= low == (int) seed;
				assertEquals(EndSpikes.key(seed), EndSpikes.key(low & 0xFFFFFFFFL));
			}
			assertTrue(found);
		}
	}

	@Test
	void baseFloorLocatorRoundTrips() {
		for (int cx = -40; cx < 40; cx += 7) {
			for (int cz = -40; cz < 40; cz += 9) {
				for (Rotation r : Rotation.values()) {
					int[] f = BaseFloorLocator.footprint(cx, cz, r);
					BaseFloorLocator.Result result = BaseFloorLocator.locate(f[0], f[1], f[2], f[3]);
					assertTrue(result.exact());
					assertEquals(List.of(new SeedCracker.Candidate(cx, cz, r.ordinal())), result.candidates());
				}
			}
		}
	}

	@Test
	void crackerFindsSeedFromFourCitiesWithRotation() {
		long seed = 0x1234_5678_9ABCL;
		EndWorld world = new EndWorld(seed);
		List<EndCity> cities = new ArrayList<>(world.citiesNear(0, 0, 3000));
		cities.sort(Comparator.comparingDouble(c -> Math.hypot(c.x(), c.z())));
		List<SeedCracker.City> observed = new ArrayList<>();
		for (EndCity c : cities.subList(0, 4)) {
			observed.add(new SeedCracker.City(List.of(new SeedCracker.Candidate(c.chunkX(), c.chunkZ(), c.rotation().ordinal()))));
		}
		int[] keys = EndSpikes.matchingKeys(EndSpikes.sizes(EndSpikes.key(seed)));
		SeedCracker.Result result = new SeedCracker(Placement.END_CITIES, keys, observed, 10_000).run(Runtime.getRuntime().availableProcessors());
		assertTrue(result.seeds().contains(seed));
		assertEquals(List.of(seed), new CandidateFilter(Placement.END_CITIES, observed, List.of(), List.of()).filter(result.seeds()));
	}
}
