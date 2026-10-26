package io.github.aabsolute22.endcityfinder.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of Minecraft's {@code EndCityPieces} layout logic. Only bounding boxes are simulated, which is all the
 * generator looks at, so the result tells whether (and where) the city gets a ship.
 */
public final class EndCityPieces {
	/** Template sizes minus one (inclusive bounding box extents) from {@code data/minecraft/structure/end_city}. */
	enum Template {
		BASE_FLOOR(9, 3, 9),
		BASE_ROOF(11, 1, 11),
		BRIDGE_END(4, 5, 1),
		BRIDGE_GENTLE_STAIRS(4, 6, 7),
		BRIDGE_PIECE(4, 5, 3),
		BRIDGE_STEEP_STAIRS(4, 6, 3),
		FAT_TOWER_BASE(12, 3, 12),
		FAT_TOWER_MIDDLE(12, 7, 12),
		FAT_TOWER_TOP(16, 5, 16),
		SECOND_FLOOR_1(11, 7, 11),
		SECOND_FLOOR_2(11, 7, 11),
		SECOND_ROOF(13, 1, 13),
		SHIP(12, 23, 28),
		THIRD_FLOOR_1(13, 7, 13),
		THIRD_FLOOR_2(13, 7, 13),
		THIRD_ROOF(15, 1, 15),
		TOWER_BASE(6, 6, 6),
		TOWER_PIECE(6, 3, 6),
		TOWER_TOP(8, 4, 8);

		final int sx;
		final int sy;
		final int sz;

		Template(int sx, int sy, int sz) {
			this.sx = sx;
			this.sy = sy;
			this.sz = sz;
		}
	}

	static final class Piece {
		final Template template;
		final Rotation rotation;
		final int x;
		final int y;
		final int z;
		final int minX;
		final int minY;
		final int minZ;
		final int maxX;
		final int maxY;
		final int maxZ;
		int genDepth;

		Piece(Template template, Rotation rotation, int x, int y, int z) {
			this.template = template;
			this.rotation = rotation;
			this.x = x;
			this.y = y;
			this.z = z;
			int ex = rotation.rotateX(template.sx, template.sz);
			int ez = rotation.rotateZ(template.sx, template.sz);
			minX = Math.min(x, x + ex);
			maxX = Math.max(x, x + ex);
			minZ = Math.min(z, z + ez);
			maxZ = Math.max(z, z + ez);
			minY = y;
			maxY = y + template.sy;
		}

		boolean intersects(Piece o) {
			return maxX >= o.minX && minX <= o.maxX && maxZ >= o.minZ && minZ <= o.maxZ && maxY >= o.minY && minY <= o.maxY;
		}

		/** {@code EndCityPieces.addPiece}: offset is rotated by this piece's rotation. */
		Piece child(int ox, int oy, int oz, Template template, Rotation rotation) {
			return new Piece(template, rotation, x + this.rotation.rotateX(ox, oz), y + oy, z + this.rotation.rotateZ(ox, oz));
		}
	}

	private interface SectionGenerator {
		boolean generate(int depth, Piece current, int[] pos, List<Piece> pieces);
	}

	private final LegacyRandom random;
	private boolean shipCreated;

	private EndCityPieces(LegacyRandom random) {
		this.random = random;
	}

	/** Generates the full piece list for a city whose structure random is already past the rotation roll. */
	static List<Piece> generate(LegacyRandom random, int x, int y, int z, Rotation rotation) {
		EndCityPieces gen = new EndCityPieces(random);
		List<Piece> pieces = new ArrayList<>();
		Piece piece = add(pieces, new Piece(Template.BASE_FLOOR, rotation, x, y, z));
		piece = add(pieces, piece.child(-1, 0, -1, Template.SECOND_FLOOR_1, rotation));
		piece = add(pieces, piece.child(-1, 4, -1, Template.THIRD_FLOOR_1, rotation));
		piece = add(pieces, piece.child(-1, 8, -1, Template.THIRD_ROOF, rotation));
		gen.recursiveChildren(gen::tower, 1, piece, null, pieces);
		return pieces;
	}

	private static Piece add(List<Piece> pieces, Piece piece) {
		pieces.add(piece);
		return piece;
	}

	private boolean recursiveChildren(SectionGenerator generator, int depth, Piece current, int[] pos, List<Piece> pieces) {
		if (depth > 8) {
			return false;
		}
		List<Piece> list = new ArrayList<>();
		if (generator.generate(depth, current, pos, list)) {
			boolean collided = false;
			int genDepth = random.nextInt();
			for (Piece piece : list) {
				piece.genDepth = genDepth;
				Piece hit = null;
				for (Piece other : pieces) {
					if (other.intersects(piece)) {
						hit = other;
						break;
					}
				}
				if (hit != null && hit.genDepth != current.genDepth) {
					collided = true;
					break;
				}
			}
			if (!collided) {
				pieces.addAll(list);
				return true;
			}
		}
		return false;
	}

	private static final int[][] TOWER_BRIDGES = {{0, 1, -1, 0}, {1, 6, -1, 1}, {3, 0, -1, 5}, {2, 5, -1, 6}};
	private static final int[][] FAT_TOWER_BRIDGES = {{0, 4, -1, 0}, {1, 12, -1, 4}, {3, 0, -1, 8}, {2, 8, -1, 12}};

	private boolean houseTower(int depth, Piece current, int[] pos, List<Piece> pieces) {
		if (depth > 8) {
			return false;
		}
		Rotation rotation = current.rotation;
		Piece piece = add(pieces, current.child(pos[0], pos[1], pos[2], Template.BASE_FLOOR, rotation));
		int size = random.nextInt(3);
		if (size == 0) {
			add(pieces, piece.child(-1, 4, -1, Template.BASE_ROOF, rotation));
		} else if (size == 1) {
			piece = add(pieces, piece.child(-1, 0, -1, Template.SECOND_FLOOR_2, rotation));
			piece = add(pieces, piece.child(-1, 8, -1, Template.SECOND_ROOF, rotation));
			recursiveChildren(this::tower, depth + 1, piece, null, pieces);
		} else {
			piece = add(pieces, piece.child(-1, 0, -1, Template.SECOND_FLOOR_2, rotation));
			piece = add(pieces, piece.child(-1, 4, -1, Template.THIRD_FLOOR_2, rotation));
			piece = add(pieces, piece.child(-1, 8, -1, Template.THIRD_ROOF, rotation));
			recursiveChildren(this::tower, depth + 1, piece, null, pieces);
		}
		return true;
	}

	private boolean tower(int depth, Piece current, int[] pos, List<Piece> pieces) {
		Rotation rotation = current.rotation;
		Piece piece = add(pieces, current.child(3 + random.nextInt(2), -3, 3 + random.nextInt(2), Template.TOWER_BASE, rotation));
		piece = add(pieces, piece.child(0, 7, 0, Template.TOWER_PIECE, rotation));
		Piece floor = random.nextInt(3) == 0 ? piece : null;
		int floors = 1 + random.nextInt(3);
		for (int j = 0; j < floors; j++) {
			piece = add(pieces, piece.child(0, 4, 0, Template.TOWER_PIECE, rotation));
			if (j < floors - 1 && random.nextBoolean()) {
				floor = piece;
			}
		}
		if (floor != null) {
			for (int[] bridge : TOWER_BRIDGES) {
				if (random.nextBoolean()) {
					Piece end = add(pieces, floor.child(bridge[1], bridge[2], bridge[3], Template.BRIDGE_END, rotation.rotated(Rotation.byIndex(bridge[0]))));
					recursiveChildren(this::towerBridge, depth + 1, end, null, pieces);
				}
			}
			add(pieces, piece.child(-1, 4, -1, Template.TOWER_TOP, rotation));
		} else {
			if (depth != 7) {
				return recursiveChildren(this::fatTower, depth + 1, piece, null, pieces);
			}
			add(pieces, piece.child(-1, 4, -1, Template.TOWER_TOP, rotation));
		}
		return true;
	}

	private boolean towerBridge(int depth, Piece current, int[] pos, List<Piece> pieces) {
		Rotation rotation = current.rotation;
		int count = random.nextInt(4) + 1;
		Piece piece = add(pieces, current.child(0, 0, -4, Template.BRIDGE_PIECE, rotation));
		piece.genDepth = -1;
		int y = 0;
		for (int k = 0; k < count; k++) {
			if (random.nextBoolean()) {
				piece = add(pieces, piece.child(0, y, -4, Template.BRIDGE_PIECE, rotation));
				y = 0;
			} else {
				if (random.nextBoolean()) {
					piece = add(pieces, piece.child(0, y, -4, Template.BRIDGE_STEEP_STAIRS, rotation));
				} else {
					piece = add(pieces, piece.child(0, y, -8, Template.BRIDGE_GENTLE_STAIRS, rotation));
				}
				y = 4;
			}
		}
		if (!shipCreated && random.nextInt(10 - depth) == 0) {
			add(pieces, piece.child(-8 + random.nextInt(8), y, -70 + random.nextInt(10), Template.SHIP, rotation));
			shipCreated = true;
		} else if (!recursiveChildren(this::houseTower, depth + 1, piece, new int[] {-3, y + 1, -11}, pieces)) {
			return false;
		}
		piece = add(pieces, piece.child(4, y, 0, Template.BRIDGE_END, rotation.rotated(Rotation.CLOCKWISE_180)));
		piece.genDepth = -1;
		return true;
	}

	private boolean fatTower(int depth, Piece current, int[] pos, List<Piece> pieces) {
		Rotation rotation = current.rotation;
		Piece piece = add(pieces, current.child(-3, 4, -3, Template.FAT_TOWER_BASE, rotation));
		piece = add(pieces, piece.child(0, 4, 0, Template.FAT_TOWER_MIDDLE, rotation));
		for (int i = 0; i < 2 && random.nextInt(3) != 0; i++) {
			piece = add(pieces, piece.child(0, 8, 0, Template.FAT_TOWER_MIDDLE, rotation));
			for (int[] bridge : FAT_TOWER_BRIDGES) {
				if (random.nextBoolean()) {
					Piece end = add(pieces, piece.child(bridge[1], bridge[2], bridge[3], Template.BRIDGE_END, rotation.rotated(Rotation.byIndex(bridge[0]))));
					recursiveChildren(this::towerBridge, depth + 1, end, null, pieces);
				}
			}
		}
		add(pieces, piece.child(-2, 8, -2, Template.FAT_TOWER_TOP, rotation));
		return true;
	}
}
