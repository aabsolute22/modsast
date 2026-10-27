package io.github.aabsolute22.seedcracker.world;

import java.util.ArrayList;
import java.util.List;

import io.github.aabsolute22.seedcracker.core.StructureKind;

/**
 * Finds start pieces from one anchor block: every variant that has that block type gives one possible template
 * position; only positions whose start chunk origin is chunk-aligned survive, and those are scored block by block.
 */
public final class TemplateMatcher {
	/** Canonical block id at a world position, or null if that position is not loaded. */
	public interface BlockSource {
		String canonAt(int x, int y, int z);
	}

	public record Match(StartTemplates.Variant variant, int chunkX, int chunkZ, int baseY, int matched, int checked) {
		public StructureKind kind() {
			return variant.spec().kind();
		}

		public double score() {
			return checked == 0 ? 0 : (double) matched / checked;
		}
	}

	private final StartTemplates templates;

	public TemplateMatcher(StartTemplates templates) {
		this.templates = templates;
	}

	public StartTemplates templates() {
		return templates;
	}

	/** Matches anchored on the block {@code canon} at (x, y, z); several variants may agree on one start. */
	public List<Match> fromAnchor(int x, int y, int z, String canon, BlockSource source) {
		return fromAnchor(x, y, z, canon, source, spec -> true);
	}

	/** Same, only trying specs the filter accepts (dimension, biome). */
	public List<Match> fromAnchor(int x, int y, int z, String canon, BlockSource source, java.util.function.Predicate<StartTemplates.Spec> filter) {
		List<Match> out = new ArrayList<>();
		for (StartTemplates.Variant v : templates.variantsAnchoredOn(canon)) {
			if (!filter.test(v.spec())) {
				continue;
			}
			for (int i : v.anchors()) {
				int ox = x - v.dx()[i];
				int oz = z - v.dz()[i];
				if ((ox & 15) != 0 || (oz & 15) != 0) {
					continue;
				}
				Match m = score(v, ox, y - v.dy()[i], oz, source);
				if (m != null) {
					out.add(m);
				}
			}
		}
		return out;
	}

	/**
	 * Best {matched, checked} over the chunk-aligned placements an anchor allows, ignoring the threshold; a high ratio
	 * that still fails means the start is there but some of its blocks are not right yet.
	 */
	public int[] best(int x, int y, int z, String canon, BlockSource source, java.util.function.Predicate<StartTemplates.Spec> filter) {
		int[] best = {0, 0};
		for (StartTemplates.Variant v : templates.variantsAnchoredOn(canon)) {
			if (!filter.test(v.spec())) {
				continue;
			}
			for (int i : v.anchors()) {
				int ox = x - v.dx()[i];
				int oz = z - v.dz()[i];
				if ((ox & 15) != 0 || (oz & 15) != 0) {
					continue;
				}
				int[] c = count(v, ox, y - v.dy()[i], oz, source);
				if (c[1] >= 10 && (long) c[0] * Math.max(1, best[1]) > (long) best[0] * c[1]) {
					best = c;
				}
			}
		}
		return best;
	}

	/** Plain counts {matched, checked} of a variant at a placement, for diagnostics. */
	public static int[] count(StartTemplates.Variant v, int originX, int baseY, int originZ, BlockSource source) {
		int matched = 0;
		int checked = 0;
		for (int i = 0; i < v.dx().length; i++) {
			String c = source.canonAt(originX + v.dx()[i], baseY + v.dy()[i], originZ + v.dz()[i]);
			if (c != null) {
				checked++;
				if (c.equals(v.canon()[i])) {
					matched++;
				}
			}
		}
		return new int[] {matched, checked};
	}

	/** Scores a variant placed at (originX, baseY, originZ); null if it clearly does not fit. */
	public static Match score(StartTemplates.Variant v, int originX, int baseY, int originZ, TemplateMatcher.BlockSource source) {
		int n = v.dx().length;
		double threshold = v.spec().threshold();
		int allowedMisses = (int) Math.floor(n * (1 - threshold));
		int matched = 0;
		int checked = 0;
		int misses = 0;
		for (int i = 0; i < n; i++) {
			String c = source.canonAt(originX + v.dx()[i], baseY + v.dy()[i], originZ + v.dz()[i]);
			if (c == null) {
				continue;
			}
			checked++;
			if (c.equals(v.canon()[i])) {
				matched++;
			} else if (++misses > allowedMisses) {
				return null;
			}
		}
		int minChecked = Math.min(n, Math.max(10, n / 2));
		if (checked < minChecked || matched < threshold * checked) {
			return null;
		}
		return new Match(v, originX >> 4, originZ >> 4, baseY, matched, checked);
	}
}
