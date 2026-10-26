package io.github.aabsolute22.endcityfinder.core;

/** {@code EndSpikeFeature.getSpikesForLevel}: the ten obsidian pillars around the main island. */
public final class EndSpikes {
	public static final int COUNT = 10;
	public static final int KEYS = 1 << 16;

	public record Spike(int index, int x, int z, int radius, int height, boolean guarded) {
	}

	private EndSpikes() {
	}

	public static int centerX(int index) {
		return (int) Math.floor(42.0 * Math.cos(2.0 * (-Math.PI + (Math.PI / 10.0) * index)));
	}

	public static int centerZ(int index) {
		return (int) Math.floor(42.0 * Math.sin(2.0 * (-Math.PI + (Math.PI / 10.0) * index)));
	}

	/** The 16-bit key the spike layout is cached by. */
	public static int key(long seed) {
		return (int) (new LegacyRandom(seed).nextLong() & 0xFFFFL);
	}

	/** Size index k (0..9) of every spike for a key: height {@code 76 + 3k}, radius {@code 2 + k / 3}. */
	public static int[] sizes(int key) {
		int[] list = new int[COUNT];
		for (int i = 0; i < COUNT; i++) {
			list[i] = i;
		}
		LegacyRandom random = new LegacyRandom(key);
		for (int i = COUNT; i > 1; i--) {
			int j = random.nextInt(i);
			int tmp = list[i - 1];
			list[i - 1] = list[j];
			list[j] = tmp;
		}
		return list;
	}

	public static Spike[] spikes(int key) {
		int[] sizes = sizes(key);
		Spike[] spikes = new Spike[COUNT];
		for (int i = 0; i < COUNT; i++) {
			int k = sizes[i];
			spikes[i] = new Spike(i, centerX(i), centerZ(i), 2 + k / 3, 76 + k * 3, k == 1 || k == 2);
		}
		return spikes;
	}

	public static int heightOfSize(int k) {
		return 76 + 3 * k;
	}

	/** Size index for an observed spike height, or -1 if the height is impossible. */
	public static int sizeOfHeight(int height) {
		int d = height - 76;
		return d < 0 || d % 3 != 0 || d / 3 > 9 ? -1 : d / 3;
	}

	/**
	 * Keys consistent with observed spike sizes; {@code observed[i] < 0} means spike i is unknown.
	 */
	public static int[] matchingKeys(int[] observed) {
		int[] out = new int[KEYS];
		int n = 0;
		outer:
		for (int key = 0; key < KEYS; key++) {
			int[] sizes = sizes(key);
			for (int i = 0; i < COUNT; i++) {
				if (observed[i] >= 0 && observed[i] != sizes[i]) {
					continue outer;
				}
			}
			out[n++] = key;
		}
		return java.util.Arrays.copyOf(out, n);
	}

	/**
	 * The 65536 values of the low 32 bits of a seed that produce this spike key: the key is bits 16..31 of the
	 * second LCG state, so every choice of the low 16 bits fixes the next 16 bits exactly.
	 */
	public static int[] lowSeedBits(int key) {
		final long mult2 = LegacyRandom.MULTIPLIER * LegacyRandom.MULTIPLIER;
		final long add2 = LegacyRandom.MULTIPLIER * LegacyRandom.ADDEND + LegacyRandom.ADDEND;
		int inv = inverse16((int) (mult2 & 0xFFFF));
		int[] result = new int[1 << 16];
		for (int u = 0; u < 1 << 16; u++) {
			long x = (mult2 * u + add2) & 0xFFFFFFFFL;
			int xHi = (int) (x >>> 16);
			int w = ((key - xHi) * inv) & 0xFFFF;
			long state0 = (long) u | (long) w << 16;
			result[u] = (int) ((state0 ^ LegacyRandom.MULTIPLIER) & 0xFFFFFFFFL);
		}
		return result;
	}

	private static int inverse16(int a) {
		int x = a;
		for (int i = 0; i < 5; i++) {
			x *= 2 - a * x;
		}
		return x & 0xFFFF;
	}
}
