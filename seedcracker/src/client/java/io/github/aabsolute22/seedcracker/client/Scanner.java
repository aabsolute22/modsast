package io.github.aabsolute22.seedcracker.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import io.github.aabsolute22.seedcracker.core.Observation;
import io.github.aabsolute22.seedcracker.core.Packed;
import io.github.aabsolute22.seedcracker.core.StructureKind;
import io.github.aabsolute22.seedcracker.world.Canon;
import io.github.aabsolute22.seedcracker.world.ManualPlacement;
import io.github.aabsolute22.seedcracker.world.SeedWorld;
import io.github.aabsolute22.seedcracker.world.StartTemplates;
import io.github.aabsolute22.seedcracker.world.TemplateMatcher;
import io.github.aabsolute22.seedcracker.world.Worldgen;

/**
 * Reads the chunks the client already has: structure starts (by template matching and a few hand-made
 * signatures), biome cells for the seed check, and nothing else. Runs on the client thread with a small budget.
 */
public final class Scanner {
	private static final int CHUNKS_PER_TICK = 4;
	/** How many times (every 2 s) a start that almost fits is looked at again before giving up. */
	private static final int NEAR_MISS_RETRIES = 30;

	/** A rare block that may belong to a start piece; matched once the surrounding chunks are in. */
	private record Hit(int x, int y, int z, String canon) {
	}

	/** Prismarine extents of one chunk, for monuments. */
	private record Extent(int minX, int maxX, int minZ, int maxZ, int count) {
	}

	private final ArrayDeque<Long> queue = new ArrayDeque<>();
	private final LongOpenHashSet queued = new LongOpenHashSet();
	private final LongOpenHashSet scanned = new LongOpenHashSet();
	private final List<Hit> hits = new ArrayList<>();
	/** Hits that did not match while chunks around them were missing, by their chunk; retried when a neighbour arrives. */
	private final Long2ObjectOpenHashMap<List<Hit>> parked = new Long2ObjectOpenHashMap<>();
	/** Hits whose start almost fits although all chunks are there: chunk data can still be settling, so look again later. */
	private final List<Hit> later = new ArrayList<>();
	private final Map<Hit, Integer> nearMisses = new HashMap<>();
	private final Long2ObjectOpenHashMap<Extent> prismarine = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet fortressChecked = new LongOpenHashSet();
	/** Chunks full of prismarine whose monument could not be measured yet (neighbours missing). */
	private final LongOpenHashSet monumentPending = new LongOpenHashSet();
	private final Set<String> reported = new HashSet<>();
	private final Map<Block, String> canon = new IdentityHashMap<>();
	private Set<String> anchorCanons = Set.of();
	private ManualPlacement placement;
	private TemplateMatcher matcher;
	private SeedWorld.Dim dim;
	private int ticks;

	public void reset() {
		queue.clear();
		queued.clear();
		scanned.clear();
		hits.clear();
		parked.clear();
		later.clear();
		nearMisses.clear();
		prismarine.clear();
		fortressChecked.clear();
		monumentPending.clear();
		reported.clear();
		dim = null;
	}

	public int scannedChunks() {
		return scanned.size();
	}

	public TemplateMatcher matcher() {
		return matcher;
	}

	/** Start chunk geometry for structures added by hand; needs the templates, so null until they are loaded. */
	public ManualPlacement placement() {
		if (placement == null && matcher != null) {
			placement = new ManualPlacement(matcher.templates());
		}
		return placement;
	}

	public int pendingHits() {
		return hits.size();
	}

	public void enqueue(int chunkX, int chunkZ) {
		long key = Packed.pack(chunkX, chunkZ);
		if (queued.add(key)) {
			queue.add(key);
		}
	}

	public String canon(Block block) {
		String c = canon.get(block);
		if (c == null) {
			c = Canon.of(BuiltInRegistries.BLOCK.getKey(block).getPath());
			canon.put(block, c);
		}
		return c;
	}

	private boolean interesting(BlockState state) {
		String c = canon(state.getBlock());
		return anchorCanons.contains(c) || c.equals("blue_terracotta") || c.equals("chest") || c.equals("prismarine_bricks")
				|| c.equals("dark_prismarine") || c.equals("sea_lantern") || c.equals("prismarine") || c.equals("nether_bricks") || c.equals("sticky_piston")
				|| c.equals("dispenser") || c.equals("cauldron") || c.equals("crafting_table");
	}

	public void tick(ClientLevel level, Session session, Solver solver) {
		SeedWorld.Dim current = SeedWorld.Dim.of(level.dimension());
		if (current != dim) {
			reset();
			dim = current;
			for (LevelChunk c : loadedChunks(level)) {
				enqueue(c.getPos().x(), c.getPos().z());
			}
		}
		if (dim == null) {
			return;
		}
		if (matcher == null) {
			Worldgen wg = Worldgen.now();
			if (wg == null) {
				return;
			}
			StartTemplates templates = new StartTemplates(wg);
			matcher = new TemplateMatcher(templates);
			anchorCanons = new HashSet<>(templates.anchors());
		}
		for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) {
			long key = queue.poll();
			queued.remove(key);
			LevelChunk chunk = level.getChunkSource().getChunk(Packed.x(key), Packed.z(key), ChunkStatus.FULL, false);
			if (chunk != null) {
				scanChunk(level, chunk, session);
			}
		}
		if (++ticks % 10 == 0) {
			matchHits(level, session, solver);
		}
		if (ticks % 40 == 13 && !later.isEmpty()) {
			hits.addAll(later);
			later.clear();
		}
		if (ticks % 200 == 7 && !parked.isEmpty()) {
			for (long key : parked.keySet().toLongArray()) {
				if (!loaded(level, Packed.x(key), Packed.z(key))) {
					parked.remove(key);
				}
			}
		}
		if (ticks % 40 == 5 && !monumentPending.isEmpty()) {
			for (long key : monumentPending.toLongArray()) {
				if (checkMonument(session, Packed.x(key), Packed.z(key)) != 0) {
					monumentPending.remove(key);
				}
			}
		}
	}

	private static List<LevelChunk> loadedChunks(ClientLevel level) {
		List<LevelChunk> out = new ArrayList<>();
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.player == null) {
			return out;
		}
		int r = mc.options.getEffectiveRenderDistance() + 1;
		int pcx = mc.player.blockPosition().getX() >> 4;
		int pcz = mc.player.blockPosition().getZ() >> 4;
		for (int x = pcx - r; x <= pcx + r; x++) {
			for (int z = pcz - r; z <= pcz + r; z++) {
				LevelChunk c = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
				if (c != null) {
					out.add(c);
				}
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- per chunk

	private void scanChunk(ClientLevel level, LevelChunk chunk, Session session) {
		int cx = chunk.getPos().x();
		int cz = chunk.getPos().z();
		scanned.add(Packed.pack(cx, cz));
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				List<Hit> waiting = parked.remove(Packed.pack(cx + dx, cz + dz));
				if (waiting != null) {
					hits.addAll(waiting);
				}
			}
		}
		sampleBiome(chunk, session);
		LevelChunkSection[] sections = chunk.getSections();
		int minSection = chunk.getMinY() >> 4;
		Map<String, Integer> counts = new HashMap<>();
		Map<String, Integer> anchorsSeen = new HashMap<>();
		int pMinX = Integer.MAX_VALUE;
		int pMaxX = Integer.MIN_VALUE;
		int pMinZ = Integer.MAX_VALUE;
		int pMaxZ = Integer.MIN_VALUE;
		int pCount = 0;
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section == null || section.hasOnlyAir() || !section.maybeHas(this::interesting)) {
				continue;
			}
			int baseY = (minSection + i) * 16;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (state.isAir()) {
							continue;
						}
						String c = canon(state.getBlock());
						int wx = cx * 16 + x;
						int wy = baseY + y;
						int wz = cz * 16 + z;
						if (anchorCanons.contains(c)) {
							// Rare anchors are all looked at (a start may have just one among dozens of its neighbours'); common
							// building blocks are sampled, which keeps villages full of planks cheap.
							int seen = anchorsSeen.merge(c, 1, Integer::sum);
							boolean common = Canon.fallbackAnchor(c);
							if (common ? seen <= 4 || seen % 24 == 0 && seen <= 240 : seen <= 96) {
								hits.add(new Hit(wx, wy, wz, c));
							}
						}
						switch (c) {
							case "blue_terracotta" -> {
								if (x == 10 && z == 10) {
									checkDesertPyramid(level, session, wx, wy, wz);
								}
							}
							case "chest" -> {
								if (x == 9 && z == 9) {
									checkBuriedTreasure(level, session, wx, wy, wz);
								}
							}
							case "prismarine_bricks", "dark_prismarine", "sea_lantern", "prismarine" -> {
								pMinX = Math.min(pMinX, wx);
								pMaxX = Math.max(pMaxX, wx);
								pMinZ = Math.min(pMinZ, wz);
								pMaxZ = Math.max(pMaxZ, wz);
								pCount++;
							}
							default -> {
							}
						}
						counts.merge(c, 1, Integer::sum);
					}
				}
			}
		}
		if (pCount > 0) {
			prismarine.put(Packed.pack(cx, cz), new Extent(pMinX, pMaxX, pMinZ, pMaxZ, pCount));
		}
		if (dim == SeedWorld.Dim.OVERWORLD) {
			checkJungleTemple(level, session, chunk, counts);
			checkSwampHut(level, session, chunk, counts);
			if (pCount > 50) {
				monumentPending.add(Packed.pack(cx, cz));
			}
		} else if (counts.getOrDefault("nether_bricks", 0) > 30) {
			// The crossing reaches into the next chunks east and south; whichever of them arrives last completes it.
			checkFortress(level, session, cx, cz);
			checkFortress(level, session, cx - 1, cz);
			checkFortress(level, session, cx, cz - 1);
			checkFortress(level, session, cx - 1, cz - 1);
		}
	}

	private void sampleBiome(LevelChunk chunk, Session session) {
		int cx = chunk.getPos().x();
		int cz = chunk.getPos().z();
		if ((cx & 3) != 0 || (cz & 3) != 0) {
			return;
		}
		int y = dim == SeedWorld.Dim.OVERWORLD ? chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8) : 40 + Math.floorMod(cx * 7 + cz * 13, 50);
		int qx = (cx << 2) + 2;
		int qz = (cz << 2) + 2;
		int qy = Math.max(chunk.getMinY(), y) >> 2;
		Holder<Biome> biome = chunk.getNoiseBiome(qx, qy, qz);
		String id = SeedWorld.biomeId(biome);
		if (!id.equals("?")) {
			session.addBiomeSample(dim == SeedWorld.Dim.OVERWORLD ? "overworld" : "nether", qx, qy, qz, id);
		}
	}

	private String biomeAt(ClientLevel level, int x, int y, int z) {
		if (!loaded(level, x >> 4, z >> 4)) {
			return null;
		}
		return SeedWorld.biomeId(level.getNoiseBiome(x >> 2, y >> 2, z >> 2));
	}

	private String canonAt(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
		if (!loaded(level, x >> 4, z >> 4)) {
			return null;
		}
		return canon(level.getBlockState(pos.set(x, y, z)).getBlock());
	}

	// ---------------------------------------------------------------- template starts

	/** A start found around a block the player pointed at: exact chunk, rotation (or -1) and set pick (or -1). */
	public record Located(int chunkX, int chunkZ, int rotation, int pick, int x, int y, int z, String note) {
	}

	/**
	 * Looks for the start of one kind around (x, z) in the loaded chunks, checking every anchor block (no sampling,
	 * no biome filter): the player said what it is. Nearest first; empty when the blocks do not show it.
	 */
	public List<Located> findNear(ClientLevel level, StructureKind kind, int x, int z) {
		List<Located> out = new ArrayList<>();
		if (matcher == null) {
			return out;
		}
		int pcx = x >> 4;
		int pcz = z >> 4;
		if (kind == StructureKind.FORTRESS) {
			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
					int y = crossingDeck(level, pcx + dx, pcz + dz);
					if (y != Integer.MIN_VALUE) {
						int ox = (pcx + dx) * 16 + 2;
						int oz = (pcz + dz) * 16 + 2;
						out.add(new Located(pcx + dx, pcz + dz, -1, basaltNear(level, ox + 9, oz + 9) ? -1 : Observation.PICK_FORTRESS, ox + 9, y, oz + 9,
								"crossing"));
					}
				}
			}
		} else if (kind == StructureKind.OCEAN_MONUMENT) {
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					int[] o = monumentStart(pcx + dx, pcz + dz);
					if (o != null && o.length == 2 && out.stream().noneMatch(l -> l.chunkX() == o[0] >> 4 && l.chunkZ() == o[1] >> 4)) {
						out.add(new Located(o[0] >> 4, o[1] >> 4, -1, -1, o[0], 50, o[1], "walls"));
					}
				}
			}
		} else {
			Set<String> anchors = new HashSet<>();
			for (StartTemplates.Spec spec : matcher.templates().specs()) {
				if (spec.kind() == kind) {
					anchors.add(spec.anchor());
				}
			}
			if (anchors.isEmpty()) {
				return out;
			}
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			TemplateMatcher.BlockSource source = (bx, by, bz) -> canonAt(level, pos, bx, by, bz);
			List<TemplateMatcher.Match> matches = new ArrayList<>();
			int budget = 6000;
			for (int dx = -3; dx <= 3 && budget > 0; dx++) {
				for (int dz = -3; dz <= 3 && budget > 0; dz++) {
					LevelChunk chunk = level.getChunkSource().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
					if (chunk == null) {
						continue;
					}
					LevelChunkSection[] sections = chunk.getSections();
					int minSection = chunk.getMinY() >> 4;
					for (int i = 0; i < sections.length && budget > 0; i++) {
						LevelChunkSection section = sections[i];
						if (section == null || section.hasOnlyAir() || !section.maybeHas(st -> anchors.contains(canon(st.getBlock())))) {
							continue;
						}
						for (int by = 0; by < 16; by++) {
							for (int bz = 0; bz < 16; bz++) {
								for (int bx = 0; bx < 16; bx++) {
									String c = canon(section.getBlockState(bx, by, bz).getBlock());
									if (!anchors.contains(c) || --budget < 0) {
										continue;
									}
									matches.addAll(matcher.fromAnchor(chunk.getPos().x() * 16 + bx, (minSection + i) * 16 + by, chunk.getPos().z() * 16 + bz, c,
											source, spec -> spec.kind() == kind));
								}
							}
						}
					}
				}
			}
			Map<Long, Integer> rotation = new HashMap<>();
			Map<Long, TemplateMatcher.Match> first = new HashMap<>();
			for (TemplateMatcher.Match m : matches) {
				long key = Packed.pack(m.chunkX(), m.chunkZ());
				int rot = m.variant().spec().rotationCounts() && m.variant().mirror() == net.minecraft.world.level.block.Mirror.NONE
						? m.variant().startRotation().ordinal() : -1;
				rotation.merge(key, rot, (a, b) -> a.equals(b) ? a : -1);
				first.putIfAbsent(key, m);
			}
			for (Map.Entry<Long, Integer> e : rotation.entrySet()) {
				TemplateMatcher.Match m = first.get(e.getKey());
				out.add(new Located(m.chunkX(), m.chunkZ(), e.getValue(), kind == StructureKind.BASTION ? Observation.PICK_BASTION : -1, m.chunkX() * 16 + 8,
						m.baseY(), m.chunkZ() * 16 + 8, m.variant().spec().template()));
			}
		}
		out.sort((a, b) -> Double.compare(Math.hypot(a.x() - x, a.z() - z), Math.hypot(b.x() - x, b.z() - z)));
		return out;
	}

	/** Diagnostics: what an anchor block at (x, y, z) gives right now, with and without the biome filter. */
	public String explain(ClientLevel level, int x, int y, int z, String anchor) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		TemplateMatcher.BlockSource source = (bx, by, bz) -> canonAt(level, pos, bx, by, bz);
		boolean overworld = dim == SeedWorld.Dim.OVERWORLD;
		Set<String> around = biomesAround(level, x, y, z);
		java.util.function.Predicate<StartTemplates.Spec> filter = spec -> spec.kind().overworld == overworld
				&& (spec.biomes().isEmpty() || around.isEmpty() || around.stream().anyMatch(spec.biomes()::contains));
		int filtered = matcher.fromAnchor(x, y, z, anchor, source, filter).size();
		int all = matcher.fromAnchor(x, y, z, anchor, source).size();
		return "scanned " + scanned.contains(Packed.pack(x >> 4, z >> 4)) + " near unloaded " + nearUnloaded(level, x, z) + " around " + new java.util.TreeSet<>(around)
				+ " matches " + filtered + "/" + all + " parked " + parked.size();
	}

	private void matchHits(ClientLevel level, Session session, Solver solver) {
		if (hits.isEmpty() || matcher == null) {
			return;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		TemplateMatcher.BlockSource source = (x, y, z) -> canonAt(level, pos, x, y, z);
		List<Hit> todo = new ArrayList<>(hits);
		hits.clear();
		boolean overworld = dim == SeedWorld.Dim.OVERWORLD;
		for (Hit h : todo) {
			List<TemplateMatcher.Match> matches = new ArrayList<>();
			Set<String> around = biomesAround(level, h.x, h.y, h.z);
			java.util.function.Predicate<StartTemplates.Spec> filter = spec -> spec.kind().overworld == overworld
					&& (spec.biomes().isEmpty() || around.isEmpty() || around.stream().anyMatch(spec.biomes()::contains));
			for (TemplateMatcher.Match m : matcher.fromAnchor(h.x, h.y, h.z, h.canon, source, filter)) {
				if (m.kind().overworld == overworld) {
					matches.add(m);
				}
			}
			if (matches.isEmpty()) {
				// Part of the start may sit in chunks that have not arrived yet: wait for them instead of giving up.
				if (nearUnloaded(level, h.x, h.z)) {
					long key = Packed.pack(h.x >> 4, h.z >> 4);
					List<Hit> waiting = parked.get(key);
					if (waiting == null) {
						waiting = new ArrayList<>();
						parked.put(key, waiting);
					}
					waiting.add(h);
				} else {
					int[] best = matcher.best(h.x, h.y, h.z, h.canon, source, filter);
					if (best[0] * 2 > best[1]) {
						int n = nearMisses.merge(h, 1, Integer::sum);
						if (n == 1) {
							SeedCrackerClient.LOG.info("Near miss from {} at {} {} {}: {}/{}, chunks {}", h.canon, h.x, h.y, h.z, best[0], best[1],
									chunkStates(level, h.x >> 4, h.z >> 4));
						}
						if (n < NEAR_MISS_RETRIES) {
							later.add(h);
						} else {
							nearMisses.remove(h);
						}
					}
				}
				continue;
			}
			nearMisses.remove(h);
			TemplateMatcher.Match m0 = matches.get(0);
			SeedCrackerClient.LOG.info("Template match {} {} rot {} mirror {} start {} at chunk {} {} y {} score {}/{} from {} at {} {} {}",
					m0.kind().id(), m0.variant().spec().template(), m0.variant().rotation(), m0.variant().mirror(), m0.variant().startRotation(),
					m0.chunkX(), m0.chunkZ(), m0.baseY(), m0.matched(), m0.checked(), h.canon, h.x, h.y, h.z);
			report(level, session, solver, matches);
		}
	}

	/** Biomes at and around a block, at its height and at the surface, as the server sent them. */
	private Set<String> biomesAround(ClientLevel level, int x, int y, int z) {
		Set<String> out = new HashSet<>();
		for (int[] d : new int[][] {{0, 0}, {24, 0}, {-24, 0}, {0, 24}, {0, -24}}) {
			int bx = x + d[0];
			int bz = z + d[1];
			String b = biomeAt(level, bx, y, bz);
			if (b != null) {
				out.add(b);
			}
			if (dim == SeedWorld.Dim.OVERWORLD && loaded(level, bx >> 4, bz >> 4)) {
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
				String s = biomeAt(level, bx, top, bz);
				if (s != null) {
					out.add(s);
				}
			}
		}
		return out;
	}

	/**
	 * Whether the client really holds a chunk. {@code Level.hasChunk} is not it: on the client it also says yes for
	 * chunks that have not arrived, and reading those gives air.
	 */
	private static boolean loaded(ClientLevel level, int chunkX, int chunkZ) {
		return level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
	}

	/** Non-empty sections of the chunks around one, to see whether any of them came in empty. */
	private static String chunkStates(ClientLevel level, int cx, int cz) {
		StringBuilder b = new StringBuilder();
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				LevelChunk c = level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
				int filled = 0;
				if (c != null) {
					for (LevelChunkSection section : c.getSections()) {
						if (section != null && !section.hasOnlyAir()) {
							filled++;
						}
					}
				}
				b.append(dx == -1 && dz == -1 ? "" : dx == -1 ? " / " : ",").append(c == null ? "-" : Integer.toString(filled));
			}
		}
		return b.toString();
	}

	private static boolean nearUnloaded(ClientLevel level, int x, int z) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (!loaded(level, (x >> 4) + dx, (z >> 4) + dz)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Turns the matches of one anchor into an observation: one or several chunks, rotation if they agree. */
	private void report(ClientLevel level, Session session, Solver solver, List<TemplateMatcher.Match> matches) {
		StructureKind kind = matches.get(0).kind();
		Map<Long, Integer> rotation = new HashMap<>();
		Map<Long, Double> score = new HashMap<>();
		for (TemplateMatcher.Match m : matches) {
			if (m.kind() != kind) {
				continue;
			}
			long key = Packed.pack(m.chunkX(), m.chunkZ());
			int rot = m.variant().spec().rotationCounts() && m.variant().mirror() == net.minecraft.world.level.block.Mirror.NONE
					? m.variant().startRotation().ordinal() : -1;
			rotation.merge(key, rot, (a, b) -> a.equals(b) ? a : -1);
			score.merge(key, m.score(), Math::max);
		}
		List<int[]> candidates = new ArrayList<>();
		for (Map.Entry<Long, Integer> e : rotation.entrySet()) {
			candidates.add(new int[] {Packed.x(e.getKey()), Packed.z(e.getKey()), e.getValue()});
		}
		TemplateMatcher.Match best = matches.get(0);
		int pick = kind == StructureKind.BASTION ? Observation.PICK_BASTION : -1;
		String note = best.variant().spec().template();
		add(session, solver, kind, candidates, pick, best.chunkX() * 16 + 8, best.baseY(), best.chunkZ() * 16 + 8, note);
	}

	private void add(Session session, Solver solver, StructureKind kind, List<int[]> candidates, int pick, int x, int y, int z, String note) {
		String dimId = dim == SeedWorld.Dim.OVERWORLD ? "overworld" : "nether";
		String sig = kind.id() + ":" + candidates.get(0)[0] + ":" + candidates.get(0)[1] + ":" + candidates.size() + ":" + candidates.get(0)[2];
		if (!reported.add(sig)) {
			return;
		}
		Session.Obs o = session.addObservation(kind, dimId, candidates, pick, true, x, y, z, note);
		if (o != null) {
			SeedCrackerClient.LOG.info("Detected {} ({}) at {} {} {}", kind.id(), note, x, y, z);
			SeedCrackerClient.onDetected(o);
			solver.request();
		}
	}

	private void addExact(Session session, StructureKind kind, int chunkX, int chunkZ, int pick, int x, int y, int z, String note) {
		List<int[]> c = new ArrayList<>();
		c.add(new int[] {chunkX, chunkZ, -1});
		add(session, SeedCrackerClient.solver(), kind, c, pick, x, y, z, note);
	}

	// ---------------------------------------------------------------- hand-made signatures

	/** The centre of the pyramid floor: blue terracotta at (10, 10) of the start chunk, framed by orange. */
	private void checkDesertPyramid(ClientLevel level, Session session, int x, int y, int z) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int orange = 0;
		for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			if ("orange_terracotta".equals(canonAt(level, p, x + d[0], y, z + d[1]))) {
				orange++;
			}
		}
		if (orange >= 3) {
			addExact(session, StructureKind.DESERT_PYRAMID, x >> 4, z >> 4, -1, x, y, z, "floor");
		}
	}

	/** The whole temple (12x15) fits in its start chunk, so the chunk holding its redstone is the start. */
	private void checkJungleTemple(ClientLevel level, Session session, LevelChunk chunk, Map<String, Integer> counts) {
		int mechanism = counts.getOrDefault("sticky_piston", 0) + counts.getOrDefault("dispenser", 0);
		if (mechanism < 2) {
			return;
		}
		int cx = chunk.getPos().x();
		int cz = chunk.getPos().z();
		if (countInChunk(chunk, "cobblestone") < 60) {
			return;
		}
		String biome = biomeAt(level, cx * 16 + 8, 80, cz * 16 + 8);
		if (biome != null && biome.contains("jungle")) {
			addExact(session, StructureKind.JUNGLE_TEMPLE, cx, cz, -1, cx * 16 + 6, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 6, 7), cz * 16 + 7, "redstone");
		}
	}

	/** The hut (7x9) also fits in its start chunk: cauldron, crafting table and spruce walls on stilts. */
	private void checkSwampHut(ClientLevel level, Session session, LevelChunk chunk, Map<String, Integer> counts) {
		if (counts.getOrDefault("cauldron", 0) < 1 || counts.getOrDefault("crafting_table", 0) < 1) {
			return;
		}
		int cx = chunk.getPos().x();
		int cz = chunk.getPos().z();
		if (countInChunk(chunk, "wood_planks") < 20) {
			return;
		}
		String biome = biomeAt(level, cx * 16 + 8, 64, cz * 16 + 8);
		if ("swamp".equals(biome)) {
			addExact(session, StructureKind.SWAMP_HUT, cx, cz, -1, cx * 16 + 3, 64, cz * 16 + 4, "hut");
		}
	}

	/** How many blocks of one canonical type a chunk has (only sections that may contain it are read). */
	private int countInChunk(LevelChunk chunk, String target) {
		int n = 0;
		for (LevelChunkSection section : chunk.getSections()) {
			if (section == null || section.hasOnlyAir() || !section.maybeHas(st -> canon(st.getBlock()).equals(target))) {
				continue;
			}
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (canon(section.getBlockState(x, y, z).getBlock()).equals(target)) {
							n++;
						}
					}
				}
			}
		}
		return n;
	}

	/** Buried treasure always sits at (9, 9) of its chunk, under sand or stone. */
	private void checkBuriedTreasure(ClientLevel level, Session session, int x, int y, int z) {
		if (dim != SeedWorld.Dim.OVERWORLD || y > 120) {
			return;
		}
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int buried = 0;
		for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}, {0, 1, 0}}) {
			String c = canonAt(level, p, x + d[0], y + d[1], z + d[2]);
			if (c != null && (c.equals("sand") || c.equals("sandstone") || c.equals("gravel") || c.equals("stone") || c.equals("dirt")
					|| c.equals("andesite") || c.equals("granite") || c.equals("diorite") || c.equals("water"))) {
				buried++;
			}
		}
		String biome = biomeAt(level, x, y, z);
		boolean beach = biome != null && biome.endsWith("beach");
		if (beach && buried >= 3 || buried >= 6) {
			addExact(session, StructureKind.BURIED_TREASURE, x >> 4, z >> 4, -1, x, y, z, "chest");
		}
	}

	/**
	 * A monument is 58 blocks square around the corner of its start chunk. Returns 0 while its surroundings are
	 * still loading, 1 when done (found or not).
	 */
	private int checkMonument(Session session, int cx, int cz) {
		int[] o = monumentStart(cx, cz);
		if (o == null) {
			return 0;
		}
		if (o.length == 2) {
			addExact(session, StructureKind.OCEAN_MONUMENT, o[0] >> 4, o[1] >> 4, -1, o[0], 50, o[1], "walls");
		}
		return 1;
	}

	/** Centre {x, z} of the monument whose prismarine is around this chunk; empty if there is none, null while loading. */
	private int[] monumentStart(int cx, int cz) {
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				long key = Packed.pack(cx + dx, cz + dz);
				if (!scanned.contains(key)) {
					return null;
				}
				Extent e = prismarine.get(key);
				if (e != null && e.count > 8) {
					minX = Math.min(minX, e.minX);
					maxX = Math.max(maxX, e.maxX);
					minZ = Math.min(minZ, e.minZ);
					maxZ = Math.max(maxZ, e.maxZ);
				}
			}
		}
		int ox = monumentOrigin(minX, maxX);
		int oz = monumentOrigin(minZ, maxZ);
		return ox != Integer.MIN_VALUE && oz != Integer.MIN_VALUE ? new int[] {ox, oz} : new int[0];
	}

	/** The building spans origin-29..origin+28; a little erosion at one edge is tolerated. */
	private static int monumentOrigin(int min, int max) {
		int width = max - min;
		if (width < 50 || width > 57) {
			return Integer.MIN_VALUE;
		}
		if (((min + 29) & 15) == 0) {
			return min + 29;
		}
		if (((max - 28) & 15) == 0) {
			return max - 28;
		}
		return Integer.MIN_VALUE;
	}

	/**
	 * The fortress start is a bridge crossing whose box begins at (2, 2) of the start chunk: a plus-shaped nether
	 * brick deck, arms 5 wide and 19 long. Other crossings only rarely line up with a chunk like that.
	 */
	private void checkFortress(ClientLevel level, Session session, int cx, int cz) {
		long key = Packed.pack(cx, cz);
		if (fortressChecked.contains(key)) {
			return;
		}
		int y = crossingDeck(level, cx, cz);
		if (y != Integer.MIN_VALUE && fortressChecked.add(key)) {
			int ox = cx * 16 + 2;
			int oz = cz * 16 + 2;
			int pick = basaltNear(level, ox + 9, oz + 9) ? -1 : Observation.PICK_FORTRESS;
			addExact(session, StructureKind.FORTRESS, cx, cz, pick, ox + 9, y, oz + 9, "crossing");
		}
	}

	/** Deck height of a fortress start crossing with its corner at (2, 2) of this chunk, or MIN_VALUE. */
	private int crossingDeck(ClientLevel level, int cx, int cz) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int ox = cx * 16 + 2;
		int oz = cz * 16 + 2;
		for (int y = 20; y <= 110; y++) {
			int total = 0;
			int bricks = 0;
			for (int t : new int[] {0, 4, 8, 12, 16, 18}) {
				for (int[] d : new int[][] {{t, 7}, {t, 11}, {7, t}, {11, t}}) {
					for (int dy = 0; dy <= 1; dy++) {
						String c = canonAt(level, p, ox + d[0], y + dy, oz + d[1]);
						if (c == null) {
							return Integer.MIN_VALUE;
						}
						total++;
						if (c.equals("nether_bricks")) {
							bricks++;
						}
					}
				}
			}
			if (bricks >= total * 0.9 && crossingShape(level, p, ox, y, oz)) {
				return y;
			}
		}
		return Integer.MIN_VALUE;
	}

	/**
	 * Solid brick masses (castle floors, pillars) also fill the cross; a real crossing has an open walkway above its
	 * deck and empty corners, which no other fortress piece can occupy.
	 */
	private boolean crossingShape(ClientLevel level, BlockPos.MutableBlockPos p, int ox, int deckY, int oz) {
		int open = 0;
		int checked = 0;
		for (int t : new int[] {0, 4, 8, 12, 16, 18}) {
			for (int[] d : new int[][] {{t, 9}, {9, t}}) {
				String c = canonAt(level, p, ox + d[0], deckY + 2, oz + d[1]);
				checked++;
				if (c != null && !c.equals("nether_bricks")) {
					open++;
				}
			}
		}
		for (int[] d : new int[][] {{0, 0}, {18, 0}, {0, 18}, {18, 18}, {2, 2}, {16, 2}, {2, 16}, {16, 16}, {4, 4}, {14, 4}, {4, 14}, {14, 14}}) {
			for (int dy = 0; dy <= 1; dy++) {
				String c = canonAt(level, p, ox + d[0], deckY + dy, oz + d[1]);
				checked++;
				if (c != null && !c.equals("nether_bricks")) {
					open++;
				}
			}
		}
		return open >= checked * 0.9;
	}

	/** Fortress vs bastion is decided before biomes, unless basalt deltas veto the bastion; be careful near them. */
	private boolean basaltNear(ClientLevel level, int x, int z) {
		for (int dx = -48; dx <= 48; dx += 8) {
			for (int dz = -48; dz <= 48; dz += 8) {
				for (int y = 24; y <= 56; y += 16) {
					String b = biomeAt(level, x + dx, y, z + dz);
					if (b == null || b.equals("basalt_deltas")) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
