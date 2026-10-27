package io.github.aabsolute22.seedcracker.core;

import java.util.List;

/**
 * A vanilla {@code random_spread} structure set of the Overworld or the Nether (26.2 values, checked against the
 * game in the ground-truth test). Everything here depends only on the low 48 bits of the world seed.
 */
public record Spread(String id, int spacing, int separation, int salt, boolean triangular, float frequency, Reducer reducer) {
	/** {@code StructurePlacement.FrequencyReductionMethod}. */
	public enum Reducer {
		DEFAULT, LEGACY_TYPE_1, LEGACY_TYPE_2, LEGACY_TYPE_3
	}

	public static final long REGION_X = 341873128712L;
	public static final long REGION_Z = 132897987541L;
	/** Salt {@code legacy_type_2} uses instead of the set's own. */
	public static final int LEGACY_2_SALT = 10387320;

	public static final Spread VILLAGES = linear("villages", 34, 8, 10387312);
	public static final Spread DESERT_PYRAMIDS = linear("desert_pyramids", 32, 8, 14357617);
	public static final Spread IGLOOS = linear("igloos", 32, 8, 14357618);
	public static final Spread JUNGLE_TEMPLES = linear("jungle_temples", 32, 8, 14357619);
	public static final Spread SWAMP_HUTS = linear("swamp_huts", 32, 8, 14357620);
	public static final Spread PILLAGER_OUTPOSTS = new Spread("pillager_outposts", 32, 8, 165745296, false, 0.2f, Reducer.LEGACY_TYPE_1);
	public static final Spread OCEAN_MONUMENTS = new Spread("ocean_monuments", 32, 5, 10387313, true, 1f, Reducer.DEFAULT);
	public static final Spread WOODLAND_MANSIONS = new Spread("woodland_mansions", 80, 20, 10387319, true, 1f, Reducer.DEFAULT);
	public static final Spread SHIPWRECKS = linear("shipwrecks", 24, 4, 165745295);
	public static final Spread OCEAN_RUINS = linear("ocean_ruins", 20, 8, 14357621);
	public static final Spread RUINED_PORTALS = linear("ruined_portals", 40, 15, 34222645);
	public static final Spread ANCIENT_CITIES = linear("ancient_cities", 24, 8, 20083232);
	public static final Spread TRAIL_RUINS = linear("trail_ruins", 34, 8, 83469867);
	public static final Spread TRIAL_CHAMBERS = linear("trial_chambers", 34, 12, 94251327);
	public static final Spread BURIED_TREASURES = new Spread("buried_treasures", 1, 0, 0, false, 0.01f, Reducer.LEGACY_TYPE_2);
	public static final Spread MINESHAFTS = new Spread("mineshafts", 1, 0, 0, false, 0.004f, Reducer.LEGACY_TYPE_3);
	public static final Spread NETHER_COMPLEXES = linear("nether_complexes", 27, 4, 30084232);
	public static final Spread NETHER_FOSSILS = linear("nether_fossils", 2, 1, 14357921);

	public static final List<Spread> ALL = List.of(VILLAGES, DESERT_PYRAMIDS, IGLOOS, JUNGLE_TEMPLES, SWAMP_HUTS, PILLAGER_OUTPOSTS,
			OCEAN_MONUMENTS, WOODLAND_MANSIONS, SHIPWRECKS, OCEAN_RUINS, RUINED_PORTALS, ANCIENT_CITIES, TRAIL_RUINS, TRIAL_CHAMBERS,
			BURIED_TREASURES, MINESHAFTS, NETHER_COMPLEXES, NETHER_FOSSILS);

	/** Outposts never start within this many chunks (Chebyshev) of a potential village chunk. */
	public static final int OUTPOST_VILLAGE_EXCLUSION = 10;

	private static Spread linear(String id, int spacing, int separation, int salt) {
		return new Spread(id, spacing, separation, salt, false, 1f, Reducer.DEFAULT);
	}

	public static Spread byId(String id) {
		for (Spread s : ALL) {
			if (s.id.equals(id)) {
				return s;
			}
		}
		return null;
	}

	/** Bound of each {@code nextInt} that picks the offset inside a region. */
	public int limit() {
		return spacing - separation;
	}

	public int region(int chunk) {
		return Math.floorDiv(chunk, spacing);
	}

	public long regionConstant(int regionX, int regionZ) {
		return (long) regionX * REGION_X + (long) regionZ * REGION_Z + (long) salt;
	}

	/** Chunk the structure of region {@code (regionX, regionZ)} would start in, packed by {@link Packed}. */
	public long potentialChunk(long seed, int regionX, int regionZ) {
		LegacyRandom random = new LegacyRandom(0L);
		random.setLargeFeatureWithSalt(seed, regionX, regionZ, salt);
		int limit = limit();
		int x = evaluate(random, limit);
		int z = evaluate(random, limit);
		return Packed.pack(regionX * spacing + x, regionZ * spacing + z);
	}

	private int evaluate(LegacyRandom random, int limit) {
		return triangular ? (random.nextInt(limit) + random.nextInt(limit)) / 2 : random.nextInt(limit);
	}

	/** {@code isPlacementChunk} plus the frequency reduction; the exclusion zone is separate. */
	public boolean isStructureChunk(long seed, int chunkX, int chunkZ) {
		long p = potentialChunk(seed, region(chunkX), region(chunkZ));
		if (Packed.x(p) != chunkX || Packed.z(p) != chunkZ) {
			return false;
		}
		return frequency >= 1f || frequencyAccepts(seed, chunkX, chunkZ);
	}

	/** {@code FrequencyReductionMethod.shouldGenerate} for a placement chunk. */
	public boolean frequencyAccepts(long seed, int chunkX, int chunkZ) {
		LegacyRandom random = new LegacyRandom(0L);
		switch (reducer) {
			case DEFAULT -> {
				// Vanilla passes (salt, x, z) into setLargeFeatureWithSalt's (x, z, salt) slots.
				random.setLargeFeatureWithSalt(seed, salt, chunkX, chunkZ);
				return random.nextFloat() < frequency;
			}
			case LEGACY_TYPE_1 -> {
				int i = chunkX >> 4;
				int j = chunkZ >> 4;
				random.setSeed((long) (i ^ j << 4) ^ seed);
				random.nextInt();
				return random.nextInt((int) (1.0F / frequency)) == 0;
			}
			case LEGACY_TYPE_2 -> {
				random.setLargeFeatureWithSalt(seed, chunkX, chunkZ, LEGACY_2_SALT);
				return random.nextFloat() < frequency;
			}
			case LEGACY_TYPE_3 -> {
				random.setLargeFeatureSeed(seed, chunkX, chunkZ);
				return random.nextDouble() < (double) frequency;
			}
		}
		return false;
	}

	/** Whether an outpost placement chunk is cancelled by a nearby potential village chunk. */
	public static boolean outpostExcluded(long seed, int chunkX, int chunkZ) {
		int r = OUTPOST_VILLAGE_EXCLUSION;
		Spread v = VILLAGES;
		for (int rx = v.region(chunkX - r); rx <= v.region(chunkX + r); rx++) {
			for (int rz = v.region(chunkZ - r); rz <= v.region(chunkZ + r); rz++) {
				long p = v.potentialChunk(seed, rx, rz);
				if (Math.abs(Packed.x(p) - chunkX) <= r && Math.abs(Packed.z(p) - chunkZ) <= r) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Index of the structure picked by {@code ChunkGenerator.createStructures} for a set with several weighted
	 * structures, before biome checks: the first weighted draw of the start chunk's large-feature random.
	 */
	public static int firstWeightedPick(long seed, int chunkX, int chunkZ, int[] weights) {
		LegacyRandom random = new LegacyRandom(0L);
		random.setLargeFeatureSeed(seed, chunkX, chunkZ);
		int total = 0;
		for (int w : weights) {
			total += w;
		}
		int j = random.nextInt(total);
		for (int k = 0; k < weights.length; k++) {
			j -= weights[k];
			if (j < 0) {
				return k;
			}
		}
		return weights.length - 1;
	}

	/** {@code Rotation.getRandom} as the first draw of the start chunk's large-feature random (0..3). */
	public static int startRotation(long seed, int chunkX, int chunkZ) {
		LegacyRandom random = new LegacyRandom(0L);
		random.setLargeFeatureSeed(seed, chunkX, chunkZ);
		return random.nextInt(4);
	}

	/** Probability that a uniformly random seed puts this set's offset at {@code offset} on one axis. */
	public double offsetProbability(int offset) {
		int limit = limit();
		if (offset < 0 || offset >= limit) {
			return 0;
		}
		if (!triangular) {
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
		return (double) ways / ((double) limit * limit);
	}
}
