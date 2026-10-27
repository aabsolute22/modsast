package io.github.aabsolute22.seedcracker.world;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.aabsolute22.seedcracker.core.Packed;
import io.github.aabsolute22.seedcracker.core.Spread;
import io.github.aabsolute22.seedcracker.core.StructureKind;

/**
 * Start chunks a structure can have, given a block the player points at by hand. Every kind has a few boxes,
 * relative to the start chunk origin, that the block may lie in: the start piece itself where any spot inside it
 * will do, or a small area around a landmark (the middle of a monument's roof, of an ancient city's portal frame).
 * A chunk is a candidate when the block falls in one of its boxes; one candidate means the start is known exactly.
 */
public final class ManualPlacement {
	/** Inclusive horizontal box relative to a start chunk origin. */
	public record Box(int minX, int maxX, int minZ, int maxZ) {
	}

	private final Map<StructureKind, List<Box>> boxes = new EnumMap<>(StructureKind.class);

	public ManualPlacement(StartTemplates templates) {
		put(StructureKind.DESERT_PYRAMID, new Box(0, 20, 0, 20));
		// Scattered feature pieces start at the origin and turn by swapping width and depth.
		put(StructureKind.JUNGLE_TEMPLE, new Box(0, 11, 0, 14), new Box(0, 14, 0, 11));
		put(StructureKind.SWAMP_HUT, new Box(0, 6, 0, 8), new Box(0, 8, 0, 6));
		// The monument is centred on the corner of its start chunk, the ancient city on its city_anchor jigsaw.
		put(StructureKind.OCEAN_MONUMENT, new Box(-12, 11, -12, 11));
		put(StructureKind.ANCIENT_CITY, new Box(-12, 11, -12, 11));
		// The treasure chest is always at (9, 9).
		put(StructureKind.BURIED_TREASURE, new Box(6, 12, 6, 12));
		// The central room of a mineshaft starts at (2, 2) and is 8 to 13 blocks wide.
		put(StructureKind.MINESHAFT, new Box(2, 14, 2, 14));
		// The first bridge crossing of a fortress starts at (2, 2) and is 19 blocks square.
		put(StructureKind.FORTRESS, new Box(2, 20, 2, 20));
		// A mansion is laid out from (7, 7); its front door is within a room or two of that.
		put(StructureKind.WOODLAND_MANSION, new Box(-13, 27, -13, 27));
		for (StructureKind k : StructureKind.values()) {
			if (!boxes.containsKey(k)) {
				List<Box> list = new ArrayList<>();
				for (int[] f : templates.footprints(k)) {
					list.add(new Box(f[0], f[1], f[2], f[3]));
				}
				if (!list.isEmpty()) {
					boxes.put(k, List.copyOf(list));
				}
			}
		}
	}

	private void put(StructureKind kind, Box... list) {
		boxes.put(kind, List.of(list));
	}

	/** Whether a kind can be added by hand at all: it needs a structure set that says something about the seed. */
	public static boolean useful(StructureKind kind) {
		Spread s = kind.spread;
		return s != null && (s.limit() > 1 || s.frequency() < 1f);
	}

	/**
	 * Whether a position alone is trustworthy for this kind. Fortresses have many crossings and trial chambers many
	 * corridors that look like their start; for those only the blocks (or a chunk typed in) can tell.
	 */
	public static boolean positionWorks(StructureKind kind) {
		return kind != StructureKind.FORTRESS && kind != StructureKind.TRIAL_CHAMBERS;
	}

	public List<Box> boxes(StructureKind kind) {
		return boxes.getOrDefault(kind, List.of());
	}

	/** Possible start chunks {chunkX, chunkZ} for a block at (x, z) that belongs to a structure of this kind. */
	public List<int[]> candidates(StructureKind kind, int x, int z) {
		Set<Long> keys = new LinkedHashSet<>();
		Spread s = kind.spread;
		for (Box b : boxes(kind)) {
			int cx0 = Math.floorDiv(x - b.maxX() + 15, 16);
			int cx1 = Math.floorDiv(x - b.minX(), 16);
			int cz0 = Math.floorDiv(z - b.maxZ() + 15, 16);
			int cz1 = Math.floorDiv(z - b.minZ(), 16);
			for (int cx = cx0; cx <= cx1; cx++) {
				for (int cz = cz0; cz <= cz1; cz++) {
					if (s == null || offsetOk(s, cx) && offsetOk(s, cz)) {
						keys.add(Packed.pack(cx, cz));
					}
				}
			}
		}
		List<int[]> out = new ArrayList<>();
		for (long k : keys) {
			out.add(new int[] {Packed.x(k), Packed.z(k)});
		}
		// Nearest first: the likeliest one when the player stood roughly in the middle.
		out.sort((a, c) -> Double.compare(Math.hypot(a[0] * 16 + 8 - x, a[1] * 16 + 8 - z), Math.hypot(c[0] * 16 + 8 - x, c[1] * 16 + 8 - z)));
		return out;
	}

	/** A structure set never starts in the last {@code separation} chunks of its regions. */
	private static boolean offsetOk(Spread s, int chunk) {
		return chunk - s.region(chunk) * s.spacing() < s.limit();
	}

	/** Where to stand for each kind, in the player's words. */
	public static String hint(StructureKind kind) {
		return switch (kind) {
			case VILLAGE -> "встань в центре деревни, у колокола или колодца";
			case PILLAGER_OUTPOST -> "встань в башне аванпоста";
			case DESERT_PYRAMID -> "встань в пирамиде, лучше в середине над синей терракотой";
			case JUNGLE_TEMPLE -> "встань внутри храма";
			case SWAMP_HUT -> "встань в хижине";
			case IGLOO -> "встань в иглу (наверху, не в подвале)";
			case OCEAN_MONUMENT -> "встань на середину крыши монумента";
			case WOODLAND_MANSION -> "встань у главного входа";
			case SHIPWRECK -> "встань на корабль";
			case OCEAN_RUIN -> "встань в самой большой постройке руин";
			case RUINED_PORTAL, RUINED_PORTAL_NETHER -> "встань у рамки портала";
			case ANCIENT_CITY -> "встань в середину большой рамки из укреплённого глубинного сланца";
			case TRAIL_RUINS -> "встань у башни руин (её верх торчит из земли)";
			case TRIAL_CHAMBERS -> "мод узнаёт их только по блокам: встань рядом, внутри или над ними";
			case BURIED_TREASURE -> "встань на сундук клада или туда, где крестик на карте сокровищ";
			case MINESHAFT -> "встань в большую комнату с земляным полом, из которой расходятся туннели";
			case FORTRESS -> "мод узнаёт её только по блокам: встань на мост рядом с перекрёстком";
			case BASTION -> "встань в середине бастиона";
			default -> "встань в структуре";
		};
	}
}
