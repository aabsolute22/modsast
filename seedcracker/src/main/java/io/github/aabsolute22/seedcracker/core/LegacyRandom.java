package io.github.aabsolute22.seedcracker.core;

/**
 * Bit-exact copy of {@code java.util.Random} / Minecraft's {@code LegacyRandomSource}.
 * Structure placement only ever sees the low 48 bits of the world seed through it.
 */
public final class LegacyRandom {
	public static final long MULTIPLIER = 0x5DEECE66DL;
	public static final long ADDEND = 0xBL;
	public static final long MASK = (1L << 48) - 1;

	private long state;

	public LegacyRandom(long seed) {
		setSeed(seed);
	}

	public void setSeed(long seed) {
		state = (seed ^ MULTIPLIER) & MASK;
	}

	public long state() {
		return state;
	}

	public int next(int bits) {
		state = (state * MULTIPLIER + ADDEND) & MASK;
		return (int) (state >>> (48 - bits));
	}

	public int nextInt() {
		return next(32);
	}

	public int nextInt(int bound) {
		if ((bound & (bound - 1)) == 0) {
			return (int) ((long) bound * next(31) >> 31);
		}
		int bits;
		int value;
		do {
			bits = next(31);
			value = bits % bound;
		} while (bits - value + (bound - 1) < 0);
		return value;
	}

	public long nextLong() {
		return ((long) next(32) << 32) + next(32);
	}

	public float nextFloat() {
		return next(24) * 5.9604645E-8F;
	}

	public double nextDouble() {
		return (((long) next(26) << 27) + next(27)) * 0x1.0p-53;
	}

	/** {@code WorldgenRandom.setLargeFeatureSeed}: the random a structure start uses for its rotation and pieces. */
	public void setLargeFeatureSeed(long worldSeed, int chunkX, int chunkZ) {
		setSeed(worldSeed);
		long a = nextLong();
		long b = nextLong();
		setSeed((long) chunkX * a ^ (long) chunkZ * b ^ worldSeed);
	}

	/** {@code WorldgenRandom.setLargeFeatureWithSalt}: the random of a {@code random_spread} placement region. */
	public void setLargeFeatureWithSalt(long worldSeed, int regionX, int regionZ, int salt) {
		setSeed((long) regionX * 341873128712L + (long) regionZ * 132897987541L + worldSeed + (long) salt);
	}
}
