package io.github.aabsolute22.seedcracker.core;

import java.util.List;

/**
 * One structure start seen in the world: it began in one of {@code candidates} (usually exactly one chunk).
 *
 * @param spread the structure set it belongs to
 * @param candidates possible start chunks; {@code rotation} is the start rotation (0..3) or -1 if unknown
 * @param pick for sets with several structures: the index the first weighted draw must give, -1 for no constraint
 * @param pickWeights weights of the structures of the set, in set order (only used with {@code pick})
 * @param outpost an observed pillager outpost: also checks its frequency draw and the village exclusion zone
 */
public record Observation(Spread spread, List<Candidate> candidates, int pick, int[] pickWeights, boolean outpost) {
	public record Candidate(int chunkX, int chunkZ, int rotation) {
	}

	public static final int[] NETHER_WEIGHTS = {2, 3};
	public static final int PICK_FORTRESS = 0;
	public static final int PICK_BASTION = 1;

	public static Observation exact(Spread spread, int chunkX, int chunkZ, int rotation) {
		return new Observation(spread, List.of(new Candidate(chunkX, chunkZ, rotation)), -1, null, spread == Spread.PILLAGER_OUTPOSTS);
	}

	public static Observation exact(Spread spread, int chunkX, int chunkZ) {
		return exact(spread, chunkX, chunkZ, -1);
	}

	public Observation withPick(int pick, int[] weights) {
		return new Observation(spread, candidates, pick, weights, outpost);
	}

	public boolean isExact() {
		return candidates.size() == 1;
	}

	/** Whether the structure set's own placement check accepts this observation for {@code seed}. */
	public boolean matches(long seed) {
		for (Candidate c : candidates) {
			if (matches(seed, c)) {
				return true;
			}
		}
		return false;
	}

	public boolean matches(long seed, Candidate c) {
		if (!spread.isStructureChunk(seed, c.chunkX(), c.chunkZ())) {
			return false;
		}
		if (outpost && Spread.outpostExcluded(seed, c.chunkX(), c.chunkZ())) {
			return false;
		}
		if (pick >= 0 && Spread.firstWeightedPick(seed, c.chunkX(), c.chunkZ(), pickWeights) != pick) {
			return false;
		}
		return c.rotation() < 0 || Spread.startRotation(seed, c.chunkX(), c.chunkZ()) == c.rotation();
	}

	/** Chance that a random seed satisfies this observation; smaller is more informative. */
	public double selectivity() {
		double p = 0;
		for (Candidate c : candidates) {
			int ox = c.chunkX() - spread.region(c.chunkX()) * spread.spacing();
			int oz = c.chunkZ() - spread.region(c.chunkZ()) * spread.spacing();
			double q = spread.offsetProbability(ox) * spread.offsetProbability(oz) * Math.min(1.0, spread.frequency());
			if (c.rotation() >= 0) {
				q /= 4;
			}
			p += q;
		}
		if (pick >= 0) {
			int total = 0;
			for (int w : pickWeights) {
				total += w;
			}
			p *= (double) pickWeights[pick] / total;
		}
		return Math.min(1.0, p);
	}

	/** Bits of information about the structure seed. */
	public double bits() {
		return -Math.log(selectivity()) / Math.log(2);
	}
}
