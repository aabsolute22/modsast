package io.github.aabsolute22.endcityfinder.core;

/** Minecraft's {@code BlendedNoise} with the legacy (End) octave setup. */
final class BlendedNoise {
	private final ImprovedNoise[] minLimit = new ImprovedNoise[16];
	private final ImprovedNoise[] maxLimit = new ImprovedNoise[16];
	private final ImprovedNoise[] main = new ImprovedNoise[8];
	private final double xzMultiplier;
	private final double yMultiplier;
	private final double xzFactor;
	private final double yFactor;
	private final double smearScaleMultiplier;

	BlendedNoise(LegacyRandom random, double xzScale, double yScale, double xzFactor, double yFactor, double smearScaleMultiplier) {
		// Legacy PerlinNoise creates its octaves from the highest frequency down, which is also getOctaveNoise(0..n) order.
		for (int i = 0; i < 16; i++) {
			minLimit[i] = new ImprovedNoise(random);
		}
		for (int i = 0; i < 16; i++) {
			maxLimit[i] = new ImprovedNoise(random);
		}
		for (int i = 0; i < 8; i++) {
			main[i] = new ImprovedNoise(random);
		}
		this.xzMultiplier = 684.412 * xzScale;
		this.yMultiplier = 684.412 * yScale;
		this.xzFactor = xzFactor;
		this.yFactor = yFactor;
		this.smearScaleMultiplier = smearScaleMultiplier;
	}

	static BlendedNoise end(long seed) {
		return new BlendedNoise(new LegacyRandom(seed), 0.25, 0.25, 80.0, 160.0, 4.0);
	}

	double compute(int blockX, int blockY, int blockZ) {
		double d = blockX * xzMultiplier;
		double e = blockY * yMultiplier;
		double f = blockZ * xzMultiplier;
		double g = d / xzFactor;
		double h = e / yFactor;
		double i = f / xzFactor;
		double j = yMultiplier * smearScaleMultiplier;
		double k = j / yFactor;
		double l = 0.0;
		double m = 0.0;
		double n = 0.0;
		double o = 1.0;
		for (int p = 0; p < 8; p++) {
			n += main[p].noise(wrap(g * o), wrap(h * o), wrap(i * o), k * o, h * o) / o;
			o /= 2.0;
		}
		double q = (n / 10.0 + 1.0) / 2.0;
		boolean atMax = q >= 1.0;
		boolean atMin = q <= 0.0;
		o = 1.0;
		for (int r = 0; r < 16; r++) {
			double s = wrap(d * o);
			double t = wrap(e * o);
			double u = wrap(f * o);
			double v = j * o;
			if (!atMax) {
				l += minLimit[r].noise(s, t, u, v, e * o) / o;
			}
			if (!atMin) {
				m += maxLimit[r].noise(s, t, u, v, e * o) / o;
			}
			o /= 2.0;
		}
		return clampedLerp(l / 512.0, m / 512.0, q) / 128.0;
	}

	static double wrap(double value) {
		return value - (double) lfloor(value / 3.3554432E7 + 0.5) * 3.3554432E7;
	}

	private static long lfloor(double value) {
		long l = (long) value;
		return value < l ? l - 1L : l;
	}

	static double clampedLerp(double start, double end, double delta) {
		if (delta < 0.0) {
			return start;
		}
		return delta > 1.0 ? end : ImprovedNoise.lerp(delta, start, end);
	}
}
