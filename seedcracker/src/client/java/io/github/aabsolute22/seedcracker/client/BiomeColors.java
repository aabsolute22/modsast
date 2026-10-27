package io.github.aabsolute22.seedcracker.client;

import java.util.HashMap;
import java.util.Map;

/** Map colours per biome, roughly the palette people know from seed map sites. */
final class BiomeColors {
	private static final Map<String, Integer> COLORS = new HashMap<>();

	static {
		put(0x000070, "ocean");
		put(0x000030, "deep_ocean");
		put(0x0000AC, "warm_ocean");
		put(0x000090, "lukewarm_ocean", "deep_lukewarm_ocean");
		put(0x202070, "cold_ocean", "deep_cold_ocean");
		put(0x7070D6, "frozen_ocean", "deep_frozen_ocean");
		put(0x3050D0, "river");
		put(0xA0A0FF, "frozen_river");
		put(0xFADE55, "beach");
		put(0xFAF0C0, "snowy_beach");
		put(0xA2A284, "stony_shore");
		put(0x8DB360, "plains");
		put(0xB5DB88, "sunflower_plains");
		put(0xFFFFFF, "snowy_plains");
		put(0xB4DCDC, "ice_spikes");
		put(0xFA9418, "desert");
		put(0x07F9B2, "swamp");
		put(0x2CCC8E, "mangrove_swamp");
		put(0x056621, "forest");
		put(0x2D8E49, "flower_forest");
		put(0x307444, "birch_forest");
		put(0x589C6C, "old_growth_birch_forest");
		put(0x40511A, "dark_forest");
		put(0xC8C8C0, "pale_garden");
		put(0x0B6659, "taiga");
		put(0x31554A, "snowy_taiga");
		put(0x596651, "old_growth_pine_taiga");
		put(0x818E79, "old_growth_spruce_taiga");
		put(0x537B09, "jungle");
		put(0x628B17, "sparse_jungle");
		put(0x768E14, "bamboo_jungle");
		put(0xBDB25F, "savanna");
		put(0xA79D64, "savanna_plateau");
		put(0xE5DA87, "windswept_savanna");
		put(0x606060, "windswept_hills");
		put(0x789878, "windswept_gravelly_hills");
		put(0x507050, "windswept_forest");
		put(0xD94515, "badlands");
		put(0xFF6D3D, "eroded_badlands");
		put(0xB09765, "wooded_badlands");
		put(0x60A445, "meadow");
		put(0xFFC0E0, "cherry_grove");
		put(0x47875A, "grove");
		put(0xC4C4C4, "snowy_slopes");
		put(0xA0A0A0, "frozen_peaks");
		put(0xDCDCC8, "jagged_peaks");
		put(0x7B8F74, "stony_peaks");
		put(0xFF00FF, "mushroom_fields");
		put(0x4E6E3E, "lush_caves");
		put(0x7B6E5F, "dripstone_caves");
		put(0x0E2A2E, "deep_dark");
		put(0xC8C060, "sulfur_caves");
		put(0xBF3B3B, "nether_wastes");
		put(0x5E3830, "soul_sand_valley");
		put(0xDD0808, "crimson_forest");
		put(0x49907B, "warped_forest");
		put(0x403636, "basalt_deltas");
	}

	private BiomeColors() {
	}

	private static void put(int rgb, String... ids) {
		for (String id : ids) {
			COLORS.put(id, 0xFF000000 | rgb);
		}
	}

	static int of(String id) {
		Integer c = COLORS.get(id);
		if (c != null) {
			return c;
		}
		int h = id.hashCode();
		return 0xFF000000 | (h & 0x7F7F7F) + 0x303030;
	}
}
