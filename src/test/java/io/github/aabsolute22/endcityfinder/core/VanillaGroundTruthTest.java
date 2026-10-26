package io.github.aabsolute22.endcityfinder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * Compares the standalone core against the real Minecraft 26.2 world generation code.
 */
class VanillaGroundTruthTest {
	private static HolderLookup.Provider lookup;
	private static Holder<NoiseGeneratorSettings> endSettings;
	private static BiomeSource biomeSource;
	private static NoiseBasedChunkGenerator generator;
	private static Structure endCity;
	private static RandomSpreadStructurePlacement placement;
	private static StructureTemplateManager templates;
	private static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(0, 256);

	@BeforeAll
	static void bootstrap() throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		lookup = VanillaRegistries.createLookup();
		endSettings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.END);
		biomeSource = TheEndBiomeSource.create(lookup.lookupOrThrow(Registries.BIOME));
		generator = new NoiseBasedChunkGenerator(biomeSource, endSettings);
		endCity = lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.END_CITY).value();
		placement = (RandomSpreadStructurePlacement) lookup.lookupOrThrow(Registries.STRUCTURE_SET)
				.getOrThrow(BuiltinStructureSets.END_CITIES).value().placement();
		Path dir = Files.createTempDirectory("ecf-test");
		MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource()));
		templates = new StructureTemplateManager(resources, LevelStorageSource.createDefault(dir).createAccess("test"),
				DataFixers.getDataFixer(), BuiltInRegistries.BLOCK);
	}

	private static long[] seeds(int count, long salt) {
		Random random = new Random(salt);
		long[] seeds = new long[count];
		for (int i = 0; i < count; i++) {
			seeds[i] = random.nextLong();
		}
		return seeds;
	}

	private static RandomState randomState(long seed) {
		return RandomState.create(endSettings.value(), lookup.lookupOrThrow(Registries.NOISE), seed);
	}

	private static WorldGenLevel levelWithSeed(long seed) {
		return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, (proxy, method, args) -> {
			if (method.getName().equals("getSeed")) {
				return seed;
			}
			throw new UnsupportedOperationException(method.getName());
		});
	}

	@Test
	void placementMatchesVanilla() {
		assertEquals(Placement.END_CITIES.spacing(), placement.spacing());
		assertEquals(Placement.END_CITIES.separation(), placement.separation());
		for (long seed : seeds(20, 1)) {
			for (int rx = -10; rx <= 10; rx++) {
				for (int rz = -10; rz <= 10; rz++) {
					ChunkPos vanilla = placement.getPotentialStructureChunk(seed, rx * 20, rz * 20);
					long core = Placement.END_CITIES.potentialChunk(seed, rx, rz);
					assertEquals(vanilla.x(), Placement.unpackX(core));
					assertEquals(vanilla.z(), Placement.unpackZ(core));
				}
			}
		}
	}

	@Test
	void spikesMatchVanilla() {
		for (long seed : seeds(200, 2)) {
			List<EndSpikeFeature.EndSpike> vanilla = EndSpikeFeature.getSpikesForLevel(levelWithSeed(seed));
			EndSpikes.Spike[] core = EndSpikes.spikes(EndSpikes.key(seed));
			assertEquals(vanilla.size(), core.length);
			for (int i = 0; i < core.length; i++) {
				EndSpikeFeature.EndSpike v = vanilla.get(i);
				assertEquals(v.getCenterX(), core[i].x(), "spike x " + i);
				assertEquals(v.getCenterZ(), core[i].z(), "spike z " + i);
				assertEquals(v.getRadius(), core[i].radius(), "spike radius " + i);
				assertEquals(v.getHeight(), core[i].height(), "spike height " + i);
				assertEquals(v.isGuarded(), core[i].guarded(), "spike guarded " + i);
			}
		}
	}

	@Test
	void terrainAndBiomesMatchVanilla() {
		Random random = new Random(3);
		for (long seed : seeds(6, 3)) {
			RandomState state = randomState(seed);
			EndWorld world = new EndWorld(seed);
			int solid = 0;
			for (int i = 0; i < 400; i++) {
				int x = random.nextInt(8000) - 4000;
				int z = random.nextInt(8000) - 4000;
				int vanilla = generator.getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, HEIGHT, state);
				int core = world.terrain().surfaceY(x, z);
				assertEquals(vanilla, core, "surface at " + x + " " + z + " seed " + seed);
				if (core >= 0) {
					solid++;
				}
				Holder<Biome> biome = biomeSource.getNoiseBiome(x >> 2, 16, z >> 2, state.sampler());
				EndBiome expected = biome.is(Biomes.THE_END) ? EndBiome.THE_END
						: biome.is(Biomes.END_HIGHLANDS) ? EndBiome.HIGHLANDS
						: biome.is(Biomes.END_MIDLANDS) ? EndBiome.MIDLANDS
						: biome.is(Biomes.END_BARRENS) ? EndBiome.BARRENS : EndBiome.SMALL_ISLANDS;
				assertEquals(expected, world.islands().biomeOfChunk(x >> 4, z >> 4), "biome at " + x + " " + z);
			}
			assertTrue(solid > 20, "too few solid samples to be meaningful: " + solid);
		}
	}

	/** Contents of #minecraft:has_structure/end_city; tags are not bound in a bare registry lookup. */
	private static boolean isEndCityBiome(Holder<Biome> biome) {
		return biome.is(Biomes.END_HIGHLANDS) || biome.is(Biomes.END_MIDLANDS);
	}

	private static Optional<Structure.GenerationStub> vanillaCity(long seed, RandomState state, ChunkPos pos) {
		Structure.GenerationContext context = new Structure.GenerationContext(RegistryAccess.EMPTY, generator, biomeSource, state,
				templates, seed, pos, HEIGHT, VanillaGroundTruthTest::isEndCityBiome);
		return endCity.findValidGenerationPoint(context);
	}

	@Test
	void citiesAndShipsMatchVanilla() {
		int cities = 0;
		int ships = 0;
		for (long seed : seeds(8, 4)) {
			RandomState state = randomState(seed);
			EndWorld world = new EndWorld(seed);
			for (int rx = -6; rx <= 6; rx++) {
				for (int rz = -6; rz <= 6; rz++) {
					ChunkPos pos = placement.getPotentialStructureChunk(seed, rx * 20, rz * 20);
					Optional<Structure.GenerationStub> vanilla = vanillaCity(seed, state, pos);
					List<EndCityPieces.Piece> core = world.piecesAt(pos.x(), pos.z());
					assertEquals(vanilla.isPresent(), core != null, "city presence at " + pos + " seed " + seed);
					if (core == null) {
						continue;
					}
					cities++;
					assertEquals(vanilla.get().position().getY(), core.get(0).y, "city y at " + pos);
					List<StructurePiece> pieces = vanilla.get().getPiecesBuilder().build().pieces();
					assertEquals(((TemplateStructurePiece) pieces.get(0)).getRotation().ordinal(), core.get(0).rotation.ordinal(), "rotation at " + pos);
					assertEquals(pieces.size(), core.size(), "piece count at " + pos + " seed " + seed);
					for (int i = 0; i < pieces.size(); i++) {
						BoundingBox b = pieces.get(i).getBoundingBox();
						EndCityPieces.Piece p = core.get(i);
						String where = "piece " + i + " " + p.template + " at " + pos + " seed " + seed;
						assertEquals(b.minX(), p.minX, where);
						assertEquals(b.minY(), p.minY, where);
						assertEquals(b.minZ(), p.minZ, where);
						assertEquals(b.maxX(), p.maxX, where);
						assertEquals(b.maxY(), p.maxY, where);
						assertEquals(b.maxZ(), p.maxZ, where);
					}
					if (world.cityAt(pos.x(), pos.z()).ship()) {
						ships++;
					}
				}
			}
		}
		System.out.println("verified " + cities + " cities, " + ships + " with ships");
		assertTrue(cities > 100);
	}

	@Test
	void crackerRecoversVanillaSeed() {
		long seed = seeds(1, 5)[0];
		RandomState state = randomState(seed);
		List<EndSpikeFeature.EndSpike> spikes = EndSpikeFeature.getSpikesForLevel(levelWithSeed(seed));
		int[] observed = new int[EndSpikes.COUNT];
		for (int i = 0; i < observed.length; i++) {
			observed[i] = EndSpikes.sizeOfHeight(spikes.get(i).getHeight());
		}
		int[] keys = EndSpikes.matchingKeys(observed);
		List<int[]> found = new ArrayList<>();
		for (int rx = -4; rx <= 4; rx++) {
			for (int rz = -4; rz <= 4; rz++) {
				ChunkPos pos = placement.getPotentialStructureChunk(seed, rx * 20, rz * 20);
				Optional<Structure.GenerationStub> city = vanillaCity(seed, state, pos);
				if (city.isPresent()) {
					int rotation = ((TemplateStructurePiece) city.get().getPiecesBuilder().build().pieces().get(0)).getRotation().ordinal();
					found.add(new int[] {pos.x(), pos.z(), rotation});
				}
			}
		}
		found.sort(Comparator.comparingInt(c -> c[0] * c[0] + c[1] * c[1]));
		List<SeedCracker.City> observedCities = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			int[] c = found.get(i);
			observedCities.add(new SeedCracker.City(List.of(new SeedCracker.Candidate(c[0], c[1], c[2]))));
		}
		SeedCracker cracker = new SeedCracker(Placement.END_CITIES, keys, observedCities, 10000);
		SeedCracker.Result result = cracker.run(Runtime.getRuntime().availableProcessors());
		List<Long> survivors = new CandidateFilter(Placement.END_CITIES, observedCities, List.of(), List.of()).filter(result.seeds());
		assertNotNull(survivors);
		assertEquals(List.of(seed & LegacyRandom.MASK), survivors);
	}
}
