package io.github.aabsolute22.endcityfinder.core;

/** 2D part of Minecraft's {@code SimplexNoise}, used by the End island density function. */
public final class SimplexNoise {
	private static final int[][] GRADIENT = {
		{1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
		{1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
		{0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
		{1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
	};
	private static final double SQRT_3 = Math.sqrt(3.0);
	private static final double F2 = 0.5 * (SQRT_3 - 1.0);
	private static final double G2 = (3.0 - SQRT_3) / 6.0;

	private final int[] p = new int[512];
	public final double xo;
	public final double yo;
	public final double zo;

	public SimplexNoise(LegacyRandom random) {
		xo = random.nextDouble() * 256.0;
		yo = random.nextDouble() * 256.0;
		zo = random.nextDouble() * 256.0;
		for (int i = 0; i < 256; i++) {
			p[i] = i;
		}
		for (int i = 0; i < 256; i++) {
			int j = random.nextInt(256 - i);
			int k = p[i];
			p[i] = p[j + i];
			p[j + i] = k;
		}
		System.arraycopy(p, 0, p, 256, 256);
	}

	private int p(int i) {
		return p[i & 0xFF];
	}

	private static double corner(int index, double x, double y, double max) {
		double d = max - x * x - y * y;
		if (d < 0.0) {
			return 0.0;
		}
		d *= d;
		int[] g = GRADIENT[index];
		return d * d * (g[0] * x + g[1] * y);
	}

	public double getValue(double x, double y) {
		double d = (x + y) * F2;
		int i = floor(x + d);
		int j = floor(y + d);
		double e = (i + j) * G2;
		double h = x - (i - e);
		double k = y - (j - e);
		int l;
		int m;
		if (h > k) {
			l = 1;
			m = 0;
		} else {
			l = 0;
			m = 1;
		}
		double n = h - l + G2;
		double o = k - m + G2;
		double q = h - 1.0 + 2.0 * G2;
		double r = k - 1.0 + 2.0 * G2;
		int s = i & 0xFF;
		int t = j & 0xFF;
		int u = p(s + p(t)) % 12;
		int v = p(s + l + p(t + m)) % 12;
		int w = p(s + 1 + p(t + 1)) % 12;
		return 70.0 * (corner(u, h, k, 0.5) + corner(v, n, o, 0.5) + corner(w, q, r, 0.5));
	}

	static int floor(double value) {
		int i = (int) value;
		return value < i ? i - 1 : i;
	}
}
