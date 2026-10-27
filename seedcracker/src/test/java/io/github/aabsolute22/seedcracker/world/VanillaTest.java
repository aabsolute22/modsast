package io.github.aabsolute22.seedcracker.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;

import io.github.aabsolute22.seedcracker.core.LegacyRandom;
import io.github.aabsolute22.seedcracker.core.Observation;
import io.github.aabsolute22.seedcracker.core.Packed;
import io.github.aabsolute22.seedcracker.core.SeedHash;
import io.github.aabsolute22.seedcracker.core.StartRules;
import io.github.aabsolute22.seedcracker.core.Spread;
import io.github.aabsolute22.seedcracker.core.StructureKind;
import io.github.aabsolute22.seedcracker.core.StructureSeedCracker;

/** Compares the cracker's model of world generation with the real Minecraft 26.2 code. */
class VanillaTest {
	private static Worldgen worldgen;
	private static final List<Direction> HORIZONTAL = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

	@BeforeAll
	static void bootstrap() throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		long t0 = System.nanoTime();
		worldgen = Worldgen.get(Files.createTempDirectory("sc-test")).join();
		System.out.printf("worldgen loaded in %d ms%n", (System.nanoTime() - t0) / 1_000_000);
	}

	private static long[] seeds(int count, long salt) {
		Random random = new Random(salt);
		long[] out = new long[count];
		for (int i = 0; i < count; i++) {
			out[i] = random.nextLong();
		}
		return out;
	}

	@Test
	void hashMatchesVanilla() {
		for (long seed : seeds(200, 1)) {
			assertEquals(BiomeManager.obfuscateSeed(seed), SeedHash.obfuscate(seed));
		}
	}

	@Test
	void spreadsMatchVanilla() {
		for (long seed : seeds(4, 2)) {
			SeedWorld over = new SeedWorld(worldgen, seed, SeedWorld.Dim.OVERWORLD);
			SeedWorld nether = new SeedWorld(worldgen, seed, SeedWorld.Dim.NETHER);
			for (Spread spread : Spread.ALL) {
				SeedWorld w = over.hasSet(spread) ? over : nether;
				assertTrue(w.hasSet(spread), "vanilla has no set " + spread.id());
				Holder<StructureSet> set = w.set(spread);
				RandomSpreadStructurePlacement p = (RandomSpreadStructurePlacement) set.value().placement();
				assertEquals(spread.spacing(), p.spacing(), spread.id());
				assertEquals(spread.separation(), p.separation(), spread.id());
				assertEquals(spread.triangular(), p.spreadType() == RandomSpreadType.TRIANGULAR, spread.id());
				Random r = new Random(seed);
				for (int i = 0; i < 300; i++) {
					int cx = r.nextInt(4000) - 2000;
					int cz = r.nextInt(4000) - 2000;
					ChunkPos v = p.getPotentialStructureChunk(seed, cx, cz);
					long mine = spread.potentialChunk(seed, spread.region(cx), spread.region(cz));
					assertEquals(v.x(), Packed.x(mine), spread.id());
					assertEquals(v.z(), Packed.z(mine), spread.id());
					// Placement chunk + frequency + exclusion zone, as vanilla decides it.
					int tx = v.x();
					int tz = v.z();
					if (spread.spacing() == 1) {
						tx = cx;
						tz = cz;
					}
					boolean vanilla = p.isStructureChunk(stateOf(w), tx, tz);
					boolean core = spread.isStructureChunk(seed, tx, tz)
							&& !(spread == Spread.PILLAGER_OUTPOSTS && Spread.outpostExcluded(seed, tx, tz));
					assertEquals(vanilla, core, spread.id() + " placement at " + tx + " " + tz);
				}
			}
		}
	}

	private static net.minecraft.world.level.chunk.ChunkGeneratorStructureState stateOf(SeedWorld w) {
		return w.generator().createState(worldgen.registries().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE_SET),
				w.randomState(), w.seed());
	}

	/** Rotation (or direction for code-built temples) of the start piece against the first large-feature draw. */
	@Test
	void startRotationsMatchVanilla() {
		Map<String, int[]> stats = new LinkedHashMap<>();
		for (long seed : seeds(3, 3)) {
			for (SeedWorld.Dim dim : SeedWorld.Dim.values()) {
				SeedWorld w = new SeedWorld(worldgen, seed, dim);
				for (Spread spread : Spread.ALL) {
					if (!w.hasSet(spread) || spread.spacing() <= 2) {
						continue;
					}
					for (int rx = -3; rx <= 3; rx++) {
						for (int rz = -3; rz <= 3; rz++) {
							long p = spread.potentialChunk(seed, rx, rz);
							Optional<StructureStart> start = w.startAt(w.set(spread), Packed.x(p), Packed.z(p));
							if (start.isEmpty() || start.get().getPieces().isEmpty()) {
								continue;
							}
							StructurePiece first = start.get().getPieces().get(0);
							int actual = first instanceof TemplateStructurePiece || first instanceof PoolElementStructurePiece || first.getOrientation() == null
									? first.getRotation().ordinal() : HORIZONTAL.indexOf(first.getOrientation());
							int predicted = Spread.startRotation(seed, Packed.x(p), Packed.z(p));
							String id = w.at(spread, Packed.x(p), Packed.z(p)).map(SeedWorld.Found::structure).orElse(spread.id());
							int[] s = stats.computeIfAbsent(id, k -> new int[2]);
							s[0]++;
							if (actual == predicted) {
								s[1]++;
							}
						}
					}
				}
			}
		}
		stats.forEach((id, s) -> System.out.printf("rotation %-24s %3d/%3d%n", id, s[1], s[0]));
		for (String id : StartRules.ROTATION_FIRST) {
			int[] s = stats.get(id);
			if (s != null) {
				assertEquals(s[0], s[1], "rotation rule for " + id);
			}
		}
	}

	@Test
	void netherPickMatchesVanilla() {
		int checked = 0;
		for (long seed : seeds(3, 4)) {
			SeedWorld w = new SeedWorld(worldgen, seed, SeedWorld.Dim.NETHER);
			for (int rx = -6; rx <= 6; rx++) {
				for (int rz = -6; rz <= 6; rz++) {
					Optional<SeedWorld.Found> f = w.inRegion(Spread.NETHER_COMPLEXES, rx, rz);
					if (f.isEmpty()) {
						continue;
					}
					int pick = Spread.firstWeightedPick(seed, f.get().chunkX(), f.get().chunkZ(), Observation.NETHER_WEIGHTS);
					if (f.get().kind() == StructureKind.BASTION) {
						assertEquals(Observation.PICK_BASTION, pick);
					} else if (!basaltNear(w, f.get().chunkX(), f.get().chunkZ())) {
						assertEquals(Observation.PICK_FORTRESS, pick, "fortress outside basalt deltas");
					}
					checked++;
				}
			}
		}
		assertTrue(checked > 100);
	}

	/** Same rule as the client: a bastion could only have been rejected if basalt deltas are near its start. */
	private static boolean basaltNear(SeedWorld w, int chunkX, int chunkZ) {
		for (int dx = -40; dx <= 56; dx += 8) {
			for (int dz = -40; dz <= 56; dz += 8) {
				for (int y = 24; y <= 56; y += 16) {
					if ("basalt_deltas".equals(w.biomeIdAt(chunkX * 16 + dx, y, chunkZ * 16 + dz))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	@Test
	void climateOnlySamplerMatchesFullRouter() {
		for (long seed : seeds(3, 5)) {
			for (SeedWorld.Dim dim : SeedWorld.Dim.values()) {
				SeedWorld w = new SeedWorld(worldgen, seed, dim);
				BiomeCheck check = new BiomeCheck(worldgen, dim);
				Climate.Sampler sampler = check.sampler(seed);
				Random r = new Random(seed);
				for (int i = 0; i < 300; i++) {
					int qx = r.nextInt(4000) - 2000;
					int qz = r.nextInt(4000) - 2000;
					int qy = (dim.minY >> 2) + r.nextInt(dim.height >> 2);
					BiomeCheck.Sample s = new BiomeCheck.Sample(qx, qy, qz, "");
					assertEquals(SeedWorld.biomeId(w.biome(qx, qy, qz)), check.biome(sampler, s));
				}
			}
		}
	}

	@Test
	void slimeChunksMatchFormula() {
		for (long seed : seeds(5, 6)) {
			Random r = new Random(seed);
			for (int i = 0; i < 200; i++) {
				int x = r.nextInt(20000) - 10000;
				int z = r.nextInt(20000) - 10000;
				Random ref = new Random(seed + (long) (x * x * 4987142) + (long) (x * 5947611) + (long) (z * z) * 4392871L + (long) (z * 389711) ^ 987234911L);
				assertEquals(ref.nextInt(10) == 0, SeedWorld.isSlimeChunk(seed, x, z));
			}
		}
	}

	@Test
	void strongholdsAndLocate() {
		long seed = seeds(1, 7)[0];
		SeedWorld w = new SeedWorld(worldgen, seed, SeedWorld.Dim.OVERWORLD);
		long t0 = System.nanoTime();
		List<SeedWorld.Found> strongholds = w.strongholds();
		System.out.printf("strongholds: %d in %d ms, nearest %s%n", strongholds.size(), (System.nanoTime() - t0) / 1_000_000,
				strongholds.isEmpty() ? "-" : strongholds.get(0));
		assertEquals(128, strongholds.size());
		for (StructureKind kind : StructureKind.values()) {
			if (!kind.overworld || kind == StructureKind.STRONGHOLD) {
				continue;
			}
			t0 = System.nanoTime();
			List<SeedWorld.Found> found = w.near(kind, 0, 0, kind == StructureKind.BURIED_TREASURE || kind == StructureKind.MINESHAFT ? 600 : 3000);
			System.out.printf("locate %-18s %3d in %5d ms%s%n", kind.id(), found.size(), (System.nanoTime() - t0) / 1_000_000,
					found.isEmpty() ? "" : " nearest " + found.get(0).x() + " " + found.get(0).y() + " " + found.get(0).z() + " " + found.get(0).variant());
		}
		SeedWorld nether = new SeedWorld(worldgen, seed, SeedWorld.Dim.NETHER);
		for (StructureKind kind : List.of(StructureKind.FORTRESS, StructureKind.BASTION, StructureKind.RUINED_PORTAL_NETHER)) {
			List<SeedWorld.Found> found = nether.near(kind, 0, 0, 1500);
			System.out.printf("locate %-18s %3d%s%n", kind.id(), found.size(), found.isEmpty() ? "" : " nearest " + found.get(0));
			assertFalse(found.isEmpty(), kind.id());
		}
	}

	/**
	 * The detector's idea of where each start template sits relative to its chunk, checked against the pieces vanilla
	 * builds: template position, rotation pivot, and a full match from one anchor block on the vanilla-placed blocks.
	 */
	@Test
	void templateGeometryMatchesVanilla() {
		StartTemplates templates = new StartTemplates(worldgen);
		TemplateMatcher matcher = new TemplateMatcher(templates);
		System.out.println("start templates: " + templates.specs().size() + ", anchors " + templates.anchors());
		Map<String, int[]> stats = new LinkedHashMap<>();
		for (long seed : seeds(2, 10)) {
			for (SeedWorld.Dim dim : SeedWorld.Dim.values()) {
				SeedWorld w = new SeedWorld(worldgen, seed, dim);
				for (StructureKind kind : StructureKind.values()) {
					if (kind.overworld != dim.overworld() || kind.spread == null || !w.hasSet(kind.spread) || kind.spread.spacing() <= 2) {
						continue;
					}
					if (templates.specs().stream().noneMatch(sp -> sp.kind() == kind)) {
						continue;
					}
					for (int rx = -4; rx <= 4; rx++) {
						for (int rz = -4; rz <= 4; rz++) {
							long p = kind.spread.potentialChunk(seed, rx, rz);
							int cx = Packed.x(p);
							int cz = Packed.z(p);
							Optional<StructureStart> start = w.startAt(w.set(kind.spread), cx, cz);
							if (start.isEmpty() || start.get().getPieces().isEmpty()) {
								continue;
							}
							StructurePiece first = start.get().getPieces().get(0);
							net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template;
							net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings settings;
							BlockPos templatePos;
							if (first instanceof PoolElementStructurePiece pool
									&& pool.getElement() instanceof net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement single) {
								template = worldgen.templates().getOrCreate(single.getTemplateLocation());
								settings = new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings().setRotation(pool.getRotation());
								templatePos = pool.getPosition();
							} else if (first instanceof TemplateStructurePiece t) {
								template = t.template();
								settings = t.placeSettings();
								templatePos = t.templatePosition();
							} else {
								continue;
							}
							StartTemplates.Spec spec = null;
							for (StartTemplates.Spec sp : templates.specs()) {
								if (sp.kind() == kind && worldgen.templates().getOrCreate(net.minecraft.resources.Identifier.withDefaultNamespace(sp.template())) == template) {
									spec = sp;
								}
							}
							String key = kind.id();
							int[] st = stats.computeIfAbsent(key, k -> new int[3]);
							st[0]++;
							if (spec == null) {
								continue;
							}
							BlockPos predicted = spec.templateOffset(settings.getRotation()).offset(cx * 16, 0, cz * 16);
							assertEquals(predicted.getX(), templatePos.getX(), key + " template x " + spec.template());
							assertEquals(predicted.getZ(), templatePos.getZ(), key + " template z " + spec.template());
							assertEquals(spec.rotationPivot(), settings.getRotationPivot(), key + " pivot");
							// Place the template the vanilla way and let the matcher find it from one anchor.
							Map<Long, String> blocks = new java.util.HashMap<>();
							for (var info : StartTemplates.blocks(template)) {
								BlockPos wp = templatePos.offset(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.calculateRelativePosition(settings, info.pos()));
								String canon = Canon.of(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(info.state().getBlock()).getPath());
								blocks.put(BlockPos.asLong(wp.getX(), wp.getY(), wp.getZ()), canon);
							}
							TemplateMatcher.BlockSource source = (x, y, z) -> blocks.getOrDefault(BlockPos.asLong(x, y, z), "air");
							StartTemplates.Spec finalSpec = spec;
							BlockPos anchorPos = null;
							for (var info : StartTemplates.blocks(template)) {
								BlockPos wp = templatePos.offset(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.calculateRelativePosition(settings, info.pos()));
								if (finalSpec.anchor().equals(blocks.get(BlockPos.asLong(wp.getX(), wp.getY(), wp.getZ())))) {
									anchorPos = wp;
									break;
								}
							}
							assertTrue(anchorPos != null, key + " anchor " + spec.anchor());
							List<TemplateMatcher.Match> matches = matcher.fromAnchor(anchorPos.getX(), anchorPos.getY(), anchorPos.getZ(), spec.anchor(), source);
							boolean ok = matches.stream().anyMatch(m -> m.chunkX() == cx && m.chunkZ() == cz && m.variant().rotation() == settings.getRotation()
									&& m.variant().mirror() == settings.getMirror() && m.baseY() == templatePos.getY());
							assertTrue(ok, key + " matcher on " + spec.template() + " rot " + settings.getRotation() + " mirror " + settings.getMirror() + ": " + matches);
							st[1]++;
							if (matches.stream().allMatch(m -> m.chunkX() == cx && m.chunkZ() == cz)) {
								st[2]++;
							}
						}
					}
				}
			}
		}
		stats.forEach((k, v) -> System.out.printf("template %-22s starts %3d matched %3d unambiguous %3d%n", k, v[0], v[1], v[2]));
	}

	/** Seed → vanilla structures → observations → cracker → hashed seed → the same seed. */
	@Test
	void crackVanillaWorld() {
		long seed = seeds(1, 8)[0];
		SeedWorld w = new SeedWorld(worldgen, seed, SeedWorld.Dim.OVERWORLD);
		List<Observation> obs = new ArrayList<>();
		for (StructureKind kind : List.of(StructureKind.DESERT_PYRAMID, StructureKind.JUNGLE_TEMPLE, StructureKind.SWAMP_HUT, StructureKind.IGLOO,
				StructureKind.VILLAGE, StructureKind.SHIPWRECK, StructureKind.RUINED_PORTAL)) {
			for (SeedWorld.Found f : w.near(kind, 0, 0, 2500)) {
				if (obs.size() >= 6) {
					break;
				}
				int rotation = StartRules.ROTATION_FIRST.contains(f.structure()) ? Spread.startRotation(seed, f.chunkX(), f.chunkZ()) : -1;
				obs.add(Observation.exact(kind.spread, f.chunkX(), f.chunkZ(), rotation));
			}
		}
		System.out.println("observations: " + obs);
		StructureSeedCracker cracker = new StructureSeedCracker(obs, 4096);
		long t0 = System.nanoTime();
		StructureSeedCracker.Result result = cracker.run(Runtime.getRuntime().availableProcessors());
		System.out.printf("cracked in %d ms: %d structure seeds%n", (System.nanoTime() - t0) / 1_000_000, result.seeds().size());
		assertTrue(result.seeds().contains(seed & LegacyRandom.MASK));
		assertTrue(SeedHash.fullSeeds(seed & LegacyRandom.MASK, BiomeManager.obfuscateSeed(seed)).contains(seed));

		// Without the hash: biomes. Samples spread like the chunks a player loads.
		List<BiomeCheck.Sample> samples = new ArrayList<>();
		Random r = new Random(9);
		for (int i = 0; i < 24; i++) {
			int qx = r.nextInt(400) - 200;
			int qz = r.nextInt(400) - 200;
			int qy = 16;
			samples.add(new BiomeCheck.Sample(qx, qy, qz, SeedWorld.biomeId(w.biome(qx, qy, qz))));
		}
		BiomeCheck check = new BiomeCheck(worldgen, SeedWorld.Dim.OVERWORLD);
		t0 = System.nanoTime();
		List<Long> full = check.fullSeeds(seed & LegacyRandom.MASK, samples, Runtime.getRuntime().availableProcessors());
		System.out.printf("biome search over 65536 in %d ms: %s%n", (System.nanoTime() - t0) / 1_000_000, full);
		assertEquals(List.of(seed), full);
	}
}
