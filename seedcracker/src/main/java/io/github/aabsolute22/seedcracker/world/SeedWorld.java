package io.github.aabsolute22.seedcracker.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;

import io.github.aabsolute22.seedcracker.core.Packed;
import io.github.aabsolute22.seedcracker.core.Spread;
import io.github.aabsolute22.seedcracker.core.StructureKind;

/**
 * Vanilla world generation of one dimension for one full seed: biomes, terrain heights and structure starts,
 * computed by the game's own code (the same calls {@code ChunkGenerator.createStructures} makes).
 */
public final class SeedWorld {
	public enum Dim {
		OVERWORLD(Level.OVERWORLD, NoiseGeneratorSettings.OVERWORLD, MultiNoiseBiomeSourceParameterLists.OVERWORLD, -64, 384),
		NETHER(Level.NETHER, NoiseGeneratorSettings.NETHER, MultiNoiseBiomeSourceParameterLists.NETHER, 0, 256);

		public final ResourceKey<Level> level;
		public final ResourceKey<NoiseGeneratorSettings> settings;
		public final ResourceKey<MultiNoiseBiomeSourceParameterList> preset;
		public final int minY;
		public final int height;

		Dim(ResourceKey<Level> level, ResourceKey<NoiseGeneratorSettings> settings, ResourceKey<MultiNoiseBiomeSourceParameterList> preset, int minY, int height) {
			this.level = level;
			this.settings = settings;
			this.preset = preset;
			this.minY = minY;
			this.height = height;
		}

		public boolean overworld() {
			return this == OVERWORLD;
		}

		public static Dim of(ResourceKey<Level> level) {
			for (Dim d : values()) {
				if (d.level == level || d.level.equals(level)) {
					return d;
				}
			}
			return null;
		}
	}

	/** A structure that really generates, with the point worth walking to. */
	public record Found(StructureKind kind, String structure, int chunkX, int chunkZ, int x, int y, int z, BoundingBox box, String variant,
			List<BlockPos> extras) {
		public long key() {
			return Packed.pack(chunkX, chunkZ) ^ ((long) kind.ordinal() << 58);
		}

		public double distanceTo(double px, double pz) {
			return Math.hypot(x + 0.5 - px, z + 0.5 - pz);
		}
	}

	private final Worldgen worldgen;
	private final long seed;
	private final Dim dim;
	private final MultiNoiseBiomeSource biomeSource;
	private final NoiseBasedChunkGenerator generator;
	private final RandomState randomState;
	private final ChunkGeneratorStructureState structureState;
	private final LevelHeightAccessor heightAccessor;
	private final Map<String, Holder<StructureSet>> sets = new HashMap<>();
	private final Map<Long, Optional<Found>> found = new ConcurrentHashMap<>();
	private volatile List<Found> strongholds;

	public SeedWorld(Worldgen worldgen, long seed, Dim dim) {
		this.worldgen = worldgen;
		this.seed = seed;
		this.dim = dim;
		var registries = worldgen.registries();
		Holder<MultiNoiseBiomeSourceParameterList> preset = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(dim.preset);
		this.biomeSource = MultiNoiseBiomeSource.createFromPreset(preset);
		Holder<NoiseGeneratorSettings> settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(dim.settings);
		this.generator = new NoiseBasedChunkGenerator(biomeSource, settings);
		this.randomState = RandomState.create(settings.value(), registries.lookupOrThrow(Registries.NOISE), seed);
		this.structureState = generator.createState(registries.lookupOrThrow(Registries.STRUCTURE_SET), randomState, seed);
		this.heightAccessor = LevelHeightAccessor.create(dim.minY, dim.height);
		for (Holder<StructureSet> set : structureState.possibleStructureSets()) {
			set.unwrapKey().ifPresent(key -> sets.put(key.identifier().getPath(), set));
		}
	}

	public long seed() {
		return seed;
	}

	public Dim dim() {
		return dim;
	}

	public RandomState randomState() {
		return randomState;
	}

	public NoiseBasedChunkGenerator generator() {
		return generator;
	}

	public LevelHeightAccessor heightAccessor() {
		return heightAccessor;
	}

	public boolean hasSet(Spread spread) {
		return sets.containsKey(spread.id());
	}

	// ---------------------------------------------------------------- biomes

	public Holder<Biome> biome(int quartX, int quartY, int quartZ) {
		return biomeSource.getNoiseBiome(quartX, quartY, quartZ, randomState.sampler());
	}

	public static String biomeId(Holder<Biome> biome) {
		return biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
	}

	public String biomeIdAt(int blockX, int blockY, int blockZ) {
		return biomeId(biome(blockX >> 2, blockY >> 2, blockZ >> 2));
	}

	public int surfaceY(int blockX, int blockZ) {
		return generator.getBaseHeight(blockX, blockZ, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState);
	}

	public int oceanFloorY(int blockX, int blockZ) {
		return generator.getBaseHeight(blockX, blockZ, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, randomState);
	}

	// ---------------------------------------------------------------- structures

	/** Mirrors {@code ChunkGenerator.createStructures} for one structure set and chunk. */
	public Optional<StructureStart> startAt(Holder<StructureSet> set, int chunkX, int chunkZ) {
		StructurePlacement placement = set.value().placement();
		if (!placement.isStructureChunk(structureState, chunkX, chunkZ)) {
			return Optional.empty();
		}
		ChunkPos pos = new ChunkPos(chunkX, chunkZ);
		List<StructureSet.StructureSelectionEntry> entries = set.value().structures();
		if (entries.size() == 1) {
			return tryGenerate(entries.get(0), pos);
		}
		List<StructureSet.StructureSelectionEntry> left = new ArrayList<>(entries);
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
		random.setLargeFeatureSeed(seed, chunkX, chunkZ);
		int total = 0;
		for (StructureSet.StructureSelectionEntry e : left) {
			total += e.weight();
		}
		while (!left.isEmpty()) {
			int j = random.nextInt(total);
			int k = 0;
			for (StructureSet.StructureSelectionEntry e : left) {
				j -= e.weight();
				if (j < 0) {
					break;
				}
				k++;
			}
			StructureSet.StructureSelectionEntry chosen = left.get(k);
			Optional<StructureStart> start = tryGenerate(chosen, pos);
			if (start.isPresent()) {
				return start;
			}
			left.remove(k);
			total -= chosen.weight();
		}
		return Optional.empty();
	}

	private Optional<StructureStart> tryGenerate(StructureSet.StructureSelectionEntry entry, ChunkPos pos) {
		Structure structure = entry.structure().value();
		StructureStart start = structure.generate(entry.structure(), dim.level, worldgen.registries(), generator, biomeSource, randomState,
				worldgen.templates(), seed, pos, 0, heightAccessor, structure.biomes()::contains);
		return start.isValid() ? Optional.of(start) : Optional.empty();
	}

	public Holder<StructureSet> set(Spread spread) {
		return sets.get(spread.id());
	}

	/** The structure of a {@code random_spread} set in one region, if it really generates. */
	public Optional<Found> inRegion(Spread spread, int regionX, int regionZ) {
		long p = spread.potentialChunk(seed, regionX, regionZ);
		return at(spread, Packed.x(p), Packed.z(p));
	}

	/** The structure of {@code spread}'s set starting in this chunk, if any. */
	public Optional<Found> at(Spread spread, int chunkX, int chunkZ) {
		long key = Packed.pack(chunkX, chunkZ) * 31 + spread.salt() * 7919L + spread.spacing();
		Optional<Found> cached = found.get(key);
		if (cached != null) {
			return cached;
		}
		Holder<StructureSet> set = sets.get(spread.id());
		Optional<Found> result = Optional.empty();
		if (set != null) {
			result = startAt(set, chunkX, chunkZ).map(start -> describe(start, chunkX, chunkZ)).filter(f -> f.kind() != null);
		}
		if (found.size() > 50_000) {
			found.clear();
		}
		found.put(key, result);
		return result;
	}

	/** Every structure of {@code kind} whose start is within {@code radius} blocks of (x, z), nearest first. */
	public List<Found> near(StructureKind kind, double x, double z, int radius) {
		if (kind == StructureKind.STRONGHOLD) {
			List<Found> out = new ArrayList<>();
			for (Found f : strongholds()) {
				if (f.distanceTo(x, z) <= radius) {
					out.add(f);
				}
			}
			out.sort((a, b) -> Double.compare(a.distanceTo(x, z), b.distanceTo(x, z)));
			return out;
		}
		Spread spread = kind.spread;
		if (spread == null || kind.overworld != dim.overworld() || !sets.containsKey(spread.id())) {
			return List.of();
		}
		if (spread.spacing() == 1) {
			return nearPerChunk(kind, spread, x, z, radius);
		}
		int span = spread.spacing() * 16;
		int rx0 = Math.floorDiv((int) Math.floor(x) - radius, span);
		int rx1 = Math.floorDiv((int) Math.floor(x) + radius, span);
		int rz0 = Math.floorDiv((int) Math.floor(z) - radius, span);
		int rz1 = Math.floorDiv((int) Math.floor(z) + radius, span);
		List<Found> out = new ArrayList<>();
		for (int rx = rx0; rx <= rx1; rx++) {
			for (int rz = rz0; rz <= rz1; rz++) {
				long p = spread.potentialChunk(seed, rx, rz);
				double cx = Packed.x(p) * 16 + 8;
				double cz = Packed.z(p) * 16 + 8;
				if (Math.hypot(cx - x, cz - z) > radius + 64) {
					continue;
				}
				inRegion(spread, rx, rz).filter(f -> f.kind() == kind && f.distanceTo(x, z) <= radius).ifPresent(out::add);
			}
		}
		out.sort((a, b) -> Double.compare(a.distanceTo(x, z), b.distanceTo(x, z)));
		return out;
	}

	/** Buried treasure and mineshafts can start in any chunk; only chunks passing the cheap draw are generated. */
	private List<Found> nearPerChunk(StructureKind kind, Spread spread, double x, double z, int radius) {
		int r = Math.min(radius, 1024) >> 4;
		int ccx = (int) Math.floor(x) >> 4;
		int ccz = (int) Math.floor(z) >> 4;
		List<Found> out = new ArrayList<>();
		for (int cx = ccx - r; cx <= ccx + r; cx++) {
			for (int cz = ccz - r; cz <= ccz + r; cz++) {
				if (!spread.frequencyAccepts(seed, cx, cz)) {
					continue;
				}
				at(spread, cx, cz).filter(f -> f.kind() == kind && f.distanceTo(x, z) <= radius).ifPresent(out::add);
			}
		}
		out.sort((a, b) -> Double.compare(a.distanceTo(x, z), b.distanceTo(x, z)));
		return out;
	}

	public List<Found> strongholds() {
		List<Found> s = strongholds;
		if (s != null) {
			return s;
		}
		List<Found> out = new ArrayList<>();
		Holder<StructureSet> set = sets.get("strongholds");
		if (set != null && set.value().placement() instanceof ConcentricRingsStructurePlacement rings) {
			List<ChunkPos> positions = structureState.getRingPositionsFor(rings);
			if (positions != null) {
				for (ChunkPos pos : positions) {
					startAt(set, pos.x(), pos.z()).map(start -> describe(start, pos.x(), pos.z())).ifPresent(out::add);
				}
			}
		}
		strongholds = out;
		return out;
	}

	private Found describe(StructureStart start, int chunkX, int chunkZ) {
		Structure structure = start.getStructure();
		String id = worldgen.registries().lookupOrThrow(Registries.STRUCTURE).getKey(structure) instanceof Identifier ident ? ident.getPath() : "?";
		StructureKind kind = StructureKind.ofStructure(id, dim.overworld());
		List<StructurePiece> pieces = start.getPieces();
		BoundingBox box = start.getBoundingBox();
		StructurePiece first = pieces.isEmpty() ? null : pieces.get(0);
		BoundingBox target = first != null ? first.getBoundingBox() : box;
		String variant = variantOf(id, first);
		List<BlockPos> extras = new ArrayList<>();
		int x = (target.minX() + target.maxX()) / 2;
		int z = (target.minZ() + target.maxZ()) / 2;
		int y = (target.minY() + target.maxY()) / 2;
		if (kind == StructureKind.STRONGHOLD) {
			for (StructurePiece p : pieces) {
				if (p.getType() == StructurePieceType.STRONGHOLD_PORTAL_ROOM) {
					BoundingBox b = p.getBoundingBox();
					x = (b.minX() + b.maxX()) / 2;
					y = b.minY() + 3;
					z = (b.minZ() + b.maxZ()) / 2;
				}
			}
		} else if (kind == StructureKind.FORTRESS) {
			for (StructurePiece p : pieces) {
				if (p.getType() == StructurePieceType.NETHER_FORTRESS_MONSTER_THRONE) {
					BoundingBox b = p.getBoundingBox();
					extras.add(new BlockPos((b.minX() + b.maxX()) / 2, b.minY() + 5, (b.minZ() + b.maxZ()) / 2));
				}
			}
		} else if (kind == StructureKind.BURIED_TREASURE) {
			x = chunkX * 16 + 9;
			z = chunkZ * 16 + 9;
			y = oceanFloorY(x, z) - 1;
		} else if (!(first instanceof TemplateStructurePiece) && !(first instanceof PoolElementStructurePiece) && dim.overworld()
				&& kind != StructureKind.OCEAN_MONUMENT && kind != StructureKind.MINESHAFT) {
			// Code-built pieces (temples) settle on the ground only when placed; use the terrain height.
			y = surfaceY(x, z);
		}
		return new Found(kind, id, chunkX, chunkZ, x, y, z, box, variant, extras);
	}

	private static String variantOf(String id, StructurePiece first) {
		String template = "";
		if (first instanceof PoolElementStructurePiece pool && pool.getElement() instanceof SinglePoolElement single) {
			template = single.getTemplateLocation().getPath();
		}
		if (id.startsWith("village_")) {
			return id.substring("village_".length()) + (template.contains("zombie") ? "_zombie" : "");
		}
		if (id.startsWith("bastion")) {
			String[] parts = template.split("/");
			return parts.length > 1 ? parts[1] : "";
		}
		if (id.startsWith("ruined_portal")) {
			return id.equals("ruined_portal") ? "standard" : id.substring("ruined_portal_".length());
		}
		if (id.startsWith("ocean_ruin_")) {
			return id.substring("ocean_ruin_".length());
		}
		if (id.equals("shipwreck_beached")) {
			return "beached";
		}
		if (id.equals("mineshaft_mesa")) {
			return "mesa";
		}
		return "";
	}

	// ---------------------------------------------------------------- slime chunks

	public static boolean isSlimeChunk(long seed, int chunkX, int chunkZ) {
		return WorldgenRandom.seedSlimeChunk(chunkX, chunkZ, seed, 987234911L).nextInt(10) == 0;
	}
}
