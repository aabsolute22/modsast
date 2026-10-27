package io.github.aabsolute22.seedcracker.world;

import java.util.Set;

/**
 * Block ids reduced to what survives structure processors and random palettes: mossy, cracked, aged copper,
 * crying obsidian, the wood type of a shipwreck and so on compare equal. Works on ids without namespace.
 */
public final class Canon {
	private static final String[] PREFIXES = {"waxed_", "exposed_", "weathered_", "oxidized_", "mossy_", "cracked_", "infested_", "suspicious_",
			"gilded_", "crying_", "stripped_"};
	private static final String[] WOODS = {"dark_oak_", "pale_oak_", "oak_", "spruce_", "birch_", "jungle_", "acacia_", "mangrove_", "cherry_",
			"bamboo_", "crimson_", "warped_"};

	/** Never compared: air, liquids, things players take or break, plants, gravity blocks, redstone bits. */
	private static final Set<String> IGNORED = Set.of("air", "cave_air", "void_air", "structure_void", "jigsaw", "structure_block", "water", "lava",
			"fire", "soul_fire", "chest", "trapped_chest", "barrel", "snow", "dirt_path", "sand", "red_sand", "gravel", "ladder", "lever",
			"tripwire", "tripwire_hook", "redstone_wire", "repeater", "comparator", "cobweb", "vine", "short_grass", "tall_grass", "fern",
			"large_fern", "dandelion", "poppy", "flower_pot", "cake", "decorated_pot", "glass_pane", "iron_chain", "chain", "spawner",
			"trial_spawner", "vault", "brewing_stand", "cauldron", "water_cauldron", "sea_pickle", "seagrass", "tall_seagrass", "kelp",
			"kelp_plant", "dead_bush", "sweet_berry_bush", "hanging_roots", "glow_lichen", "moss_carpet", "pointed_dripstone");

	/** Too common in natural terrain to anchor a search on. */
	private static final Set<String> NATURAL = Set.of("stone", "deepslate", "dirt", "sandstone", "red_sandstone", "terracotta", "clay", "netherrack",
			"blackstone", "basalt", "smooth_basalt", "soul_sand", "soul_soil", "magma_block", "glowstone", "tuff", "calcite", "granite", "diorite",
			"andesite", "ice", "packed_ice", "blue_ice", "snow_block", "powder_snow", "obsidian", "wood_log", "wood_leaves", "wood_stem", "wood_wood",
			"wood_hyphae", "mud", "packed_mud", "moss_block", "dripstone_block", "bedrock", "sculk", "nether_wart_block", "warped_wart_block",
			"shroomlight", "prismarine", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "cobblestone", "mud_bricks", "wood_planks",
			"wood_slab", "wood_stairs", "wood_fence", "stone_bricks", "nether_bricks");

	private Canon() {
	}

	public static String of(String id) {
		String s = id;
		boolean changed = true;
		while (changed) {
			changed = false;
			for (String p : PREFIXES) {
				if (s.startsWith(p) && s.length() > p.length()) {
					s = s.substring(p.length());
					changed = true;
				}
			}
		}
		for (String w : WOODS) {
			if (s.startsWith(w)) {
				s = "wood_" + s.substring(w.length());
				break;
			}
		}
		return switch (s) {
			case "grass_block", "coarse_dirt", "rooted_dirt", "podzol", "mycelium" -> "dirt";
			default -> s;
		};
	}

	public static boolean ignored(String canon) {
		return IGNORED.contains(canon) || canon.contains("torch") || canon.contains("lantern") || canon.contains("candle") || canon.contains("door")
				|| canon.endsWith("_pane") || canon.endsWith("carpet") || canon.endsWith("_bed") || canon.contains("banner") || canon.contains("sign")
				|| canon.startsWith("potted_") || canon.endsWith("_button") || canon.endsWith("_sapling") || canon.endsWith("_head")
				|| canon.endsWith("_skull") || canon.startsWith("sculk") || canon.endsWith("_tulip") || canon.endsWith("_orchid")
				|| canon.endsWith("_flower") || canon.endsWith("_mushroom") || canon.endsWith("leaves") || canon.endsWith("_coral")
				|| canon.endsWith("_coral_fan") || canon.endsWith("_coral_wall_fan") || canon.endsWith("_trapdoor") || canon.endsWith("_pressure_plate");
	}

	/** Whether a block type is rare enough in natural terrain to be worth anchoring on. */
	public static boolean anchorable(String canon) {
		if (NATURAL.contains(canon) || canon.endsWith("_ore") || canon.endsWith("_terracotta") && !canon.contains("glazed")) {
			return false;
		}
		return !canon.startsWith("amethyst");
	}
}
