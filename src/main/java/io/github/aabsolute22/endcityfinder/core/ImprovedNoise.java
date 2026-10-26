package io.github.aabsolute22.endcityfinder.core;

/** Minecraft's {@code ImprovedNoise} (classic Perlin noise with y smearing). */
final class ImprovedNoise {
	private static final double[][] GRADIENT = {
		{1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
		{1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
		{0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
		{1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
	};

	private final byte[] p = new byte[256];
	private final double xo;
	private final double yo;
	private final double zo;

	ImprovedNoise(LegacyRandom random) {
		xo = random.nextDouble() * 256.0;
		yo = random.nextDouble() * 256.0;
		zo = random.nextDouble() * 256.0;
		for (int i = 0; i < 256; i++) {
			p[i] = (byte) i;
		}
		for (int i = 0; i < 256; i++) {
			int j = random.nextInt(256 - i);
			byte b = p[i];
			p[i] = p[i + j];
			p[i + j] = b;
		}
	}

	double noise(double x, double y, double z, double yScale, double yMax) {
		double i = x + xo;
		double j = y + yo;
		double k = z + zo;
		int l = SimplexNoise.floor(i);
		int m = SimplexNoise.floor(j);
		int n = SimplexNoise.floor(k);
		double o = i - l;
		double pp = j - m;
		double q = k - n;
		double s;
		if (yScale != 0.0) {
			double r;
			if (yMax >= 0.0 && yMax < pp) {
				r = yMax;
			} else {
				r = pp;
			}
			s = SimplexNoise.floor(r / yScale + (double) 1.0E-7F) * yScale;
		} else {
			s = 0.0;
		}
		return sampleAndLerp(l, m, n, o, pp - s, q, pp);
	}

	private int p(int i) {
		return p[i & 0xFF] & 0xFF;
	}

	private static double gradDot(int i, double x, double y, double z) {
		double[] g = GRADIENT[i & 15];
		return g[0] * x + g[1] * y + g[2] * z;
	}

	private double sampleAndLerp(int x, int y, int z, double dx, double dy, double dz, double dyOriginal) {
		int l = p(x);
		int m = p(x + 1);
		int n = p(l + y);
		int o = p(l + y + 1);
		int q = p(m + y);
		int r = p(m + y + 1);
		double h = gradDot(p(n + z), dx, dy, dz);
		double s = gradDot(p(q + z), dx - 1.0, dy, dz);
		double t = gradDot(p(o + z), dx, dy - 1.0, dz);
		double u = gradDot(p(r + z), dx - 1.0, dy - 1.0, dz);
		double v = gradDot(p(n + z + 1), dx, dy, dz - 1.0);
		double w = gradDot(p(q + z + 1), dx - 1.0, dy, dz - 1.0);
		double a = gradDot(p(o + z + 1), dx, dy - 1.0, dz - 1.0);
		double b = gradDot(p(r + z + 1), dx - 1.0, dy - 1.0, dz - 1.0);
		double sx = smoothstep(dx);
		double sy = smoothstep(dyOriginal);
		double sz = smoothstep(dz);
		return lerp3(sx, sy, sz, h, s, t, u, v, w, a, b);
	}

	static double smoothstep(double d) {
		return d * d * d * (d * (d * 6.0 - 15.0) + 10.0);
	}

	static double lerp(double delta, double start, double end) {
		return start + delta * (end - start);
	}

	static double lerp2(double dx, double dy, double a, double b, double c, double d) {
		return lerp(dy, lerp(dx, a, b), lerp(dx, c, d));
	}

	static double lerp3(double dx, double dy, double dz, double a, double b, double c, double d, double e, double f, double g, double h) {
		return lerp(dz, lerp2(dx, dy, a, b, c, d), lerp2(dx, dy, e, f, g, h));
	}
}
