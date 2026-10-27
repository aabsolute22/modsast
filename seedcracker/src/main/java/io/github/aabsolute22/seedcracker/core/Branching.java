package io.github.aabsolute22.seedcracker.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Observations with several possible start chunks (a structure added by hand from where the player stood) only
 * filter seeds; the search itself is driven by exact ones. When that leaves the search slow, fixing some of them to one
 * candidate at a time splits it into branches that are each fast; the union of their results is the answer.
 */
public final class Branching {
	/** Most branches a plan may have. */
	public static final int MAX_BRANCHES = 64;

	private Branching() {
	}

	/** Branches to search, each a complete list of observations; without ambiguous observations just the list itself. */
	public static List<List<Observation>> plan(List<Observation> observations, int maxSeeds) {
		List<Observation> base = new ArrayList<>();
		for (Observation o : observations) {
			base.add(prune(o));
		}
		List<List<Observation>> current = List.of(base);
		double best = cost(current, maxSeeds);
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < base.size(); i++) {
			int n = base.get(i).candidates().size();
			if (n > 1 && n <= 16) {
				order.add(i);
			}
		}
		order.sort(Comparator.comparingInt(i -> base.get(i).candidates().size()));
		for (int i : order) {
			List<List<Observation>> next = new ArrayList<>();
			for (List<Observation> branch : current) {
				Observation o = branch.get(i);
				for (Observation.Candidate c : o.candidates()) {
					List<Observation> b = new ArrayList<>(branch);
					b.set(i, new Observation(o.spread(), List.of(c), o.pick(), o.pickWeights(), o.outpost()));
					next.add(b);
				}
			}
			if (next.size() > MAX_BRANCHES) {
				break;
			}
			double c = cost(next, maxSeeds);
			// Splitting pays when the exact candidate lets the search lift bits it could not before.
			if (c < best * 0.7) {
				best = c;
				current = next;
			}
		}
		return current;
	}

	/** Estimated nanoseconds on one core for all branches together. */
	public static double cost(List<List<Observation>> branches, int maxSeeds) {
		double total = 0;
		for (List<Observation> b : branches) {
			total += new StructureSeedCracker(b, maxSeeds).estimatedNanos();
		}
		return total;
	}

	/** Drops candidates the structure set can never place (offset past the region's limit). */
	static Observation prune(Observation o) {
		if (o.isExact()) {
			return o;
		}
		Spread s = o.spread();
		List<Observation.Candidate> kept = new ArrayList<>();
		for (Observation.Candidate c : o.candidates()) {
			int ox = c.chunkX() - s.region(c.chunkX()) * s.spacing();
			int oz = c.chunkZ() - s.region(c.chunkZ()) * s.spacing();
			if (ox < s.limit() && oz < s.limit()) {
				kept.add(c);
			}
		}
		return kept.isEmpty() || kept.size() == o.candidates().size() ? o
				: new Observation(s, List.copyOf(kept), o.pick(), o.pickWeights(), o.outpost());
	}
}
