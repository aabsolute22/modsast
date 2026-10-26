package io.github.aabsolute22.endcityfinder.core;

/**
 * A generated End city.
 *
 * @param chunkX start chunk
 * @param chunkZ start chunk
 * @param y      height of the base floor
 * @param ship   whether the city has an End ship (elytra)
 * @param minX   horizontal bounds of all city pieces, for rendering
 */
public record EndCity(int chunkX, int chunkZ, int y, Rotation rotation, boolean ship,
		int minX, int minZ, int maxX, int maxZ, int maxY, int shipX, int shipY, int shipZ) {
	public int x() {
		return chunkX * 16 + 7;
	}

	public int z() {
		return chunkZ * 16 + 7;
	}

	public long key() {
		return Placement.pack(chunkX, chunkZ);
	}
}
