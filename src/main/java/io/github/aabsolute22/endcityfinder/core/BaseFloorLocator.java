package io.github.aabsolute22.endcityfinder.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the start chunk and rotation of an End city from the footprint of its lowest block layer.
 * <p>
 * The city starts with {@code base_floor} placed with its template origin at {@code (16cx+7, y, 16cz+7)}.
 * The bottom layer of that template covers x 1..8, z 1..9, so rotating the box and lining the origin up
 * with 7 (mod 16) pins down both the chunk and the rotation.
 */
public final class BaseFloorLocator {
	private static final int MIN_X = 1;
	private static final int MAX_X = 8;
	private static final int MIN_Z = 1;
	private static final int MAX_Z = 9;

	/** {@code exact} means the footprint had the expected size, so the candidates are trustworthy. */
	public record Result(List<SeedCracker.Candidate> candidates, boolean exact) {
	}

	private BaseFloorLocator() {
	}

	/** World-space footprint of the base floor bottom layer for a city start, as {minX, minZ, maxX, maxZ}. */
	public static int[] footprint(int chunkX, int chunkZ, Rotation rotation) {
		int px = chunkX * 16 + 7;
		int pz = chunkZ * 16 + 7;
		int ax = rotation.rotateX(MIN_X, MIN_Z);
		int az = rotation.rotateZ(MIN_X, MIN_Z);
		int bx = rotation.rotateX(MAX_X, MAX_Z);
		int bz = rotation.rotateZ(MAX_X, MAX_Z);
		return new int[] {px + Math.min(ax, bx), pz + Math.min(az, bz), px + Math.max(ax, bx), pz + Math.max(az, bz)};
	}

	public static Result locate(int minX, int minZ, int maxX, int maxZ) {
		List<SeedCracker.Candidate> exact = new ArrayList<>();
		List<SeedCracker.Candidate> loose = new ArrayList<>();
		for (Rotation rotation : Rotation.values()) {
			// The template origin is the corner the rotated box grows away from.
			int ox = rotation.rotateX(MIN_X, MIN_Z) > 0 || rotation.rotateX(MAX_X, MAX_Z) > 0 ? minX - Math.min(rotation.rotateX(MIN_X, MIN_Z), rotation.rotateX(MAX_X, MAX_Z))
					: maxX - Math.max(rotation.rotateX(MIN_X, MIN_Z), rotation.rotateX(MAX_X, MAX_Z));
			int oz = rotation.rotateZ(MIN_X, MIN_Z) > 0 || rotation.rotateZ(MAX_X, MAX_Z) > 0 ? minZ - Math.min(rotation.rotateZ(MIN_X, MIN_Z), rotation.rotateZ(MAX_X, MAX_Z))
					: maxZ - Math.max(rotation.rotateZ(MIN_X, MIN_Z), rotation.rotateZ(MAX_X, MAX_Z));
			if (Math.floorMod(ox, 16) != 7 || Math.floorMod(oz, 16) != 7) {
				continue;
			}
			SeedCracker.Candidate candidate = new SeedCracker.Candidate(Math.floorDiv(ox, 16), Math.floorDiv(oz, 16), rotation.ordinal());
			int[] expected = footprint(candidate.chunkX(), candidate.chunkZ(), rotation);
			if (expected[0] == minX && expected[1] == minZ && expected[2] == maxX && expected[3] == maxZ) {
				exact.add(candidate);
			} else {
				loose.add(candidate);
			}
		}
		if (!exact.isEmpty()) {
			return new Result(exact, true);
		}
		if (!loose.isEmpty()) {
			return new Result(loose, false);
		}
		return new Result(around((minX + maxX) / 2, (minZ + maxZ) / 2, 12), false);
	}

	/** Every start chunk whose base floor origin lies within {@code radius} blocks of (x, z); rotation unknown. */
	public static List<SeedCracker.Candidate> around(int x, int z, int radius) {
		List<SeedCracker.Candidate> list = new ArrayList<>();
		for (int cx = Math.floorDiv(x - radius - 7, 16); cx <= Math.floorDiv(x + radius - 7, 16) + 1; cx++) {
			for (int cz = Math.floorDiv(z - radius - 7, 16); cz <= Math.floorDiv(z + radius - 7, 16) + 1; cz++) {
				int dx = cx * 16 + 7 - x;
				int dz = cz * 16 + 7 - z;
				if (dx * dx + dz * dz <= radius * radius) {
					list.add(new SeedCracker.Candidate(cx, cz, -1));
				}
			}
		}
		return list;
	}
}
