package io.github.aabsolute22.seedcracker.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.aabsolute22.seedcracker.core.Branching;
import io.github.aabsolute22.seedcracker.core.Observation;
import io.github.aabsolute22.seedcracker.core.SeedHash;
import io.github.aabsolute22.seedcracker.core.StructureSeedCracker;
import io.github.aabsolute22.seedcracker.world.BiomeCheck;
import io.github.aabsolute22.seedcracker.world.SeedWorld;
import io.github.aabsolute22.seedcracker.world.Worldgen;

/**
 * Runs the cracking pipeline in the background: structure seed from observations, then the top 16 bits from the
 * hashed seed (or from biomes), re-run whenever new evidence comes in.
 */
public final class Solver {
	/** Below this many expected wrong structure seeds the search is worth running. */
	private static final double MAX_FALSE_SEEDS = 3000;
	private static final int MAX_SEEDS = 4096;

	private final Session session;
	private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "seedcracker-solver");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private final AtomicBoolean pending = new AtomicBoolean();
	private volatile StructureSeedCracker cracker;
	private volatile BiomeCheck biomeCheck;
	private volatile boolean allowSlow;
	private volatile boolean closed;
	/** Full seeds still possible when the hash was missing and biomes could not decide yet. */
	private volatile List<Long> fullCandidates = List.of();

	public Solver(Session session) {
		this.session = session;
	}

	public void request() {
		if (!closed && pending.compareAndSet(false, true)) {
			exec.submit(this::run);
		}
	}

	/** Starts the brute force even if it is expected to be slow. */
	public void allowSlow() {
		allowSlow = true;
		request();
	}

	public void close() {
		closed = true;
		cancelRunning();
		exec.shutdownNow();
	}

	public void cancelRunning() {
		StructureSeedCracker c = cracker;
		if (c != null) {
			c.cancel();
		}
		BiomeCheck b = biomeCheck;
		if (b != null) {
			b.cancel();
		}
	}

	public List<Long> fullCandidates() {
		return fullCandidates;
	}

	private void run() {
		pending.set(false);
		try {
			solve();
		} catch (Throwable t) {
			SeedCrackerClient.LOG.error("Solver failed", t);
			session.setStatus(Session.Phase.CONTRADICTION, "ошибка: " + t);
		} finally {
			session.setProgress(() -> -1, -1);
		}
	}

	private void solve() {
		Settings settings = SeedCrackerClient.settings();
		if (session.seed() != null) {
			session.setStatus(Session.Phase.SOLVED, "сид " + session.seed());
			return;
		}
		session.setStatus(Session.Phase.LOADING, "загружаю данные генерации");
		Worldgen worldgen = Worldgen.get(SeedCrackerClient.tempDir()).join();
		List<Observation> obs = session.coreObservations();
		double bits = 0;
		for (Observation o : obs) {
			bits += o.bits();
		}
		if (obs.isEmpty()) {
			session.setStatus(Session.Phase.COLLECTING, "ищу структуры");
			return;
		}
		List<Long> seeds = session.structureSeeds();
		boolean reuse = !seeds.isEmpty() && !session.structureSeedsOverflow();
		StructureSeedCracker filter = new StructureSeedCracker(obs, MAX_SEEDS);
		if (reuse) {
			List<Long> kept = new ArrayList<>();
			for (long s : seeds) {
				if (filter.accepts(s)) {
					kept.add(s);
				}
			}
			if (kept.isEmpty()) {
				reuse = false;
			} else {
				seeds = kept;
				if (kept.size() != session.structureSeeds().size()) {
					session.setStructureSeeds(kept, false);
				}
			}
		}
		if (!reuse) {
			if (filter.expectedFalseSeeds() > MAX_FALSE_SEEDS) {
				session.setStatus(Session.Phase.COLLECTING, String.format("мало данных: %.0f бит из ~48, ищу ещё структуры", bits));
				return;
			}
			// Structures added by hand may name a few possible chunks; trying each one apart is often much faster.
			List<StructureSeedCracker> branches = new ArrayList<>();
			for (List<Observation> b : Branching.plan(obs, MAX_SEEDS)) {
				branches.add(new StructureSeedCracker(b, MAX_SEEDS));
			}
			double nanos = 0;
			for (StructureSeedCracker c : branches) {
				nanos += c.estimatedNanos();
			}
			int threads = settings.threadCount();
			double seconds = nanos / 1e9 / threads;
			if (seconds > settings.maxCrackMinutes * 60.0 && !allowSlow) {
				session.setStatus(Session.Phase.SLOW, String.format("перебор займёт ~%s, /sc crack чтобы начать (храм, корабль или клад ускорят)",
						duration((long) (seconds * 1000))));
				return;
			}
			long start = System.currentTimeMillis();
			int[] done = {0};
			session.setProgress(() -> {
				StructureSeedCracker c = cracker;
				return (done[0] + (c == null ? 0 : c.progress())) / branches.size();
			}, (long) (seconds * 1000));
			session.setStatus(Session.Phase.CRACKING, String.format("перебор сида структур (%.0f бит%s)", bits,
					branches.size() > 1 ? ", " + branches.size() + " вариантов ручных находок" : ""));
			Set<Long> union = new java.util.TreeSet<>();
			boolean overflow = false;
			for (StructureSeedCracker c : branches) {
				cracker = c;
				StructureSeedCracker.Result result = c.run(threads);
				cracker = null;
				if (result.cancelled()) {
					return;
				}
				done[0]++;
				for (long seed : result.seeds()) {
					// A branch only fixed one candidate; the full set of observations has the last word.
					if (filter.accepts(seed)) {
						union.add(seed);
					}
				}
				overflow |= result.overflow() || union.size() > MAX_SEEDS;
				if (overflow) {
					break;
				}
			}
			SeedCrackerClient.LOG.info("Structure seed search: {} seeds in {} ms over {} branches (overflow {})", union.size(),
					System.currentTimeMillis() - start, branches.size(), overflow);
			seeds = new ArrayList<>(union);
			session.setStructureSeeds(seeds, overflow);
			if (overflow) {
				session.setStatus(Session.Phase.COLLECTING, "слишком много вариантов, нужна ещё структура");
				return;
			}
			if (seeds.isEmpty()) {
				if (dropSuspect(obs, settings)) {
					request();
					return;
				}
				if (session.phase() != Session.Phase.CONTRADICTION) {
					session.setStatus(Session.Phase.CONTRADICTION, "ни один сид не подходит, проверь /sc structures");
				}
				return;
			}
		}
		upperBits(worldgen, seeds, settings);
	}

	/**
	 * One wrong detection makes every seed fail. If leaving out exactly one automatic observation brings seeds back
	 * (and those searches are quick), that observation is removed.
	 */
	private boolean dropSuspect(List<Observation> obs, Settings settings) {
		List<Session.Obs> all = session.observations();
		if (all.size() < 3 || all.size() != obs.size()) {
			return false;
		}
		session.setStatus(Session.Phase.CRACKING, "противоречие, ищу ошибочную находку");
		int threads = settings.threadCount();
		long deadline = System.currentTimeMillis() + 120_000;
		List<Integer> suspects = new ArrayList<>();
		for (int i = 0; i < obs.size(); i++) {
			if (System.currentTimeMillis() > deadline) {
				continue;
			}
			List<Observation> rest = new ArrayList<>(obs);
			rest.remove(i);
			StructureSeedCracker c = new StructureSeedCracker(rest, MAX_SEEDS);
			if (c.expectedFalseSeeds() > MAX_FALSE_SEEDS || c.estimatedNanos() / 1e9 / threads > 20) {
				continue;
			}
			cracker = c;
			StructureSeedCracker.Result r = c.run(threads);
			cracker = null;
			if (r.cancelled()) {
				return false;
			}
			if (!r.seeds().isEmpty() && !r.overflow()) {
				suspects.add(i);
			}
		}
		if (suspects.size() != 1) {
			return false;
		}
		Session.Obs bad = all.get(suspects.get(0));
		if (!bad.auto) {
			// Never drop what the player typed in; say which one does not fit.
			session.setStatus(Session.Phase.CONTRADICTION, "не сходится ручная находка #" + bad.id + " (" + bad.describe() + "), проверь её или /sc remove " + bad.id);
			net.minecraft.client.Minecraft.getInstance().execute(() -> SeedCrackerClient.message(net.minecraft.network.chat.Component
					.literal("[SC] Похоже, ручная находка #" + bad.id + " ошибочная: " + bad.describe() + ". Удалить: /sc remove " + bad.id)
					.withStyle(net.minecraft.ChatFormatting.YELLOW)));
			return false;
		}
		session.removeObservation(bad.id);
		SeedCrackerClient.LOG.info("Dropped inconsistent observation #{} {}", bad.id, bad.describe());
		net.minecraft.client.Minecraft.getInstance().execute(() -> SeedCrackerClient.message(net.minecraft.network.chat.Component
				.literal("[SC] Убрал ошибочную находку #" + bad.id + ": " + bad.describe()).withStyle(net.minecraft.ChatFormatting.YELLOW)));
		return true;
	}

	private void upperBits(Worldgen worldgen, List<Long> structureSeeds, Settings settings) {
		List<BiomeCheck.Sample> over = session.biomeSamples("overworld");
		List<BiomeCheck.Sample> nether = session.biomeSamples("nether");
		BiomeCheck overCheck = new BiomeCheck(worldgen, SeedWorld.Dim.OVERWORLD);
		BiomeCheck netherCheck = new BiomeCheck(worldgen, SeedWorld.Dim.NETHER);
		Set<Long> full = new LinkedHashSet<>();
		Long hashed = session.hashedSeed();
		List<Long> byHash = new ArrayList<>();
		if (hashed != null) {
			session.setStatus(Session.Phase.UPPER_BITS, "сид структур найден, достаю остальные 16 бит по хэшу");
			for (long ss : structureSeeds) {
				for (long seed : SeedHash.fullSeeds(ss, hashed)) {
					byHash.add(seed);
					if (biomesAgree(overCheck, netherCheck, seed, over, nether)) {
						full.add(seed);
					}
				}
			}
		}
		if (full.isEmpty() && byHash.size() == 1) {
			// 64 hash bits cannot match by chance; biomes only disagree on superflat, amplified or datapack worlds.
			long seed = byHash.get(0);
			fullCandidates = List.of();
			session.setSeed(seed, "cracked");
			session.setStatus(Session.Phase.SOLVED, "сид " + seed + " (по хэшу, биомы другие: плоский мир или датапак)");
			SeedCrackerClient.onSolved(seed);
			return;
		}
		if (full.isEmpty()) {
			List<Long> previous = fullCandidates;
			if (!previous.isEmpty()) {
				for (long seed : previous) {
					if (structureSeeds.contains(seed & ((1L << 48) - 1)) && biomesAgree(overCheck, netherCheck, seed, over, nether)) {
						full.add(seed);
					}
				}
			}
		}
		if (full.isEmpty()) {
			if (over.size() + nether.size() < 8) {
				session.setStatus(Session.Phase.UPPER_BITS, "сид структур найден, собираю биомы для остальных 16 бит");
				return;
			}
			if (structureSeeds.size() > 8) {
				session.setStatus(Session.Phase.COLLECTING, structureSeeds.size() + " вариантов сида структур, нужна ещё структура");
				return;
			}
			boolean useOver = over.size() >= nether.size();
			BiomeCheck check = useOver ? overCheck : netherCheck;
			List<BiomeCheck.Sample> samples = useOver ? over : nether;
			biomeCheck = check;
			for (long ss : structureSeeds) {
				session.setProgress(check::progress, -1);
				session.setStatus(Session.Phase.UPPER_BITS, "хэш сида не пришёл, подбираю 16 бит по биомам");
				for (long seed : check.fullSeeds(ss, samples, settings.threadCount())) {
					if (biomesAgree(overCheck, netherCheck, seed, over, nether)) {
						full.add(seed);
					}
				}
			}
			biomeCheck = null;
		}
		if (full.size() == 1) {
			long seed = full.iterator().next();
			fullCandidates = List.of();
			session.setSeed(seed, "cracked");
			session.setStatus(Session.Phase.SOLVED, "сид " + seed);
			SeedCrackerClient.onSolved(seed);
		} else if (full.isEmpty()) {
			fullCandidates = List.of();
			session.setStatus(Session.Phase.CONTRADICTION, "биомы не сходятся ни с одним сидом (датапак или другой генератор?)");
		} else {
			fullCandidates = new ArrayList<>(full);
			session.setStatus(Session.Phase.AMBIGUOUS, full.size() + " вариантов сида, полетай ещё чтобы собрать биомы");
		}
	}

	private static boolean biomesAgree(BiomeCheck overCheck, BiomeCheck netherCheck, long seed, List<BiomeCheck.Sample> over, List<BiomeCheck.Sample> nether) {
		return (over.isEmpty() || overCheck.matches(seed, over)) && (nether.isEmpty() || netherCheck.matches(seed, nether));
	}

	public static String duration(long millis) {
		long s = Math.max(0, millis / 1000);
		if (s < 60) {
			return s + " с";
		}
		if (s < 3600) {
			return (s / 60) + " мин";
		}
		return String.format("%d ч %02d мин", s / 3600, s / 60 % 60);
	}
}
