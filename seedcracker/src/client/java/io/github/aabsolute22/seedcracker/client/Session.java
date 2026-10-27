package io.github.aabsolute22.seedcracker.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.github.aabsolute22.seedcracker.core.Observation;
import io.github.aabsolute22.seedcracker.core.StartRules;
import io.github.aabsolute22.seedcracker.core.StructureKind;
import io.github.aabsolute22.seedcracker.world.BiomeCheck;

/** Everything the mod knows about one server or singleplayer world; saved as JSON. */
public final class Session {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_BIOME_SAMPLES = 64;
	private static final int BIOME_SAMPLE_SPACING = 48;

	/** A structure start the player found (automatically or by hand). */
	public static final class Obs {
		public int id;
		public String kind;
		public String dim;
		/** Possible start chunks as {chunkX, chunkZ, rotation or -1}. */
		public List<int[]> candidates = new ArrayList<>();
		/** Index of the structure in a multi-structure set the first draw must give, or -1. */
		public int pick = -1;
		public boolean auto;
		public int x;
		public int y;
		public int z;
		public String note = "";

		public StructureKind kindEnum() {
			return StructureKind.byId(kind);
		}

		public String describe() {
			StringBuilder b = new StringBuilder();
			StructureKind k = kindEnum();
			b.append(k != null ? SeedCrackerClient.name(k) : kind).append(" · чанк ");
			if (candidates.size() == 1) {
				int[] c = candidates.get(0);
				b.append(c[0]).append(' ').append(c[1]);
				if (c[2] >= 0) {
					b.append(" · поворот ").append(c[2] * 90).append('°');
				}
			} else {
				b.append("один из ").append(candidates.size());
			}
			return b.toString();
		}
	}

	public static final class Target {
		public String kind;
		/** Start chunk when the target is a structure, so its details can refine the point later. */
		public Integer chunkX;
		public Integer chunkZ;
		public boolean refined;
		public String dim;
		public int x;
		public int y;
		public int z;
		public String label;
	}

	public static final class Sample {
		public int qx;
		public int qy;
		public int qz;
		public String biome;
	}

	/** The saved part. */
	public static final class Data {
		public List<Obs> observations = new ArrayList<>();
		public Map<String, List<Sample>> biomes = new HashMap<>();
		public Long hashedSeed;
		public Long seed;
		/** Where the seed came from: "cracked", "manual", "singleplayer". */
		public String seedSource;
		public List<Long> structureSeeds = new ArrayList<>();
		public boolean structureSeedsOverflow;
		public Set<Long> visited = new HashSet<>();
		public Target target;
		public int nextId = 1;
	}

	public enum Phase {
		LOADING, COLLECTING, CRACKING, UPPER_BITS, AMBIGUOUS, SOLVED, CONTRADICTION, SLOW
	}

	private final String key;
	private final Data data;
	private boolean dirty;
	private volatile Phase phase = Phase.LOADING;
	private volatile String detail = "";
	private volatile java.util.function.DoubleSupplier progress = () -> -1;
	private volatile long etaMillis = -1;

	private Session(String key, Data data) {
		this.key = key;
		this.data = data;
	}

	private static Path file(String key) {
		return SeedCrackerClient.configDir().resolve("worlds").resolve(key.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json");
	}

	public static Session open(String key) {
		Data data = null;
		try {
			Path f = file(key);
			if (Files.exists(f)) {
				data = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Data.class);
			}
		} catch (Exception e) {
			SeedCrackerClient.LOG.warn("Could not read {}", key, e);
		}
		if (data == null) {
			data = new Data();
		}
		if (data.observations == null) {
			data.observations = new ArrayList<>();
		}
		if (data.biomes == null) {
			data.biomes = new HashMap<>();
		}
		if (data.structureSeeds == null) {
			data.structureSeeds = new ArrayList<>();
		}
		if (data.visited == null) {
			data.visited = new HashSet<>();
		}
		return new Session(key, data);
	}

	public synchronized void save() {
		try {
			Path f = file(key);
			Files.createDirectories(f.getParent());
			Files.writeString(f, GSON.toJson(data), StandardCharsets.UTF_8);
			dirty = false;
		} catch (IOException e) {
			SeedCrackerClient.LOG.warn("Could not save {}", key, e);
		}
	}

	public void saveIfDirty() {
		if (dirty) {
			save();
		}
	}

	public void markDirty() {
		dirty = true;
	}

	public String key() {
		return key;
	}

	public Data data() {
		return data;
	}

	// ---------------------------------------------------------------- status

	public Phase phase() {
		return phase;
	}

	public String detail() {
		return detail;
	}

	public void setStatus(Phase phase, String detail) {
		this.phase = phase;
		this.detail = detail;
	}

	public double progress() {
		return progress.getAsDouble();
	}

	public void setProgress(java.util.function.DoubleSupplier progress, long etaMillis) {
		this.progress = progress;
		this.etaMillis = etaMillis;
	}

	public long etaMillis() {
		return etaMillis;
	}

	public Long seed() {
		return data.seed;
	}

	public synchronized void setSeed(Long seed, String source) {
		data.seed = seed;
		data.seedSource = seed == null ? null : source;
		markDirty();
	}

	// ---------------------------------------------------------------- observations

	public synchronized List<Obs> observations() {
		return new ArrayList<>(data.observations);
	}

	/**
	 * Adds a found structure; returns it, or null if it was already known. A repeat sighting can still add the
	 * rotation when the earlier one had none.
	 */
	public synchronized Obs addObservation(StructureKind kind, String dim, List<int[]> candidates, int pick, boolean auto, int x, int y, int z, String note) {
		for (Obs o : data.observations) {
			if (!o.kind.equals(kind.id())) {
				continue;
			}
			for (int[] a : o.candidates) {
				for (int[] b : candidates) {
					if (a[0] == b[0] && a[1] == b[1]) {
						boolean newExact = candidates.size() == 1;
						boolean oldExact = o.candidates.size() == 1;
						boolean better = newExact && (!oldExact || o.candidates.get(0)[2] < 0 && candidates.get(0)[2] >= 0);
						if (better) {
							o.candidates = new ArrayList<>(candidates);
							markDirty();
							return o;
						}
						return null;
					}
				}
			}
		}
		Obs o = new Obs();
		o.id = data.nextId++;
		o.kind = kind.id();
		o.dim = dim;
		o.candidates = new ArrayList<>(candidates);
		o.pick = pick;
		o.auto = auto;
		o.x = x;
		o.y = y;
		o.z = z;
		o.note = note;
		data.observations.add(o);
		markDirty();
		return o;
	}

	public synchronized boolean removeObservation(int id) {
		boolean removed = data.observations.removeIf(o -> o.id == id);
		if (removed) {
			markDirty();
		}
		return removed;
	}

	/** The observations in the cracker's terms; strongholds and unknown kinds are left out. */
	public synchronized List<Observation> coreObservations() {
		List<Observation> out = new ArrayList<>();
		for (Obs o : data.observations) {
			StructureKind k = o.kindEnum();
			if (k == null || k.spread == null) {
				continue;
			}
			boolean rotationCounts = k.structures.stream().allMatch(StartRules.ROTATION_FIRST::contains);
			List<Observation.Candidate> cands = new ArrayList<>();
			for (int[] c : o.candidates) {
				cands.add(new Observation.Candidate(c[0], c[1], rotationCounts ? c[2] : -1));
			}
			int[] weights = o.pick >= 0 ? Observation.NETHER_WEIGHTS : null;
			out.add(new Observation(k.spread, cands, o.pick, weights, k == StructureKind.PILLAGER_OUTPOST));
		}
		return out;
	}

	public synchronized double informationBits() {
		double bits = 0;
		for (Observation o : coreObservations()) {
			bits += o.bits();
		}
		return bits;
	}

	// ---------------------------------------------------------------- biomes

	/** Keeps a spread-out set of biome cells per dimension for checking candidate seeds. */
	public synchronized boolean addBiomeSample(String dim, int qx, int qy, int qz, String biome) {
		List<Sample> list = data.biomes.computeIfAbsent(dim, k -> new ArrayList<>());
		if (list.size() >= MAX_BIOME_SAMPLES) {
			return false;
		}
		int spacing = BIOME_SAMPLE_SPACING / 4;
		for (Sample s : list) {
			if (Math.abs(s.qx - qx) < spacing && Math.abs(s.qz - qz) < spacing) {
				return false;
			}
		}
		Sample s = new Sample();
		s.qx = qx;
		s.qy = qy;
		s.qz = qz;
		s.biome = biome;
		list.add(s);
		markDirty();
		return true;
	}

	public synchronized List<BiomeCheck.Sample> biomeSamples(String dim) {
		List<BiomeCheck.Sample> out = new ArrayList<>();
		for (Sample s : data.biomes.getOrDefault(dim, List.of())) {
			out.add(new BiomeCheck.Sample(s.qx, s.qy, s.qz, s.biome));
		}
		return out;
	}

	public synchronized int biomeSampleCount() {
		int n = 0;
		for (List<Sample> l : data.biomes.values()) {
			n += l.size();
		}
		return n;
	}

	// ---------------------------------------------------------------- hashed seed, candidates

	public synchronized void setHashedSeed(long hashed) {
		if (data.hashedSeed == null || data.hashedSeed != hashed) {
			data.hashedSeed = hashed;
			markDirty();
		}
	}

	public Long hashedSeed() {
		return data.hashedSeed;
	}

	public synchronized void setStructureSeeds(List<Long> seeds, boolean overflow) {
		data.structureSeeds = new ArrayList<>(seeds.size() > 4096 ? seeds.subList(0, 4096) : seeds);
		data.structureSeedsOverflow = overflow;
		markDirty();
	}

	public synchronized List<Long> structureSeeds() {
		return new ArrayList<>(data.structureSeeds);
	}

	public boolean structureSeedsOverflow() {
		return data.structureSeedsOverflow;
	}

	// ---------------------------------------------------------------- visited, target

	public synchronized boolean isVisited(long key) {
		return data.visited.contains(key);
	}

	public synchronized void setVisited(long key, boolean visited) {
		if (visited ? data.visited.add(key) : data.visited.remove(key)) {
			markDirty();
		}
	}

	public synchronized Target target() {
		return data.target;
	}

	public synchronized void setTarget(Target target) {
		data.target = target;
		markDirty();
	}

	public synchronized void reset() {
		Data fresh = new Data();
		data.observations = fresh.observations;
		data.biomes = fresh.biomes;
		data.seed = null;
		data.seedSource = null;
		data.structureSeeds = fresh.structureSeeds;
		data.structureSeedsOverflow = false;
		data.visited = fresh.visited;
		data.target = null;
		data.nextId = 1;
		markDirty();
	}
}
