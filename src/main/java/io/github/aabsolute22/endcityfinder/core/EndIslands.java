package io.github.aabsolute22.endcityfinder.core;

import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code DensityFunctions.EndIslandDensityFunction} and the End biome source built on top of it.
 * Island centers are cached in tiles because every height lookup scans a 25x25 neighbourhood of them.
 */
public final class EndIslands {
	private static final int TILE_BITS = 5;
	private static final int TILE = 1 << TILE_BITS;

	private final SimplexNoise noise;
	private final ConcurrentHashMap<Long, float[]> tiles = new ConcurrentHashMap<>();

	public EndIslands(long seed) {
		noise = noise(seed);
	}

	/** The island noise of a seed on its own, for brute-force checks that cannot afford the tile cache. */
	public static SimplexNoise noise(long seed) {
		LegacyRandom random = new LegacyRandom(seed);
		random.skip(17292);
		return new SimplexNoise(random);
	}

	/**
	 * Cache-free, exact equivalent of {@code biomeOfChunk(chunkX, chunkZ).allowsEndCity()}.
	 * Highlands or midlands means the height value is at least 0, i.e. some island cone reaches the point;
	 * cones are at most {@code 100 / 9} cells wide, so only a few nearby island points need the noise.
	 */
	public static boolean allowsEndCity(SimplexNoise noise, int chunkX, int chunkZ) {
		if ((long) chunkX * (long) chunkX + (long) chunkZ * (long) chunkZ <= 4096L) {
			return false;
		}
		int x = chunkX * 2 + 1;
		int z = chunkZ * 2 + 1;
		// The central island term only reaches 0 within 12.5 cells of the origin, far inside the end biome.
		int k = x / 2;
		int l = z / 2;
		int m = x % 2;
		int n = z % 2;
		for (int o = -6; o <= 6; o++) {
			for (int p = -6; p <= 6; p++) {
				float h = (float) (m - o * 2);
				float s = (float) (n - p * 2);
				float distance = (float) Math.sqrt(h * h + s * s);
				if (distance * 9.0F > 100.0F) {
					continue;
				}
				long q = (long) (k + o);
				long r = (long) (l + p);
				if (q * q + r * r > 4096L && noise.getValue((double) q, (double) r) < -0.9F) {
					float g = (Math.abs((float) q) * 3439.0F + Math.abs((float) r) * 147.0F) % 13.0F + 9.0F;
					if (100.0F - distance * g >= 0.0F) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Island radius factor at island grid point (each grid step is 16 blocks), or 0 if there is no island. */
	private float islandAt(long x, long z) {
		long tx = x >> TILE_BITS;
		long tz = z >> TILE_BITS;
		float[] tile = tiles.get(tx * 0x1_0000_0000L + tz);
		if (tile == null) {
			tile = new float[TILE * TILE];
			for (int i = 0; i < TILE; i++) {
				for (int j = 0; j < TILE; j++) {
					long o = (tx << TILE_BITS) + i;
					long p = (tz << TILE_BITS) + j;
					if (o * o + p * p > 4096L && noise.getValue((double) o, (double) p) < -0.9F) {
						tile[i * TILE + j] = (Math.abs((float) o) * 3439.0F + Math.abs((float) p) * 147.0F) % 13.0F + 9.0F;
					}
				}
			}
			if (tiles.size() > 4096) {
				tiles.clear();
			}
			tiles.put(tx * 0x1_0000_0000L + tz, tile);
		}
		return tile[(int) ((x - (tx << TILE_BITS)) * TILE + (z - (tz << TILE_BITS)))];
	}

	/** {@code EndIslandDensityFunction.getHeightValue}; arguments are block coordinates divided by 8 (truncated). */
	public float heightValue(int x, int z) {
		int k = x / 2;
		int l = z / 2;
		int m = x % 2;
		int n = z % 2;
		float f = 100.0F - (float) Math.sqrt((float) (x * x + z * z)) * 8.0F;
		f = clamp(f, -100.0F, 80.0F);
		for (int o = -12; o <= 12; o++) {
			for (int p = -12; p <= 12; p++) {
				long q = (long) (k + o);
				long r = (long) (l + p);
				float g = islandAt(q, r);
				if (g != 0.0F) {
					float h = (float) (m - o * 2);
					float s = (float) (n - p * 2);
					float t = 100.0F - (float) Math.sqrt(h * h + s * s) * g;
					t = clamp(t, -100.0F, 80.0F);
					f = Math.max(f, t);
				}
			}
		}
		return f;
	}

	/** The density function value at a block column, as used by both terrain and biomes. */
	public double density(int blockX, int blockZ) {
		return ((double) heightValue(blockX / 8, blockZ / 8) - 8.0) / 128.0;
	}

	/** {@code TheEndBiomeSource.getNoiseBiome}; biomes in the End are constant per chunk. */
	public EndBiome biomeOfChunk(int chunkX, int chunkZ) {
		if ((long) chunkX * (long) chunkX + (long) chunkZ * (long) chunkZ <= 4096L) {
			return EndBiome.THE_END;
		}
		double d = density((chunkX * 2 + 1) * 8, (chunkZ * 2 + 1) * 8);
		if (d > 0.25) {
			return EndBiome.HIGHLANDS;
		} else if (d >= -0.0625) {
			return EndBiome.MIDLANDS;
		} else {
			return d < -0.21875 ? EndBiome.SMALL_ISLANDS : EndBiome.BARRENS;
		}
	}

	private static float clamp(float value, float min, float max) {
		return value < min ? min : Math.min(value, max);
	}
}
