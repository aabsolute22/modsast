package io.github.aabsolute22.endcityfinder.core;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The End's noise terrain: {@code postProcess(slideEnd(endIslands + base_3d_noise_end))}, 8x4x8 block cells,
 * world height 0..128. Solid where the trilinearly interpolated density is positive.
 */
public final class EndTerrain {
	private static final int CELL_WIDTH = 8;
	private static final int CELL_HEIGHT = 4;
	private static final int CELL_COUNT_Y = 32;
	/** Upper bound of |base_3d_noise|; used only to skip cells that cannot contain solid blocks. */
	private static final double BASE_NOISE_BOUND = 1.1;

	private final EndIslands islands;
	private final BlendedNoise base;
	private final ConcurrentHashMap<Long, double[]> columns = new ConcurrentHashMap<>();

	public EndTerrain(long seed) {
		this(new EndIslands(seed), seed);
	}

	public EndTerrain(EndIslands islands, long seed) {
		this.islands = islands;
		this.base = BlendedNoise.end(seed);
	}

	public EndIslands islands() {
		return islands;
	}

	private static double clampedMap(double value, double fromMin, double fromMax, double toMin, double toMax) {
		return BlendedNoise.clampedLerp(toMin, toMax, (value - fromMin) / (fromMax - fromMin));
	}

	/** {@code DensityFunctions.lerp(delta, min, max)} expressed the same way vanilla builds it. */
	private static double lerpDf(double delta, double min, double max) {
		return (delta == 0.0 ? 0.0 : delta * (max + -min)) + min;
	}

	private static double slide(double density, int blockY) {
		double top = clampedMap(blockY, 56, 312, 1.0, 0.0);
		double r = lerpDf(top, -23.4375, density);
		double bottom = clampedMap(blockY, 4, 32, 0.0, 1.0);
		return lerpDf(bottom, -0.234375, r);
	}

	private double[] column(int cellX, int cellZ) {
		long key = (long) cellX * 0x1_0000_0000L + (cellZ & 0xFFFFFFFFL);
		double[] column = columns.get(key);
		if (column == null) {
			column = new double[CELL_COUNT_Y + 2];
			java.util.Arrays.fill(column, Double.NaN);
			// Last slot: the island density of this column, needed for the upper bound.
			column[CELL_COUNT_Y + 1] = islands.density(cellX * CELL_WIDTH, cellZ * CELL_WIDTH);
			if (columns.size() > 65536) {
				columns.clear();
			}
			columns.put(key, column);
		}
		return column;
	}

	private double corner(double[] column, int cellX, int cellY, int cellZ) {
		double v = column[cellY];
		if (Double.isNaN(v)) {
			int bx = cellX * CELL_WIDTH;
			int by = cellY * CELL_HEIGHT;
			int bz = cellZ * CELL_WIDTH;
			// final_density is squeeze(interpolated(0.64 * density)); squeeze keeps the sign.
			v = slide(column[CELL_COUNT_Y + 1] + base.compute(bx, by, bz), by) * 0.64;
			column[cellY] = v;
		}
		return v;
	}

	private static double upperBound(double[] column, int cellY) {
		return slide(column[CELL_COUNT_Y + 1] + BASE_NOISE_BOUND, cellY * CELL_HEIGHT);
	}

	/**
	 * {@code ChunkGenerator.getFirstOccupiedHeight(x, z, WORLD_SURFACE_WG)}: y of the highest solid block,
	 * or -1 when the column is empty.
	 */
	public int surfaceY(int blockX, int blockZ) {
		int cellX = Math.floorDiv(blockX, CELL_WIDTH);
		int cellZ = Math.floorDiv(blockZ, CELL_WIDTH);
		double dx = (double) Math.floorMod(blockX, CELL_WIDTH) / CELL_WIDTH;
		double dz = (double) Math.floorMod(blockZ, CELL_WIDTH) / CELL_WIDTH;
		double[] c00 = column(cellX, cellZ);
		double[] c10 = column(cellX + 1, cellZ);
		double[] c01 = column(cellX, cellZ + 1);
		double[] c11 = column(cellX + 1, cellZ + 1);
		for (int cy = CELL_COUNT_Y - 1; cy >= 0; cy--) {
			if (upperBound(c00, cy) <= 0 && upperBound(c10, cy) <= 0 && upperBound(c01, cy) <= 0 && upperBound(c11, cy) <= 0
					&& upperBound(c00, cy + 1) <= 0 && upperBound(c10, cy + 1) <= 0
					&& upperBound(c01, cy + 1) <= 0 && upperBound(c11, cy + 1) <= 0) {
				continue;
			}
			double n000 = corner(c00, cellX, cy, cellZ);
			double n100 = corner(c10, cellX + 1, cy, cellZ);
			double n001 = corner(c01, cellX, cy, cellZ + 1);
			double n101 = corner(c11, cellX + 1, cy, cellZ + 1);
			double n010 = corner(c00, cellX, cy + 1, cellZ);
			double n110 = corner(c10, cellX + 1, cy + 1, cellZ);
			double n011 = corner(c01, cellX, cy + 1, cellZ + 1);
			double n111 = corner(c11, cellX + 1, cy + 1, cellZ + 1);
			for (int q = CELL_HEIGHT - 1; q >= 0; q--) {
				double f = (double) q / CELL_HEIGHT;
				double v00 = ImprovedNoise.lerp(f, n000, n010);
				double v10 = ImprovedNoise.lerp(f, n100, n110);
				double v01 = ImprovedNoise.lerp(f, n001, n011);
				double v11 = ImprovedNoise.lerp(f, n101, n111);
				double v0 = ImprovedNoise.lerp(dx, v00, v10);
				double v1 = ImprovedNoise.lerp(dx, v01, v11);
				double v = ImprovedNoise.lerp(dz, v0, v1);
				if (v > 0.0) {
					return cy * CELL_HEIGHT + q;
				}
			}
		}
		return -1;
	}
}
