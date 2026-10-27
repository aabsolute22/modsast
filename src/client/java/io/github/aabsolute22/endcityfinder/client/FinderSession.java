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
import io.github.aabsolute22.endcityfinder.core.SaltSolver;
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
		Integer candidateSalt;
		Long target;
		Integer salt;
		Long saltSeed;
		int[] saltCandidates;
		List<String> party = new ArrayList<>();
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
	private Integer candidateSalt;
	/** The server's End city salt when it is not vanilla: set by hand, received from a friend or cracked. */
	private Integer salt;
	/** While the salt is not pinned down yet: the seed and the salts that still fit. */
	private Long saltSeed;
	private List<Integer> saltCandidates;
	/** Players to share the seed and looted cities with; see {@link Party}. */
	private final List<String> party = new ArrayList<>();

	private final ExecutorService worker = daemonExecutor("endcityfinder-solver");
	/** Separate from the solver so a long prediction never delays solving, and vice versa. */
	private final ExecutorService predictor = daemonExecutor("endcityfinder-predictor");
	private final AtomicBoolean solveQueued = new AtomicBoolean();
	private final AtomicBoolean predictQueued = new AtomicBoolean();
	private volatile java.util.function.DoubleSupplier activeProgress;
	private volatile Runnable activeCancel;
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
			salt = data.salt;
			saltSeed = data.saltSeed;
			if (data.saltCandidates != null) {
				saltCandidates = new ArrayList<>();
				for (int c : data.saltCandidates) {
					saltCandidates.add(c);
				}
			}
			if (data.party != null) {
				party.addAll(data.party);
			}
			candidateSalt = data.candidateSalt;
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
		data.salt = salt;
		data.saltSeed = saltSeed;
		data.saltCandidates = saltCandidates == null ? null : saltCandidates.stream().mapToInt(Integer::intValue).toArray();
		data.party = new ArrayList<>(party);
		data.candidateSalt = candidateSalt;
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
		Runnable cancel = activeCancel;
		if (cancel != null) {
			cancel.run();
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
			salt = null;
			saltSeed = null;
			saltCandidates = null;
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

	/** Returns whether the state changed. */
	public synchronized boolean setVisited(EndCity city, boolean value) {
		boolean changed = value ? visited.add(city.key()) : visited.remove(city.key());
		dirty |= changed;
		return changed;
	}

	/** Marks cities (by start chunk) looted, e.g. as reported by a friend; returns how many were new. */
	public synchronized int markVisited(long[] cityKeys) {
		int added = 0;
		for (long key : cityKeys) {
			if (visited.add(key)) {
				added++;
			}
		}
		dirty |= added > 0;
		return added;
	}

	public synchronized long[] visitedCities() {
		return visited.toLongArray();
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
		java.util.function.DoubleSupplier p = activeProgress;
		return p == null ? -1 : p.getAsDouble();
	}

	public List<EndWorld> worlds() {
		return worlds;
	}

	public synchronized int candidateCount() {
		if (saltCandidates != null && salt == null) {
			return saltCandidates.size();
		}
		return candidates == null ? 0 : candidates.size();
	}

	/** The End city placement the solver assumes: vanilla unless the server's salt is known. */
	public synchronized Placement placement() {
		return placementFor(salt);
	}

	private static Placement placementFor(Integer salt) {
		Placement vanilla = Placement.END_CITIES;
		return salt == null ? vanilla : new Placement(vanilla.spacing(), vanilla.separation(), salt, vanilla.triangular());
	}

	public synchronized Integer salt() {
		return salt;
	}

	/** Sets the server's End city salt by hand; {@code null} goes back to vanilla. */
	public void setSalt(Integer value) {
		synchronized (this) {
			salt = value != null && value == Placement.END_CITIES.salt() ? null : value;
			saltSeed = null;
			saltCandidates = null;
			candidates = null;
			dirty = true;
		}
		requestSolve();
	}

	/** Seed and salt received from a friend: no search needed. */
	public void applyShared(long seed, int sharedSalt) {
		synchronized (this) {
			manualSeed = seed & LegacyRandom.MASK;
			salt = sharedSalt == Placement.END_CITIES.salt() ? null : sharedSalt;
			saltSeed = null;
			saltCandidates = null;
			dirty = true;
		}
		requestSolve();
	}

	/** The solved seed, or null while it is not unique. */
	public Long solvedSeed() {
		List<EndWorld> ws = worlds;
		return phase == Phase.SOLVED && ws.size() == 1 ? ws.get(0).seed() : null;
	}

	/** The salt of the solved world (vanilla salt when unchanged). */
	public int solvedSalt() {
		List<EndWorld> ws = worlds;
		return ws.isEmpty() ? Placement.END_CITIES.salt() : ws.get(0).placement().salt();
	}

	public synchronized List<String> party() {
		return new ArrayList<>(party);
	}

	public synchronized boolean addPartyMember(String name) {
		for (String p : party) {
			if (p.equalsIgnoreCase(name)) {
				return false;
			}
		}
		party.add(name);
		dirty = true;
		return true;
	}

	public synchronized boolean removePartyMember(String name) {
		boolean removed = party.removeIf(p -> p.equalsIgnoreCase(name));
		dirty |= removed;
		return removed;
	}

	public synchronized boolean isPartyMember(String name) {
		for (String p : party) {
			if (p.equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
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

	private static int threads() {
		return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
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
		Integer previousSalt;
		Integer knownSalt;
		Long pendingSaltSeed;
		List<Integer> pendingSalts;
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
			previousSalt = candidateSalt;
			knownSalt = salt;
			pendingSaltSeed = saltSeed;
			pendingSalts = saltCandidates == null ? null : new ArrayList<>(saltCandidates);
		}
		Placement placement = placementFor(knownSalt);
		if (seed != null) {
			publish(List.of(seed & LegacyRandom.MASK), placement, null, null);
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
		// The salt search already found the seed: new cities and explored chunks only narrow the salts down.
		if (knownSalt == null && pendingSaltSeed != null && pendingSalts != null) {
			setStatus(Phase.FILTERING, "проверяю " + pendingSalts.size() + " вариантов соли");
			List<Integer> remaining = new ArrayList<>();
			for (int candidate : pendingSalts) {
				if (new CandidateFilter(placementFor(candidate), constraints, empty, sampleSnapshot).check(pendingSaltSeed).accepted()) {
					remaining.add(candidate);
				}
			}
			if (!remaining.isEmpty()) {
				publishSalted(pendingSaltSeed, remaining);
				return;
			}
		}
		CandidateFilter filter = new CandidateFilter(placement, constraints, empty, sampleSnapshot);
		if (previous != null && Arrays.equals(previousKeys, keys) && cityIds.containsAll(previousCities)
				&& java.util.Objects.equals(previousSalt, knownSalt)) {
			setStatus(Phase.FILTERING, "проверяю " + previous.size() + " кандидатов");
			List<Long> filtered = filter.filter(previous);
			if (!filtered.isEmpty()) {
				publish(filtered, placement, keys, cityIds);
				return;
			}
		}
		SeedCracker cracker = new SeedCracker(placement, keys, constraints, MAX_RAW_SEEDS);
		double expected = cracker.expectedSeeds();
		if (expected > MAX_EXPECTED_SEEDS) {
			int more = (int) Math.ceil(Math.log(expected / MAX_EXPECTED_SEEDS) / Math.log(2) / 5.8);
			setStatus(Phase.NEED_MORE_CITIES, "мало данных, найди ещё примерно " + Math.max(1, more) + " город(а)");
			clearWorlds();
			return;
		}
		setStatus(Phase.SEARCHING, "перебор " + keys.length + " × 2^32 сидов");
		activeProgress = cracker::progress;
		activeCancel = cracker::cancel;
		SeedCracker.Result result;
		try {
			result = cracker.run(threads());
		} finally {
			activeProgress = null;
			activeCancel = null;
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
		List<Long> filtered = filter.filter(result.seeds());
		if (!filtered.isEmpty()) {
			publish(filtered, placement, keys, cityIds);
			return;
		}
		if (knownSalt != null) {
			publish(List.of(), placement, keys, cityIds);
			setStatus(Phase.CONTRADICTION, "с солью " + knownSalt + " ничего не сходится: проверь соль (/ecf salt reset) и города (/ecf cities)");
			return;
		}
		solveUnknownSalt(keys, constraints, empty, sampleSnapshot);
	}

	/** Nothing fits the vanilla salt: assume the server changed it and crack seed and salt separately. */
	private void solveUnknownSalt(int[] keys, List<SeedCracker.City> constraints, List<Long> empty, List<CandidateFilter.TerrainSample> sampleSnapshot) {
		SaltSolver solver = new SaltSolver(Placement.END_CITIES, keys, constraints, empty, sampleSnapshot);
		if (solver.rotatedCities() < 3) {
			setStatus(Phase.CONTRADICTION, "с ванильной солью не сходится. либо город указан неверно (/ecf cities), либо сервер поменял соль: "
					+ "тогда найди пролётом ещё города, нужно хотя бы 3 с известным поворотом (сейчас " + solver.rotatedCities() + ")");
			clearWorlds();
			return;
		}
		setStatus(Phase.SEARCHING, "с ванильной солью не сходится, вычисляю соль сервера (до пары минут)");
		activeProgress = solver::progress;
		activeCancel = solver::cancel;
		SaltSolver.Result result;
		try {
			result = solver.run(threads());
		} finally {
			activeProgress = null;
			activeCancel = null;
		}
		if (result.cancelled()) {
			return;
		}
		if (result.problem() != null) {
			setStatus(result.problem().startsWith("слишком") ? Phase.NEED_MORE_CITIES : Phase.CONTRADICTION, result.problem());
			clearWorlds();
			return;
		}
		long first = result.solutions().get(0).seed();
		boolean oneSeed = result.solutions().stream().allMatch(sol -> sol.seed() == first);
		if (oneSeed) {
			publishSalted(first, result.solutions().stream().map(SaltSolver.Solution::salt).toList());
			return;
		}
		List<EndWorld> list = new ArrayList<>();
		for (SaltSolver.Solution sol : result.solutions()) {
			if (list.size() < MAX_WORLDS) {
				list.add(new EndWorld(sol.seed(), placementFor(sol.salt())));
			}
		}
		worlds = List.copyOf(list);
		setStatus(Phase.AMBIGUOUS, result.solutions().size() + " вариантов сида и соли, найди ещё города или полетай");
		predictedAtX = Double.NaN;
	}

	private void publishSalted(long seed, List<Integer> salts) {
		synchronized (this) {
			saltSeed = seed;
			if (salts.size() == 1) {
				salt = salts.get(0) == Placement.END_CITIES.salt() ? null : salts.get(0);
				saltCandidates = null;
				candidates = null;
				manualSeed = null;
			} else {
				saltCandidates = new ArrayList<>(salts);
			}
			dirty = true;
		}
		List<EndWorld> list = new ArrayList<>();
		for (int i = 0; i < Math.min(MAX_WORLDS, salts.size()); i++) {
			list.add(new EndWorld(seed, placementFor(salts.get(i))));
		}
		worlds = List.copyOf(list);
		if (salts.size() == 1) {
			setStatus(Phase.SOLVED, "сид энда " + seed + ", соль городов на сервере " + salts.get(0));
		} else {
			setStatus(Phase.AMBIGUOUS, "сид найден, соль городов: " + salts.size() + " варианта, найди ещё город или полетай");
		}
		predictedAtX = Double.NaN;
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

	private void publish(List<Long> seeds, Placement placement, int[] keys, Set<Integer> cityIds) {
		synchronized (this) {
			candidates = keys == null ? null : new ArrayList<>(seeds);
			candidateKeys = keys;
			candidateCities = cityIds;
			candidateSalt = placement.salt() == Placement.END_CITIES.salt() ? null : placement.salt();
			dirty = true;
		}
		if (seeds.isEmpty()) {
			setStatus(Phase.CONTRADICTION, "ни один сид не подходит, скорее всего один из городов указан неверно (/ecf cities)");
			clearWorlds();
			return;
		}
		List<EndWorld> list = new ArrayList<>();
		for (int i = 0; i < Math.min(MAX_WORLDS, seeds.size()); i++) {
			list.add(new EndWorld(seeds.get(i), placement));
		}
		worlds = List.copyOf(list);
		String saltNote = placement.salt() == Placement.END_CITIES.salt() ? "" : ", соль " + placement.salt();
		if (seeds.size() == 1) {
			setStatus(Phase.SOLVED, "сид энда " + seeds.get(0) + saltNote);
		} else {
			setStatus(Phase.AMBIGUOUS, seeds.size() + " кандидатов, показываю общие города; найди ещё город или полетай");
		}
		predictedAtX = Double.NaN;
	}

	// ---------------------------------------------------------------- predictions

	public List<EndCity> predicted() {
		return predicted;
	}

	/** The predicted cities the player wants to see: without looted ones and, with {@code shipsOnly}, only those with a ship. */
	public List<EndCity> shown(Settings settings) {
		List<EndCity> all = predicted;
		if (!settings.shipsOnly && !settings.hideVisited) {
			return all;
		}
		return all.stream().filter(c -> (!settings.shipsOnly || c.ship()) && (!settings.hideVisited || !isVisited(c))).toList();
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
