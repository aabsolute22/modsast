package io.github.aabsolute22.endcityfinder.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import io.github.aabsolute22.endcityfinder.core.CandidateFilter;
import io.github.aabsolute22.endcityfinder.core.EndCity;
import io.github.aabsolute22.endcityfinder.core.EndSpikes;
import io.github.aabsolute22.endcityfinder.core.EndWorld;
import io.github.aabsolute22.endcityfinder.core.LegacyRandom;
import io.github.aabsolute22.endcityfinder.core.Placement;
import io.github.aabsolute22.endcityfinder.core.SeedCracker;

/**
 * Everything the mod knows about the End of one world or server: observations, solver state, predictions.
 * Observations are changed on the client thread; solving and predicting run on a background worker.
 */
public final class FinderSession {
	private static final Gson GSON = new GsonBuilder().create();
	private static final int MAX_RAW_SEEDS = 400_000;
	private static final double MAX_EXPECTED_SEEDS = 300_000;
	private static final int MAX_SAMPLES = 600;
	private static final int MAX_WORLDS = 32;

	public enum Phase {
		NEED_PILLARS,
		NEED_CITIES,
		NEED_MORE_CITIES,
		SEARCHING,
		FILTERING,
		AMBIGUOUS,
		SOLVED,
		CONTRADICTION
	}

	/** A city the player found. {@code exact} cities pin down start chunk and rotation from its blocks. */
	public static final class ObservedCity {
		public int id;
		public int x;
		public int z;
		public boolean auto;
		public boolean exact;
		public List<int[]> candidates = new ArrayList<>();

		List<SeedCracker.Candidate> toCandidates() {
			List<SeedCracker.Candidate> list = new ArrayList<>();
			for (int[] c : candidates) {
				list.add(new SeedCracker.Candidate(c[0], c[1], c[2]));
			}
			return list;
		}

		public String describe() {
			if (candidates.size() == 1) {
				int[] c = candidates.get(0);
				return "чанк " + c[0] + " " + c[1] + (c[2] >= 0 ? " поворот " + c[2] * 90 + "°" : "");
			}
			return candidates.size() + " возможных чанков около " + x + " " + z;
		}
	}

	/** On-disk form. */
	private static final class Data {
		int version = 1;
		int[] spikes;
		List<ObservedCity> cities = new ArrayList<>();
		int nextId = 1;
		long[] empty = new long[0];
		List<int[]> samples = new ArrayList<>();
		long[] visited = new long[0];
		Long manualSeed;
		long[] candidates;
		int[] candidateKeys;
		int[] candidateCities;
		Long target;
	}

	private final String key;
	private final Path file;
	private final int[] spikes = new int[EndSpikes.COUNT];
	private final Map<Integer, ObservedCity> cities = new LinkedHashMap<>();
	private int nextId = 1;
	private final LongOpenHashSet emptyChunks = new LongOpenHashSet();
	private final List<int[]> samples = new ArrayList<>();
	private final LongOpenHashSet visited = new LongOpenHashSet();
	private Long manualSeed;
	private Long target;

	private List<Long> candidates;
	private int[] candidateKeys;
	private Set<Integer> candidateCities;

	private final ExecutorService worker = daemonExecutor("endcityfinder-solver");
	/** Separate from the solver so a long prediction never delays solving, and vice versa. */
	private final ExecutorService predictor = daemonExecutor("endcityfinder-predictor");
	private final AtomicBoolean solveQueued = new AtomicBoolean();
	private final AtomicBoolean predictQueued = new AtomicBoolean();
	private volatile SeedCracker activeCracker;
	private volatile Phase phase = Phase.NEED_PILLARS;
	private volatile String detail = "";
	private volatile List<EndWorld> worlds = List.of();
	private volatile List<EndCity> predicted = List.of();
	/** The worlds {@link #predicted} was computed for; stale results are dropped. */
	private volatile List<EndWorld> predictedFor = List.of();
	private volatile double predictedAtX = Double.NaN;
	private volatile double predictedAtZ = Double.NaN;
	private boolean dirty;
	private long lastSave;

	private static ExecutorService daemonExecutor(String name) {
		return Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
	}

	private FinderSession(String key, Path file) {
		this.key = key;
		this.file = file;
		Arrays.fill(spikes, -1);
	}

	public static FinderSession open(String key) {
		String name = key.replaceAll("[^a-zA-Z0-9._-]", "_");
		FinderSession session = new FinderSession(key, EndCityFinderClient.configDir().resolve("worlds").resolve(name + ".json"));
		session.load();
		session.requestSolve();
		return session;
	}

	public String key() {
		return key;
	}

	// ---------------------------------------------------------------- persistence

	private synchronized void load() {
		if (!Files.exists(file)) {
			return;
		}
		try {
			Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
			if (data == null) {
				return;
			}
			if (data.spikes != null && data.spikes.length == EndSpikes.COUNT) {
				System.arraycopy(data.spikes, 0, spikes, 0, EndSpikes.COUNT);
			}
			for (ObservedCity c : data.cities) {
				cities.put(c.id, c);
			}
			nextId = Math.max(data.nextId, cities.keySet().stream().mapToInt(i -> i + 1).max().orElse(1));
			emptyChunks.addAll(new LongOpenHashSet(data.empty));
			samples.addAll(data.samples);
			visited.addAll(new LongOpenHashSet(data.visited));
			manualSeed = data.manualSeed;
			target = data.target;
			if (data.candidates != null && data.candidateKeys != null && data.candidateCities != null) {
				candidates = new ArrayList<>();
				for (long c : data.candidates) {
					candidates.add(c);
				}
				candidateKeys = data.candidateKeys;
				candidateCities = new HashSet<>();
				for (int id : data.candidateCities) {
					candidateCities.add(id);
				}
			}
		} catch (Exception e) {
			EndCityFinderClient.LOG.warn("Could not read {}", file, e);
		}
	}

	public synchronized void save() {
		Data data = new Data();
		data.spikes = spikes.clone();
		data.cities = new ArrayList<>(cities.values());
		data.nextId = nextId;
		data.empty = emptyChunks.toLongArray();
		data.samples = new ArrayList<>(samples);
		data.visited = visited.toLongArray();
		data.manualSeed = manualSeed;
		data.target = target;
		if (candidates != null) {
			data.candidates = candidates.stream().mapToLong(Long::longValue).toArray();
			data.candidateKeys = candidateKeys;
			data.candidateCities = candidateCities.stream().mapToInt(Integer::intValue).toArray();
		}
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			dirty = false;
			lastSave = System.currentTimeMillis();
		} catch (IOException e) {
			EndCityFinderClient.LOG.warn("Could not save {}", file, e);
		}
	}

	/** Called every tick; writes changes at most every 15 seconds. */
	public void saveIfDirty() {
		if (dirty && System.currentTimeMillis() - lastSave > 15_000) {
			save();
		}
	}

	public void close() {
		SeedCracker cracker = activeCracker;
		if (cracker != null) {
			cracker.cancel();
		}
		worker.shutdownNow();
		predictor.shutdownNow();
		save();
	}

	// ---------------------------------------------------------------- observations

	public synchronized int[] spikes() {
		return spikes.clone();
	}

	public synchronized int knownSpikes() {
		int n = 0;
		for (int s : spikes) {
			if (s >= 0) {
				n++;
			}
		}
		return n;
	}

	public void setSpike(int index, int size) {
		synchronized (this) {
			if (spikes[index] == size) {
				return;
			}
			spikes[index] = size;
			dirty = true;
		}
		requestSolve();
	}

	public synchronized List<ObservedCity> cities() {
		return new ArrayList<>(cities.values());
	}

	/** Adds a city, merging it with an existing one that shares a candidate chunk; returns the stored city. */
	public ObservedCity addCity(List<SeedCracker.Candidate> candidates, int x, int z, boolean auto, boolean exact) {
		ObservedCity result;
		synchronized (this) {
			ObservedCity existing = null;
			for (ObservedCity c : cities.values()) {
				for (int[] a : c.candidates) {
					for (SeedCracker.Candidate b : candidates) {
						if (a[0] == b.chunkX() && a[1] == b.chunkZ()) {
							existing = c;
						}
					}
				}
			}
			if (existing != null) {
				// Keep the better observation: exact beats approximate, manual beats automatic.
				boolean better = (exact && !existing.exact) || (!auto && existing.auto && exact == existing.exact);
				if (!better) {
					return existing;
				}
				result = existing;
			} else {
				result = new ObservedCity();
				result.id = nextId++;
				cities.put(result.id, result);
			}
			result.x = x;
			result.z = z;
			result.auto = auto;
			result.exact = exact;
			result.candidates = new ArrayList<>();
			for (SeedCracker.Candidate c : candidates) {
				result.candidates.add(new int[] {c.chunkX(), c.chunkZ(), c.rotation()});
			}
			dirty = true;
		}
		requestSolve();
		return result;
	}

	public boolean removeCity(int id) {
		boolean removed;
		synchronized (this) {
			removed = cities.remove(id) != null;
			if (removed) {
				candidates = null;
				dirty = true;
			}
		}
		if (removed) {
			requestSolve();
		}
		return removed;
	}

	public void clearAll() {
		synchronized (this) {
			Arrays.fill(spikes, -1);
			cities.clear();
			emptyChunks.clear();
			samples.clear();
			visited.clear();
			manualSeed = null;
			target = null;
			candidates = null;
			nextId = 1;
			dirty = true;
		}
		requestSolve();
	}

	public synchronized void addEmptyChunk(int chunkX, int chunkZ) {
		if (emptyChunks.add(Placement.pack(chunkX, chunkZ))) {
			dirty = true;
		}
	}

	public synchronized int emptyChunkCount() {
		return emptyChunks.size();
	}

	public synchronized void addSample(int x, int z, int surfaceY) {
		if (samples.size() >= MAX_SAMPLES) {
			return;
		}
		for (int[] s : samples) {
			if (s[0] == x && s[1] == z) {
				return;
			}
		}
		samples.add(new int[] {x, z, surfaceY});
		dirty = true;
	}

	public synchronized int sampleCount() {
		return samples.size();
	}

	public void setManualSeed(Long seed) {
		synchronized (this) {
			manualSeed = seed;
			dirty = true;
		}
		requestSolve();
	}

	public synchronized boolean isVisited(EndCity city) {
		return visited.contains(city.key());
	}

	public synchronized void setVisited(EndCity city, boolean value) {
		if (value ? visited.add(city.key()) : visited.remove(city.key())) {
			dirty = true;
		}
	}

	public synchronized void setTarget(EndCity city) {
		target = city == null ? null : city.key();
		dirty = true;
	}

	// ---------------------------------------------------------------- solver

	public Phase phase() {
		return phase;
	}

	public String detail() {
		return detail;
	}

	public double progress() {
		SeedCracker cracker = activeCracker;
		return cracker == null ? -1 : cracker.progress();
	}

	public List<EndWorld> worlds() {
		return worlds;
	}

	public synchronized int candidateCount() {
		return candidates == null ? 0 : candidates.size();
	}

	public void requestSolve() {
		if (solveQueued.compareAndSet(false, true)) {
			try {
				worker.submit(() -> {
					solveQueued.set(false);
					try {
						solve();
					} catch (Throwable t) {
						EndCityFinderClient.LOG.error("Solver failed", t);
						phase = Phase.CONTRADICTION;
						detail = "ошибка солвера: " + t;
					}
				});
			} catch (java.util.concurrent.RejectedExecutionException ignored) {
				solveQueued.set(false);
			}
		}
	}

	private void solve() {
		int[] spikeSnapshot;
		List<ObservedCity> citySnapshot;
		List<Long> empty;
		List<CandidateFilter.TerrainSample> sampleSnapshot;
		Long seed;
		List<Long> previous;
		int[] previousKeys;
		Set<Integer> previousCities;
		synchronized (this) {
			spikeSnapshot = spikes.clone();
			citySnapshot = new ArrayList<>(cities.values());
			empty = new ArrayList<>(emptyChunks);
			sampleSnapshot = new ArrayList<>();
			for (int[] s : samples) {
				sampleSnapshot.add(new CandidateFilter.TerrainSample(s[0], s[1], s[2]));
			}
			seed = manualSeed;
			previous = candidates;
			previousKeys = candidateKeys;
			previousCities = candidateCities;
		}
		if (seed != null) {
			publish(List.of(seed & LegacyRandom.MASK), null, null);
			return;
		}
		List<SeedCracker.City> constraints = new ArrayList<>();
		Set<Integer> cityIds = new HashSet<>();
		for (ObservedCity c : citySnapshot) {
			// Automatic detections that did not match the base floor exactly may be player builds.
			if (c.auto && !c.exact) {
				continue;
			}
			constraints.add(new SeedCracker.City(c.toCandidates()));
			cityIds.add(c.id);
		}
		boolean anySpike = Arrays.stream(spikeSnapshot).anyMatch(s -> s >= 0);
		if (!anySpike) {
			setStatus(Phase.NEED_PILLARS, "долети до центрального острова, пилоны считаются сами (или /ecf pillar)");
			clearWorlds();
			return;
		}
		int[] keys = EndSpikes.matchingKeys(spikeSnapshot);
		if (keys.length == 0) {
			setStatus(Phase.CONTRADICTION, "высоты пилонов не совпадают ни с одним вариантом, проверь /ecf pillars");
			clearWorlds();
			return;
		}
		if (constraints.isEmpty()) {
			setStatus(Phase.NEED_CITIES, "нужен хотя бы один найденный город");
			clearWorlds();
			return;
		}
		CandidateFilter filter = new CandidateFilter(Placement.END_CITIES, constraints, empty, sampleSnapshot);
		if (previous != null && Arrays.equals(previousKeys, keys) && cityIds.containsAll(previousCities)) {
			setStatus(Phase.FILTERING, "проверяю " + previous.size() + " кандидатов");
			List<Long> filtered = filter.filter(previous);
			if (!filtered.isEmpty()) {
				publish(filtered, keys, cityIds);
				return;
			}
		}
		SeedCracker cracker = new SeedCracker(Placement.END_CITIES, keys, constraints, MAX_RAW_SEEDS);
		double expected = cracker.expectedSeeds();
		if (expected > MAX_EXPECTED_SEEDS) {
			int more = (int) Math.ceil(Math.log(expected / MAX_EXPECTED_SEEDS) / Math.log(2) / 5.8);
			setStatus(Phase.NEED_MORE_CITIES, "мало данных, найди ещё примерно " + Math.max(1, more) + " город(а)");
			clearWorlds();
			return;
		}
		setStatus(Phase.SEARCHING, "перебор " + keys.length + " × 2^32 сидов");
		activeCracker = cracker;
		SeedCracker.Result result;
		try {
			result = cracker.run(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
		} finally {
			activeCracker = null;
		}
		if (result.cancelled()) {
			return;
		}
		if (result.overflow()) {
			setStatus(Phase.NEED_MORE_CITIES, "слишком много вариантов, найди ещё город");
			clearWorlds();
			return;
		}
		setStatus(Phase.FILTERING, "проверяю " + result.seeds().size() + " кандидатов");
		publish(filter.filter(result.seeds()), keys, cityIds);
	}

	private void setStatus(Phase phase, String detail) {
		this.phase = phase;
		this.detail = detail;
	}

	private void clearWorlds() {
		worlds = List.of();
		predicted = List.of();
		predictedFor = List.of();
	}

	private void publish(List<Long> seeds, int[] keys, Set<Integer> cityIds) {
		synchronized (this) {
			candidates = keys == null ? null : new ArrayList<>(seeds);
			candidateKeys = keys;
			candidateCities = cityIds;
			dirty = true;
		}
		if (seeds.isEmpty()) {
			setStatus(Phase.CONTRADICTION, "ни один сид не подходит, скорее всего один из городов указан неверно (/ecf cities)");
			clearWorlds();
			return;
		}
		List<EndWorld> list = new ArrayList<>();
		for (int i = 0; i < Math.min(MAX_WORLDS, seeds.size()); i++) {
			list.add(new EndWorld(seeds.get(i)));
		}
		worlds = List.copyOf(list);
		if (seeds.size() == 1) {
			setStatus(Phase.SOLVED, "сид энда " + seeds.get(0));
		} else {
			setStatus(Phase.AMBIGUOUS, seeds.size() + " кандидатов, показываю общие города; найди ещё город или полетай");
		}
		predictedAtX = Double.NaN;
	}

	// ---------------------------------------------------------------- predictions

	public List<EndCity> predicted() {
		return predicted;
	}

	/** Whether {@link #predicted()} belongs to the current solver result. */
	public boolean predictionsCurrent() {
		return !worlds.isEmpty() && predictedFor == worlds;
	}

	/** Recomputes predictions in the background when the player moved far enough from the last center. */
	public void updatePrediction(double x, double z, Settings settings) {
		if (worlds.isEmpty()) {
			return;
		}
		double moved = Double.isNaN(predictedAtX) ? Double.MAX_VALUE : Math.hypot(x - predictedAtX, z - predictedAtZ);
		if (moved < 320 || !predictQueued.compareAndSet(false, true)) {
			return;
		}
		predictedAtX = x;
		predictedAtZ = z;
		List<EndWorld> ws = worlds;
		int radius = settings.radius;
		try {
			predictor.submit(() -> {
				predictQueued.set(false);
				try {
					predict(ws, (int) x, (int) z, radius);
				} catch (Throwable t) {
					EndCityFinderClient.LOG.error("Prediction failed", t);
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
			predictQueued.set(false);
		}
	}

	private void predict(List<EndWorld> ws, int x, int z, int radius) {
		List<EndCity> list;
		if (ws.size() == 1) {
			list = ws.get(0).citiesNear(x, z, radius);
		} else if (ws.size() <= 8) {
			// A few candidate seeds: keep only the cities all of them agree on.
			int r = Math.min(radius, 3000);
			Map<Long, EndCity> common = new HashMap<>();
			for (EndCity c : ws.get(0).citiesNear(x, z, r)) {
				common.put(c.key(), c);
			}
			for (int i = 1; i < ws.size() && !common.isEmpty(); i++) {
				Set<Long> keys = new HashSet<>();
				for (EndCity c : ws.get(i).citiesNear(x, z, r)) {
					keys.add(c.key());
				}
				common.keySet().retainAll(keys);
			}
			list = new ArrayList<>(common.values());
		} else {
			list = new ArrayList<>();
		}
		if (ws != worlds) {
			return;
		}
		list.sort(Comparator.comparingDouble(c -> Math.hypot(c.x() - x, c.z() - z)));
		predicted = List.copyOf(list);
		predictedFor = ws;
	}

	/** The city the HUD and arrow point to: the chosen target, else the nearest unvisited one. */
	public EndCity target(double x, double z, Settings settings) {
		Long chosen;
		synchronized (this) {
			chosen = target;
		}
		EndCity best = null;
		double bestDist = Double.MAX_VALUE;
		for (EndCity c : predicted) {
			if (chosen != null && c.key() == chosen) {
				return c;
			}
			if (isVisited(c) || (settings.shipsOnly && !c.ship())) {
				continue;
			}
			double d = Math.hypot(c.x() - x, c.z() - z);
			if (d < bestDist) {
				bestDist = d;
				best = c;
			}
		}
		return best;
	}
}
