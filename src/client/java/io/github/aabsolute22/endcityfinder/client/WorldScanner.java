package io.github.aabsolute22.endcityfinder.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.client.multiplayer.ClientLevel;

import io.github.aabsolute22.endcityfinder.core.BaseFloorLocator;
import io.github.aabsolute22.endcityfinder.core.EndSpikes;
import io.github.aabsolute22.endcityfinder.core.Placement;
import io.github.aabsolute22.endcityfinder.core.SeedCracker;

/**
 * Reads what the client already has loaded in the End: pillar heights, End city blocks, empty chunks and
 * terrain samples. Runs on the client thread with a small per-tick budget.
 */
public final class WorldScanner {
	private static final int CHUNKS_PER_TICK = 6;
	/** Cities never start this close to the origin (the central End biome), so purpur here is player-made. */
	private static final int MIN_CITY_CHUNK_DISTANCE = 60;
	private static final int SAMPLE_MIN_CHUNK_DISTANCE = 70;

	/** Lowest layer of city blocks inside one chunk, as packed world columns. */
	private record CityChunk(int minY, long[] columns) {
	}

	private final ArrayDeque<Long> queue = new ArrayDeque<>();
	private final LongOpenHashSet queued = new LongOpenHashSet();
	private final LongOpenHashSet scanned = new LongOpenHashSet();
	private final Long2ObjectOpenHashMap<CityChunk> cityChunks = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet reported = new LongOpenHashSet();
	private boolean cityChunksChanged;
	private int ticks;

	public void reset() {
		queue.clear();
		queued.clear();
		scanned.clear();
		cityChunks.clear();
		reported.clear();
	}

	public void enqueue(int chunkX, int chunkZ) {
		long key = Placement.pack(chunkX, chunkZ);
		if (queued.add(key)) {
			queue.add(key);
		}
	}

	public static boolean isCityBlock(BlockState state) {
		Block b = state.getBlock();
		return b == Blocks.PURPUR_BLOCK || b == Blocks.PURPUR_PILLAR || b == Blocks.PURPUR_STAIRS
				|| b == Blocks.PURPUR_SLAB || b == Blocks.END_STONE_BRICKS;
	}

	public void tick(ClientLevel level, FinderSession session, double playerX, double playerZ) {
		if (level.dimension() != Level.END) {
			return;
		}
		for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) {
			long key = queue.poll();
			queued.remove(key);
			LevelChunk chunk = level.getChunkSource().getChunk(Placement.unpackX(key), Placement.unpackZ(key), ChunkStatus.FULL, false);
			if (chunk != null) {
				scanChunk(chunk, session);
			}
		}
		ticks++;
		if (ticks % 20 == 0 && playerX * playerX + playerZ * playerZ < 400 * 400) {
			scanPillars(level, session);
		}
		if (ticks % 20 == 10 && cityChunksChanged) {
			cityChunksChanged = false;
			detectCities(session, false, 0, 0);
		}
	}

	private void scanChunk(LevelChunk chunk, FinderSession session) {
		int cx = chunk.getPos().x();
		int cz = chunk.getPos().z();
		long key = Placement.pack(cx, cz);
		LevelChunkSection[] sections = chunk.getSections();
		int minSection = chunk.getMinY() >> 4;
		int minY = Integer.MAX_VALUE;
		LongArrayList columns = new LongArrayList();
		for (int i = 0; i < sections.length && minY == Integer.MAX_VALUE; i++) {
			LevelChunkSection section = sections[i];
			if (section == null || section.hasOnlyAir() || !section.maybeHas(WorldScanner::isCityBlock)) {
				continue;
			}
			int baseY = (minSection + i) * 16;
			for (int y = 0; y < 16 && minY == Integer.MAX_VALUE; y++) {
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						if (isCityBlock(section.getBlockState(x, y, z))) {
							minY = baseY + y;
							columns.add(Placement.pack(cx * 16 + x, cz * 16 + z));
						}
					}
				}
			}
		}
		scanned.add(key);
		if (minY != Integer.MAX_VALUE) {
			if (Math.abs(cx) > MIN_CITY_CHUNK_DISTANCE || Math.abs(cz) > MIN_CITY_CHUNK_DISTANCE) {
				cityChunks.put(key, new CityChunk(minY, columns.toLongArray()));
				cityChunksChanged = true;
			}
			return;
		}
		session.addEmptyChunk(cx, cz);
		if ((Math.abs(cx) > SAMPLE_MIN_CHUNK_DISTANCE || Math.abs(cz) > SAMPLE_MIN_CHUNK_DISTANCE) && Math.floorMod(cx * 31 + cz * 17, 5) == 0) {
			session.addSample(cx * 16 + 8, cz * 16 + 8, topEndStone(sections, minSection, 8, 8));
		}
	}

	/** Highest end stone in a chunk column below y 128 (the End noise ceiling), or -1. */
	private static int topEndStone(LevelChunkSection[] sections, int minSection, int x, int z) {
		for (int i = Math.min(sections.length - 1, (128 >> 4) - 1 - minSection); i >= 0; i--) {
			LevelChunkSection section = sections[i];
			if (section == null || section.hasOnlyAir()) {
				continue;
			}
			for (int y = 15; y >= 0; y--) {
				if (section.getBlockState(x, y, z).getBlock() == Blocks.END_STONE) {
					return (minSection + i) * 16 + y;
				}
			}
		}
		return -1;
	}

	// ---------------------------------------------------------------- pillars

	private void scanPillars(ClientLevel level, FinderSession session) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < EndSpikes.COUNT; i++) {
			int x = EndSpikes.centerX(i);
			int z = EndSpikes.centerZ(i);
			if (!level.hasChunk(x >> 4, z >> 4)) {
				continue;
			}
			int height = spikeHeight(level, pos, x, z);
			int k = EndSpikes.sizeOfHeight(height);
			if (k >= 0 && radiusMatches(level, pos, x, z, height, 2 + k / 3)) {
				session.setSpike(i, k);
			}
		}
	}

	private static Block block(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
		return level.getBlockState(pos.set(x, y, z)).getBlock();
	}

	/** The pillar's height: the bedrock block the crystal stood on, or one above the top obsidian. */
	private static int spikeHeight(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int z) {
		for (int y = 110; y >= 70; y--) {
			if (block(level, pos, x, y, z) == Blocks.BEDROCK && block(level, pos, x, y - 1, z) == Blocks.OBSIDIAN) {
				return y;
			}
		}
		for (int y = 110; y >= 70; y--) {
			if (block(level, pos, x, y, z) == Blocks.OBSIDIAN) {
				return y + 1;
			}
		}
		return -1;
	}

	/** Pillar radius is {@code 2 + k / 3}; checking it guards against griefed or player-built tops. */
	private static boolean radiusMatches(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int z, int height, int radius) {
		int y = height - 3;
		return block(level, pos, x + radius, y, z) == Blocks.OBSIDIAN && block(level, pos, x - radius, y, z) == Blocks.OBSIDIAN
				&& block(level, pos, x + radius + 1, y, z) != Blocks.OBSIDIAN && block(level, pos, x - radius - 1, y, z) != Blocks.OBSIDIAN;
	}

	// ---------------------------------------------------------------- cities

	/**
	 * Groups chunks with city blocks into cities and locates each city's base floor.
	 * With {@code nearOnly} only cities within 160 blocks of (x, z) are considered, even if not fully loaded,
	 * and they count as confirmed by the player.
	 * Returns the cities added.
	 */
	public List<FinderSession.ObservedCity> detectCities(FinderSession session, boolean nearOnly, double x, double z) {
		List<FinderSession.ObservedCity> added = new ArrayList<>();
		LongOpenHashSet seen = new LongOpenHashSet();
		for (long start : cityChunks.keySet().toLongArray()) {
			if (seen.contains(start)) {
				continue;
			}
			LongArrayList cluster = new LongArrayList();
			ArrayDeque<Long> todo = new ArrayDeque<>();
			todo.add(start);
			seen.add(start);
			while (!todo.isEmpty()) {
				long c = todo.poll();
				cluster.add(c);
				int cx = Placement.unpackX(c);
				int cz = Placement.unpackZ(c);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						long n = Placement.pack(cx + dx, cz + dz);
						if (cityChunks.containsKey(n) && seen.add(n)) {
							todo.add(n);
						}
					}
				}
			}
			int minY = Integer.MAX_VALUE;
			for (long c : cluster) {
				minY = Math.min(minY, cityChunks.get(c).minY());
			}
			boolean complete = true;
			int minX = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxZ = Integer.MIN_VALUE;
			for (long c : cluster) {
				CityChunk chunk = cityChunks.get(c);
				if (chunk.minY() != minY) {
					continue;
				}
				int cx = Placement.unpackX(c);
				int cz = Placement.unpackZ(c);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (!scanned.contains(Placement.pack(cx + dx, cz + dz))) {
							complete = false;
						}
					}
				}
				for (long col : chunk.columns()) {
					minX = Math.min(minX, Placement.unpackX(col));
					maxX = Math.max(maxX, Placement.unpackX(col));
					minZ = Math.min(minZ, Placement.unpackZ(col));
					maxZ = Math.max(maxZ, Placement.unpackZ(col));
				}
			}
			int centerX = (minX + maxX) / 2;
			int centerZ = (minZ + maxZ) / 2;
			if (nearOnly) {
				if (Math.hypot(centerX - x, centerZ - z) > 160) {
					continue;
				}
			} else if (!complete) {
				continue;
			}
			long signature = Placement.pack(minX, minZ) ^ ((long) minY << 58);
			if (!nearOnly && !reported.add(signature)) {
				continue;
			}
			BaseFloorLocator.Result located = BaseFloorLocator.locate(minX, minZ, maxX, maxZ);
			List<SeedCracker.Candidate> candidates = located.candidates();
			FinderSession.ObservedCity city = session.addCity(candidates, centerX, centerZ, !nearOnly, located.exact());
			added.add(city);
			if (!nearOnly) {
				EndCityFinderClient.onCityDetected(city);
			}
		}
		return added;
	}
}
