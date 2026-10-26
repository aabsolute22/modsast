package io.github.aabsolute22.endcityfinder.core;

/**
 * Bit-exact copy of {@code java.util.Random} / Minecraft's {@code LegacyRandomSource}.
 * Only the low 48 bits of any seed matter, which is why the whole End is decided by a 48-bit structure seed.
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

	public boolean nextBoolean() {
		return next(1) != 0;
	}

	public double nextDouble() {
		return (((long) next(26) << 27) + next(27)) * 0x1.0p-53;
	}

	/** Equivalent to calling {@link #nextInt()} {@code count} times. */
	public void skip(long count) {
		long mult = 1;
		long add = 0;
		long stepMult = MULTIPLIER;
		long stepAdd = ADDEND;
		for (long k = count; k != 0; k >>>= 1) {
			if ((k & 1) != 0) {
				mult = mult * stepMult;
				add = add * stepMult + stepAdd;
			}
			stepAdd = (stepMult + 1) * stepAdd;
			stepMult = stepMult * stepMult;
		}
		state = (state * mult + add) & MASK;
	}

	/** {@code WorldgenRandom.setLargeFeatureSeed}: the random a structure start uses for rotation and pieces. */
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
