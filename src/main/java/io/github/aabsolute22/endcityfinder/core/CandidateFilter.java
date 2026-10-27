package io.github.aabsolute22.endcityfinder.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exact checks the brute force cannot afford: every observed city must really generate for the seed, no city may
 * start in a loaded chunk that had none, and the noise terrain must match what the client saw.
 */
public final class CandidateFilter {
	/** A column the client has seen: top end stone y, or -1 for an empty column. */
	public record TerrainSample(int x, int z, int surfaceY) {
	}

	public record Verdict(long seed, boolean accepted, String reason) {
	}

	private final Placement placement;
	private final List<SeedCracker.City> cities;
	private final Map<Long, Set<Long>> emptyByRegion = new HashMap<>();
	private final List<TerrainSample> samples;

	public CandidateFilter(Placement placement, List<SeedCracker.City> cities, Iterable<Long> emptyChunks, List<TerrainSample> samples) {
		this.placement = placement;
		this.cities = cities;
		for (long chunk : emptyChunks) {
			int cx = Placement.unpackX(chunk);
			int cz = Placement.unpackZ(chunk);
			long region = Placement.pack(placement.region(cx), placement.region(cz));
			emptyByRegion.computeIfAbsent(region, r -> new HashSet<>()).add(chunk);
		}
		this.samples = samples;
	}

	public Verdict check(long seed) {
		EndWorld world = new EndWorld(seed, placement);
		// Cheap biome pass first: most wrong seeds put some observed city outside highlands/midlands.
		for (SeedCracker.City city : cities) {
			boolean any = false;
			for (SeedCracker.Candidate c : city.candidates()) {
				if (world.islands().biomeOfChunk(c.chunkX(), c.chunkZ()).allowsEndCity()) {
					any = true;
					break;
				}
			}
			if (!any) {
				return new Verdict(seed, false, "observed city outside city biomes");
			}
		}
		for (SeedCracker.City city : cities) {
			boolean ok = false;
			for (SeedCracker.Candidate c : city.candidates()) {
				if (!world.hasCityStartingIn(c.chunkX(), c.chunkZ())) {
					continue;
				}
				EndCity generated = world.cityAt(c.chunkX(), c.chunkZ());
				if (c.rotation() < 0 || generated.rotation().ordinal() == c.rotation()) {
					ok = true;
					break;
				}
			}
			if (!ok) {
				return new Verdict(seed, false, "observed city would not generate");
			}
		}
		for (Map.Entry<Long, Set<Long>> e : emptyByRegion.entrySet()) {
			long chunk = placement.potentialChunk(seed, Placement.unpackX(e.getKey()), Placement.unpackZ(e.getKey()));
			if (e.getValue().contains(chunk) && world.cityAt(Placement.unpackX(chunk), Placement.unpackZ(chunk)) != null) {
				return new Verdict(seed, false, "predicts a city in an explored empty chunk");
			}
		}
		if (!terrainMatches(world, samples)) {
			return new Verdict(seed, false, "terrain mismatch");
		}
		return new Verdict(seed, true, "ok");
	}

	/**
	 * Whether the noise terrain of this world matches what the client saw, allowing for 10% of columns
	 * being changed by players.
	 */
	static boolean terrainMatches(EndWorld world, List<TerrainSample> samples) {
		int mismatches = 0;
		int limit = 1 + samples.size() / 10;
		for (TerrainSample s : samples) {
			int predicted = world.terrain().surfaceY(s.x(), s.z());
			if (predicted == s.surfaceY()) {
				continue;
			}
			// Small floating islands are a feature, not noise terrain; ignore solid surprises there.
			if (predicted < 0 && world.islands().biomeOfChunk(s.x() >> 4, s.z() >> 4) == EndBiome.SMALL_ISLANDS) {
				continue;
			}
			if (++mismatches > limit) {
				return false;
			}
		}
		return true;
	}

	public List<Long> filter(List<Long> seeds) {
		List<Long> out = new ArrayList<>(seeds.parallelStream().filter(seed -> check(seed).accepted()).toList());
		java.util.Collections.sort(out);
		return out;
	}
}
