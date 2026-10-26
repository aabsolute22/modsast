package io.github.aabsolute22.endcityfinder.core;

/** A {@code random_spread} structure placement. */
public record Placement(int spacing, int separation, int salt, boolean triangular) {
	public static final Placement END_CITIES = new Placement(20, 11, 10387313, true);

	public int limit() {
		return spacing - separation;
	}

	/** Chunk the structure start of region {@code (regionX, regionZ)} lands in, packed by {@link #pack}. */
	public long potentialChunk(long seed, int regionX, int regionZ) {
		LegacyRandom random = new LegacyRandom(0L);
		random.setLargeFeatureWithSalt(seed, regionX, regionZ, salt);
		int limit = limit();
		int x = evaluate(random, limit);
		int z = evaluate(random, limit);
		return pack(regionX * spacing + x, regionZ * spacing + z);
	}

	private int evaluate(LegacyRandom random, int limit) {
		return triangular ? (random.nextInt(limit) + random.nextInt(limit)) / 2 : random.nextInt(limit);
	}

	public int region(int chunk) {
		return Math.floorDiv(chunk, spacing);
	}

	public static long pack(int x, int z) {
		return (long) x << 32 | (z & 0xFFFFFFFFL);
	}

	public static int unpackX(long packed) {
		return (int) (packed >> 32);
	}

	public static int unpackZ(long packed) {
		return (int) packed;
	}
}
