package io.github.aabsolute22.endcityfinder.core;

public enum EndBiome {
	THE_END,
	HIGHLANDS,
	MIDLANDS,
	BARRENS,
	SMALL_ISLANDS;

	public boolean allowsEndCity() {
		return this == HIGHLANDS || this == MIDLANDS;
	}
}
