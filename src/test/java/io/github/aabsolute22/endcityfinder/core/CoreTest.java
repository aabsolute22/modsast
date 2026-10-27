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

	@Test
	void shareTokensRoundTripInsideChatFormatting() {
		String seedToken = ShareCodec.seed(123456789L, -424242);
		ShareCodec.Message m = ShareCodec.find("Friend whispers to you: " + seedToken).orElseThrow();
		assertEquals(new ShareCodec.Seed(123456789L, -424242), m);
		long[] cities = new long[100];
		for (int i = 0; i < cities.length; i++) {
			cities[i] = Placement.pack(i * 37 - 2000, 1500 - i * 23);
		}
		List<String> tokens = ShareCodec.visited(cities);
		assertTrue(tokens.size() > 1);
		List<Long> decoded = new ArrayList<>();
		for (String token : tokens) {
			assertTrue(token.length() < 200);
			for (long c : ((ShareCodec.Visited) ShareCodec.find("[me -> Friend] " + token).orElseThrow()).cities()) {
				decoded.add(c);
			}
		}
		assertEquals(cities.length, decoded.size());
		for (int i = 0; i < cities.length; i++) {
			assertEquals(cities[i], decoded.get(i));
		}
		assertTrue(ShareCodec.find(seedToken.replace("#", "#x")).isEmpty(), "corrupted token must be rejected");
	}

	@Test
	void cacheFreeBiomeCheckMatchesFullBiome() {
		Random rnd = new Random(11);
		for (int s = 0; s < 5; s++) {
			long seed = rnd.nextLong();
			EndIslands islands = new EndIslands(seed);
			SimplexNoise noise = EndIslands.noise(seed);
			for (int i = 0; i < 5000; i++) {
				int cx = rnd.nextInt(600) - 300;
				int cz = rnd.nextInt(600) - 300;
				assertEquals(islands.biomeOfChunk(cx, cz).allowsEndCity(), EndIslands.allowsEndCity(noise, cx, cz));
			}
		}
	}

	@Test
	void saltSolverRecoversChangedSalt() {
		long seed = 987654321987L;
		int salt = 424242;
		Placement custom = new Placement(20, 11, salt, true);
		EndWorld world = new EndWorld(seed, custom);
		List<EndCity> cities = new ArrayList<>(world.citiesNear(0, 0, 3000));
		cities.sort(Comparator.comparingDouble(c -> Math.hypot(c.x(), c.z())));
		List<SeedCracker.City> observed = new ArrayList<>();
		List<Long> empty = new ArrayList<>();
		List<CandidateFilter.TerrainSample> samples = new ArrayList<>();
		Random rnd = new Random(1);
		for (EndCity c : cities.subList(0, 7)) {
			observed.add(new SeedCracker.City(List.of(new SeedCracker.Candidate(c.chunkX(), c.chunkZ(), c.rotation().ordinal()))));
			for (int dx = -10; dx <= 10; dx++) {
				for (int dz = -10; dz <= 10; dz++) {
					int cx = c.chunkX() + dx;
					int cz = c.chunkZ() + dz;
					if (!world.hasCityStartingIn(cx, cz)) {
						empty.add(Placement.pack(cx, cz));
					}
					if (rnd.nextInt(25) == 0) {
						samples.add(new CandidateFilter.TerrainSample(cx * 16 + 8, cz * 16 + 8, world.terrain().surfaceY(cx * 16 + 8, cz * 16 + 8)));
					}
				}
			}
		}
		int[] keys = EndSpikes.matchingKeys(EndSpikes.sizes(EndSpikes.key(seed)));
		SaltSolver.Result result = new SaltSolver(Placement.END_CITIES, keys, observed, empty, samples).run(Runtime.getRuntime().availableProcessors());
		assertEquals(List.of(new SaltSolver.Solution(seed, salt)), result.solutions(), String.valueOf(result.problem()));
	}
}
