package io.github.aabsolute22.seedcracker.core;

/** Two ints packed into one long, used for chunk and block columns. */
public final class Packed {
	private Packed() {
	}

	public static long pack(int x, int z) {
		return (long) x << 32 | (z & 0xFFFFFFFFL);
	}

	public static int x(long packed) {
		return (int) (packed >> 32);
	}

	public static int z(long packed) {
		return (int) packed;
	}
}
