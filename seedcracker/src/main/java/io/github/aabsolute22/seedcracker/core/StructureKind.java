package io.github.aabsolute22.seedcracker.core;

import java.util.List;

/**
 * What the player can search for. One kind can cover several vanilla structures of one structure set
 * (all five village types, both shipwreck variants and so on).
 */
public enum StructureKind {
	VILLAGE(true, Spread.VILLAGES, "Деревня", "Village", 0xFF6FD66B, "village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga"),
	PILLAGER_OUTPOST(true, Spread.PILLAGER_OUTPOSTS, "Аванпост", "Pillager outpost", 0xFFB0B0B0, "pillager_outpost"),
	DESERT_PYRAMID(true, Spread.DESERT_PYRAMIDS, "Пирамида", "Desert pyramid", 0xFFF2D27A, "desert_pyramid"),
	JUNGLE_TEMPLE(true, Spread.JUNGLE_TEMPLES, "Храм в джунглях", "Jungle temple", 0xFF5DBB4A, "jungle_pyramid"),
	SWAMP_HUT(true, Spread.SWAMP_HUTS, "Хижина ведьмы", "Swamp hut", 0xFF8E7CC3, "swamp_hut"),
	IGLOO(true, Spread.IGLOOS, "Иглу", "Igloo", 0xFFE8F6FF, "igloo"),
	OCEAN_MONUMENT(true, Spread.OCEAN_MONUMENTS, "Океанский монумент", "Ocean monument", 0xFF4FD8C9, "monument"),
	WOODLAND_MANSION(true, Spread.WOODLAND_MANSIONS, "Особняк", "Woodland mansion", 0xFFA8754A, "mansion"),
	SHIPWRECK(true, Spread.SHIPWRECKS, "Корабль", "Shipwreck", 0xFFC79A5B, "shipwreck", "shipwreck_beached"),
	OCEAN_RUIN(true, Spread.OCEAN_RUINS, "Подводные руины", "Ocean ruins", 0xFF6FA8DC, "ocean_ruin_cold", "ocean_ruin_warm"),
	RUINED_PORTAL(true, Spread.RUINED_PORTALS, "Разрушенный портал", "Ruined portal", 0xFFB86BFF, "ruined_portal", "ruined_portal_desert",
			"ruined_portal_jungle", "ruined_portal_swamp", "ruined_portal_mountain", "ruined_portal_ocean"),
	ANCIENT_CITY(true, Spread.ANCIENT_CITIES, "Древний город", "Ancient city", 0xFF2EC4B6, "ancient_city"),
	TRAIL_RUINS(true, Spread.TRAIL_RUINS, "Руины тропы", "Trail ruins", 0xFFD9895B, "trail_ruins"),
	TRIAL_CHAMBERS(true, Spread.TRIAL_CHAMBERS, "Камеры испытаний", "Trial chambers", 0xFFE0A030, "trial_chambers"),
	BURIED_TREASURE(true, Spread.BURIED_TREASURES, "Клад", "Buried treasure", 0xFFFFE066, "buried_treasure"),
	STRONGHOLD(true, null, "Крепость (портал в энд)", "Stronghold", 0xFF9AE66E, "stronghold"),
	MINESHAFT(true, Spread.MINESHAFTS, "Шахта", "Mineshaft", 0xFF9C7A54, "mineshaft", "mineshaft_mesa"),
	FORTRESS(false, Spread.NETHER_COMPLEXES, "Адская крепость", "Nether fortress", 0xFFE0524F, "fortress"),
	BASTION(false, Spread.NETHER_COMPLEXES, "Бастион", "Bastion remnant", 0xFFFFB347, "bastion_remnant"),
	RUINED_PORTAL_NETHER(false, Spread.RUINED_PORTALS, "Разрушенный портал", "Ruined portal", 0xFFB86BFF, "ruined_portal_nether"),
	NETHER_FOSSIL(false, Spread.NETHER_FOSSILS, "Окаменелость", "Nether fossil", 0xFFE8E2CF, "nether_fossil");

	public final boolean overworld;
	public final Spread spread;
	public final String nameRu;
	public final String nameEn;
	public final int color;
	public final List<String> structures;

	StructureKind(boolean overworld, Spread spread, String nameRu, String nameEn, int color, String... structures) {
		this.overworld = overworld;
		this.spread = spread;
		this.nameRu = nameRu;
		this.nameEn = nameEn;
		this.color = color;
		this.structures = List.of(structures);
	}

	public String id() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	public static StructureKind byId(String id) {
		for (StructureKind k : values()) {
			if (k.id().equals(id)) {
				return k;
			}
		}
		return null;
	}

	/** The kind a vanilla structure id (without namespace) belongs to in the given dimension. */
	public static StructureKind ofStructure(String structure, boolean overworld) {
		for (StructureKind k : values()) {
			if (k.overworld == overworld && k.structures.contains(structure)) {
				return k;
			}
		}
		return null;
	}

	/** Region size in blocks, for choosing how far a search has to look; strongholds are handled separately. */
	public int regionBlocks() {
		return spread == null ? 0 : spread.spacing() * 16;
	}
}
