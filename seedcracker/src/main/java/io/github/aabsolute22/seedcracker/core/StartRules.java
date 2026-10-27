package io.github.aabsolute22.seedcracker.core;

import java.util.Set;

/**
 * Structures whose start piece rotation (or direction, for code-built pieces) is the very first draw of the
 * start chunk's large-feature random, so a detected rotation is worth 2 more bits. Verified against vanilla.
 */
public final class StartRules {
	public static final Set<String> ROTATION_FIRST = Set.of("village_plains", "village_desert", "village_savanna", "village_snowy",
			"village_taiga", "pillager_outpost", "igloo", "shipwreck", "shipwreck_beached", "ancient_city", "trail_ruins", "bastion_remnant",
			"ocean_ruin_cold", "ocean_ruin_warm", "desert_pyramid", "jungle_pyramid", "swamp_hut", "mansion", "monument", "fortress");

	private StartRules() {
	}
}
