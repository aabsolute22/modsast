package io.github.aabsolute22.seedcracker.client;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;

import io.github.aabsolute22.seedcracker.core.StructureKind;
import io.github.aabsolute22.seedcracker.world.Background;
import io.github.aabsolute22.seedcracker.world.SeedWorld;
import io.github.aabsolute22.seedcracker.world.Worldgen;

/**
 * Answers "what is near me" from the vanilla world model on the background thread. Callers always get the last
 * finished answer immediately (or null while the first one is computed) and a refresh is queued when it is stale.
 */
public final class Locator {
	/** One finished query: where it was asked and what came back. */
	private record Result<T>(T value, double x, double z, int radius, long seed) {
	}

	private static final double MOVE_REFRESH = 160;

	private volatile Long seed;
	private volatile SeedWorld overworld;
	private volatile SeedWorld nether;
	private final Map<String, Result<List<SeedWorld.Found>>> structures = new ConcurrentHashMap<>();
	private final Map<String, Result<List<BlockPos>>> biomes = new ConcurrentHashMap<>();
	private final Map<Long, SeedWorld.Found> details = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	public void setSeed(Long seed) {
		if (seed == null ? this.seed == null : seed.equals(this.seed)) {
			return;
		}
		this.seed = seed;
		overworld = null;
		nether = null;
		structures.clear();
		biomes.clear();
		details.clear();
	}

	public Long seed() {
		return seed;
	}

	/** Runs on the background thread only. */
	private SeedWorld world(SeedWorld.Dim dim) {
		Long s = seed;
		Worldgen wg = Worldgen.now();
		if (s == null || wg == null) {
			return null;
		}
		SeedWorld w = dim == SeedWorld.Dim.OVERWORLD ? overworld : nether;
		if (w == null || w.seed() != s) {
			w = new SeedWorld(wg, s, dim);
			if (dim == SeedWorld.Dim.OVERWORLD) {
				overworld = w;
			} else {
				nether = w;
			}
		}
		return w;
	}

	private static boolean stale(Result<?> r, double x, double z, int radius, Long seed) {
		return r == null || seed == null || r.seed != seed || r.radius != radius || Math.hypot(r.x - x, r.z - z) > MOVE_REFRESH;
	}

	/** Structures of {@code kind} near (x, z), nearest first; null until the first answer is ready. */
	public List<SeedWorld.Found> structures(SeedWorld.Dim dim, StructureKind kind, double x, double z, int radius) {
		Long s = seed;
		if (s == null || dim == null) {
			return null;
		}
		String key = dim + ":" + kind.id() + ":" + radius;
		Result<List<SeedWorld.Found>> r = structures.get(key);
		if (stale(r, x, z, radius, s) && inFlight.add(key)) {
			Background.EXECUTOR.submit(() -> {
				try {
					SeedWorld w = world(dim);
					if (w != null) {
						structures.put(key, new Result<>(w.near(kind, x, z, radius), x, z, radius, w.seed()));
					}
				} catch (Throwable t) {
					SeedCrackerClient.LOG.warn("Locating {} failed", kind.id(), t);
				} finally {
					inFlight.remove(key);
				}
			});
		}
		return r != null && r.seed == s ? r.value : null;
	}

	/** Nearest spots of a biome; null until ready. */
	public List<BlockPos> biomes(SeedWorld.Dim dim, String biome, double x, double z, int radius) {
		Long s = seed;
		if (s == null || dim == null) {
			return null;
		}
		String key = dim + ":" + biome + ":" + radius;
		Result<List<BlockPos>> r = biomes.get(key);
		if (stale(r, x, z, radius, s) && inFlight.add("biome:" + key)) {
			Background.EXECUTOR.submit(() -> {
				try {
					SeedWorld w = world(dim);
					if (w != null) {
						int step = radius > 4000 ? 64 : 32;
						biomes.put(key, new Result<>(w.findBiome(biome, x, z, radius, step, 6), x, z, radius, w.seed()));
					}
				} catch (Throwable t) {
					SeedCrackerClient.LOG.warn("Biome search {} failed", biome, t);
				} finally {
					inFlight.remove("biome:" + key);
				}
			});
		}
		return r != null && r.seed == s ? r.value : null;
	}

	/** The structure with its pieces built (exact box, portal room, spawners); the plain one until ready. */
	public SeedWorld.Found details(SeedWorld.Dim dim, SeedWorld.Found f) {
		SeedWorld.Found d = details.get(f.key());
		if (d != null) {
			return d;
		}
		String key = "details:" + f.key();
		if (inFlight.add(key)) {
			Background.EXECUTOR.submit(() -> {
				try {
					SeedWorld w = world(dim);
					if (w != null) {
						details.put(f.key(), w.details(f));
					}
				} catch (Throwable t) {
					SeedCrackerClient.LOG.warn("Structure details failed", t);
				} finally {
					inFlight.remove(key);
				}
			});
		}
		return f;
	}

	/** Biome at a block for the map, computed on demand by the caller's background job. */
	public SeedWorld worldNow(SeedWorld.Dim dim) {
		return dim == SeedWorld.Dim.OVERWORLD ? overworld : nether;
	}

	/** Makes sure the world of {@code dim} exists, so the map can sample biomes on the background thread. */
	public void prepare(SeedWorld.Dim dim) {
		if (seed != null && worldNow(dim) == null && inFlight.add("prepare:" + dim)) {
			Background.EXECUTOR.submit(() -> {
				try {
					world(dim);
				} finally {
					inFlight.remove("prepare:" + dim);
				}
			});
		}
	}

	public boolean busy() {
		return !inFlight.isEmpty();
	}
}
