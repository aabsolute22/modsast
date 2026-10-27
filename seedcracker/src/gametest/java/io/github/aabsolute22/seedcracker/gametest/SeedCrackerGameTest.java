package io.github.aabsolute22.seedcracker.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

import io.github.aabsolute22.seedcracker.client.LocatorScreen;
import io.github.aabsolute22.seedcracker.client.MapScreen;
import io.github.aabsolute22.seedcracker.client.SeedCrackerClient;
import io.github.aabsolute22.seedcracker.client.Session;
import io.github.aabsolute22.seedcracker.client.Targets;
import io.github.aabsolute22.seedcracker.core.StructureKind;
import io.github.aabsolute22.seedcracker.world.SeedWorld;
import io.github.aabsolute22.seedcracker.world.Worldgen;

/**
 * Plays the mod for real: a dedicated server with a known seed, the client visits the nearest structure of each
 * kind, and the mod has to detect them from the blocks it receives, crack the seed, and then locate the rest.
 */
public class SeedCrackerGameTest implements FabricClientGameTest {
	private static final long SEED = 7_331_200_416L;
	private static final int MINUTE = 20 * 60;

	private record Visit(StructureKind kind, SeedWorld.Found found, SeedWorld.Dim dim) {
	}

	private static void log(String message) {
		System.out.println("[SC-GAMETEST] " + message);
	}

	private static Session session() {
		return SeedCrackerClient.session();
	}

	private static boolean detected(Visit v) {
		Session s = session();
		if (s == null) {
			return false;
		}
		for (Session.Obs o : s.observations()) {
			if (!o.kind.equals(v.kind.id())) {
				continue;
			}
			for (int[] c : o.candidates) {
				if (c[0] == v.found.chunkX() && c[1] == v.found.chunkZ()) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean waitQuietly(ClientGameTestContext context, Predicate<Minecraft> condition, int ticks) {
		try {
			context.waitFor(condition, ticks);
			return true;
		} catch (AssertionError e) {
			return false;
		}
	}

	private static void typeCommand(ClientGameTestContext context, String command) {
		context.getInput().pressKey(options -> options.keyChat);
		context.getInput().typeChars(command);
		context.getInput().holdKeyFor(InputConstants.KEY_RETURN, 0);
		context.waitTicks(2);
	}

	private static String dimName(SeedWorld.Dim dim) {
		return dim == SeedWorld.Dim.NETHER ? "minecraft:the_nether" : "minecraft:overworld";
	}

	private static void teleport(TestDedicatedServerContext server, SeedWorld.Dim dim, double x, double y, double z) {
		server.runCommand(String.format("execute in %s run tp Player0 %.1f %.1f %.1f", dimName(dim), x, y, z));
	}

	private static void teleportFacing(ClientGameTestContext context, TestDedicatedServerContext server, TestDedicatedServerConnection connection,
			SeedWorld.Dim dim, double x, double y, double z, double fx, double fy, double fz) {
		teleport(server, dim, x, y, z);
		// The glass can only go into a loaded chunk: wait until the client is there and has the chunks.
		context.waitFor(mc -> mc.player != null && Math.abs(mc.player.getX() - x) < 2 && Math.abs(mc.player.getZ() - z) < 2, MINUTE);
		connection.waitForChunksDownload(4 * MINUTE);
		server.runCommand(String.format("execute in %s run setblock %d %d %d minecraft:glass", dimName(dim), (int) Math.floor(x), (int) Math.floor(y) - 1,
				(int) Math.floor(z)));
		server.runCommand(String.format("execute in %s run tp Player0 %.1f %.1f %.1f facing %.1f %.1f %.1f", dimName(dim), x, y, z, fx, fy, fz));
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		Worldgen wg = Worldgen.get(SeedCrackerClient.tempDir()).join();
		SeedWorld over = new SeedWorld(wg, SEED, SeedWorld.Dim.OVERWORLD);
		SeedWorld nether = new SeedWorld(wg, SEED, SeedWorld.Dim.NETHER);
		List<Visit> visits = new ArrayList<>();
		for (StructureKind k : List.of(StructureKind.DESERT_PYRAMID, StructureKind.JUNGLE_TEMPLE, StructureKind.SWAMP_HUT, StructureKind.IGLOO,
				StructureKind.VILLAGE, StructureKind.SHIPWRECK, StructureKind.RUINED_PORTAL, StructureKind.OCEAN_MONUMENT, StructureKind.TRAIL_RUINS,
				StructureKind.ANCIENT_CITY, StructureKind.TRIAL_CHAMBERS, StructureKind.OCEAN_RUIN)) {
			for (SeedWorld.Found f : over.near(k, 0, 0, 1800)) {
				if (k != StructureKind.OCEAN_RUIN || bigRuin(over, f)) {
					visits.add(new Visit(k, f, SeedWorld.Dim.OVERWORLD));
					break;
				}
			}
		}
		List<SeedWorld.Found> treasures = over.near(StructureKind.BURIED_TREASURE, 0, 0, 900);
		if (!treasures.isEmpty()) {
			visits.add(new Visit(StructureKind.BURIED_TREASURE, treasures.get(0), SeedWorld.Dim.OVERWORLD));
		}
		for (StructureKind k : List.of(StructureKind.FORTRESS, StructureKind.RUINED_PORTAL_NETHER)) {
			List<SeedWorld.Found> found = nether.near(k, 0, 0, 900);
			if (!found.isEmpty()) {
				visits.add(new Visit(k, found.get(0), SeedWorld.Dim.NETHER));
			}
		}
		for (SeedWorld.Found b : nether.near(StructureKind.BASTION, 0, 0, 1500)) {
			if ("bridge".equals(nether.details(b).variant())) {
				visits.add(new Visit(StructureKind.BASTION, b, SeedWorld.Dim.NETHER));
				break;
			}
		}
		for (Visit v : visits) {
			log("ground truth " + v.kind.id() + " at chunk " + v.found.chunkX() + " " + v.found.chunkZ() + " block " + v.found.x() + " " + v.found.y() + " "
					+ v.found.z() + " " + v.found.variant());
		}

		Properties properties = new Properties();
		properties.setProperty("view-distance", "8");
		properties.setProperty("simulation-distance", "4");
		// Consistent settings would make a superflat overworld with nothing but villages.
		try (TestDedicatedServerContext server = context.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(creator -> {
					creator.setSeed(Long.toString(SEED));
					creator.setGenerateStructures(true);
				})
				.createServer(properties);
				TestDedicatedServerConnection connection = server.connect()) {
			server.runCommand("gamemode creative Player0");
			server.runCommand("gamerule doDaylightCycle false");
			server.runCommand("time set noon");
			server.runCommand("weather clear 1000000");
			context.waitFor(mc -> session() != null, MINUTE);
			context.waitFor(mc -> Worldgen.now() != null, 2 * MINUTE);
			log("hashed seed received: " + session().hashedSeed());

			int hits = 0;
			List<String> missed = new ArrayList<>();
			Session.Phase solvedAt = null;
			for (Visit v : visits) {
				double y = v.dim == SeedWorld.Dim.NETHER ? Math.max(v.found.y() + 12, 40) : Math.max(v.found.y(), 64) + 30;
				teleport(server, v.dim, v.found.x() + 0.5, y, v.found.z() + 0.5);
				connection.waitForChunksDownload(4 * MINUTE);
				boolean ok = waitQuietly(context, mc -> detected(v), 40 * 20);
				log((ok ? "DETECTED " : "MISSED   ") + v.kind.id() + " " + v.found.variant() + " at chunk " + v.found.chunkX() + " " + v.found.chunkZ()
						+ " · phase " + session().phase() + " (" + session().detail() + ")");
				if (ok) {
					hits++;
				} else {
					missed.add(v.kind.id());
					diagnose(context, over, nether, v);
				}
				if (solvedAt == null && session().phase() == Session.Phase.SOLVED) {
					solvedAt = session().phase();
					log("solved after " + hits + " detections");
				}
			}
			log("detected " + hits + "/" + visits.size() + ", missed " + missed);
			for (String line : context.computeOnClient(mc -> {
				List<String> out = new ArrayList<>();
				for (Session.Obs o : session().observations()) {
					out.add("  observation #" + o.id + " " + o.describe() + " at " + o.x + " " + o.y + " " + o.z + " note " + o.note);
				}
				return out;
			})) {
				log(line);
			}
			List<String[]> exact = context.computeOnClient(mc -> {
				List<String[]> out = new ArrayList<>();
				for (Session.Obs o : session().observations()) {
					if (o.candidates.size() == 1) {
						out.add(new String[] {o.kind, o.dim, Integer.toString(o.candidates.get(0)[0]), Integer.toString(o.candidates.get(0)[1])});
					}
				}
				return out;
			});
			int falsePositives = 0;
			for (String[] o : exact) {
				StructureKind k = StructureKind.byId(o[0]);
				SeedWorld w = "nether".equals(o[1]) ? nether : over;
				var stub = w.stubAt(w.set(k.spread), Integer.parseInt(o[2]), Integer.parseInt(o[3]));
				boolean real = stub.isPresent() && stub.get().structure().unwrapKey().map(key -> k.structures.contains(key.identifier().getPath())).orElse(false);
				if (!real) {
					falsePositives++;
					log("FALSE POSITIVE " + String.join(" ", o));
				}
			}
			check(falsePositives == 0, "every exact detection is a real structure (" + exact.size() + ")");
			boolean solved = waitQuietly(context, mc -> session().phase() == Session.Phase.SOLVED, 6 * MINUTE);
			log("final phase " + session().phase() + " · " + session().detail() + " · biome samples " + session().biomeSampleCount());
			check(solved && session().seed() != null && session().seed() == SEED, "cracked the real seed " + SEED);

			// Locating matches the ground truth.
			double px = context.computeOnClient(mc -> mc.player.getX());
			double pz = context.computeOnClient(mc -> mc.player.getZ());
			teleport(server, SeedWorld.Dim.OVERWORLD, 0.5, 120, 0.5);
			connection.waitForChunksDownload(4 * MINUTE);
			context.waitFor(mc -> SeedCrackerClient.locator().structures(SeedWorld.Dim.OVERWORLD, StructureKind.VILLAGE, mc.player.getX(), mc.player.getZ(),
					SeedCrackerClient.settings().radius) != null, MINUTE);
			List<SeedWorld.Found> located = context.computeOnClient(mc -> SeedCrackerClient.locator().structures(SeedWorld.Dim.OVERWORLD,
					StructureKind.VILLAGE, mc.player.getX(), mc.player.getZ(), SeedCrackerClient.settings().radius));
			List<SeedWorld.Found> truth = over.near(StructureKind.VILLAGE, 0.5, 0.5, SeedCrackerClient.settings().radius);
			check(located.size() == truth.size(), "located " + located.size() + " villages, truth " + truth.size());
			log("previous player position " + px + " " + pz);

			// Visuals: a target village with the line, tracked markers, the search screen and the map.
			SeedWorld.Found village = truth.stream().filter(f -> f.distanceTo(0, 0) > 150).findFirst().orElse(truth.get(0));
			context.runOnClient(mc -> {
				session().setTarget(Targets.of(village, SeedWorld.Dim.OVERWORLD));
				SeedCrackerClient.settings().tracked.add(StructureKind.VILLAGE.id());
				SeedCrackerClient.settings().tracked.add(StructureKind.RUINED_PORTAL.id());
			});
			double dx = village.x() - 0.5;
			double dz = village.z() - 0.5;
			double len = Math.hypot(dx, dz);
			double sx = village.x() - dx / len * 90;
			double sz = village.z() - dz / len * 90;
			teleportFacing(context, server, connection, SeedWorld.Dim.OVERWORLD, sx, village.y() + 25, sz, village.x(), village.y(), village.z());
			connection.waitForChunksRender(4 * MINUTE);
			context.waitTicks(60);
			context.takeScreenshot("sc_line_near");
			// From the side the arc shows its shape: look past it, half way along and off to the left.
			double mx = (sx + village.x()) / 2 - dz / len * 45;
			double mz = (sz + village.z()) / 2 + dx / len * 45;
			teleportFacing(context, server, connection, SeedWorld.Dim.OVERWORLD, sx, village.y() + 25, sz, mx, village.y() + 12, mz);
			connection.waitForChunksRender(4 * MINUTE);
			context.waitTicks(40);
			context.takeScreenshot("sc_line_side");
			// Third person shows the whole arc leaving the player.
			teleportFacing(context, server, connection, SeedWorld.Dim.OVERWORLD, sx, village.y() + 25, sz, village.x(), village.y() + 15, village.z());
			context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			context.waitTicks(40);
			context.takeScreenshot("sc_line_third");
			context.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
			teleportFacing(context, server, connection, SeedWorld.Dim.OVERWORLD, 0.5, 140, 0.5, village.x(), village.y() + 30, village.z());
			connection.waitForChunksRender(4 * MINUTE);
			context.waitTicks(60);
			context.takeScreenshot("sc_line_far");
			context.setScreen(LocatorScreen::new);
			context.waitTicks(40);
			context.takeScreenshot("sc_locator");
			context.setScreen(MapScreen::new);
			context.waitTicks(200);
			context.takeScreenshot("sc_map");
			context.setScreen(() -> null);
			typeCommand(context, "/sc find village 6");
			context.waitTicks(20);
			context.takeScreenshot("sc_find");
			typeCommand(context, "/sc status");
			context.waitTicks(20);
			context.takeScreenshot("sc_status");
			log("done");
		}
	}

	/** Why a structure was missed: the matcher's score at the true placement and what the client sees there. */
	private static void diagnose(ClientGameTestContext context, SeedWorld over, SeedWorld nether, Visit v) {
		SeedWorld w = v.dim == SeedWorld.Dim.NETHER ? nether : over;
		var start = w.startAt(w.set(v.kind.spread), v.found.chunkX(), v.found.chunkZ());
		if (start.isEmpty()) {
			log("  diag: no start");
			return;
		}
		var pieces = start.get().getPieces();
		log("  diag: " + pieces.size() + " pieces, first " + pieces.get(0).getClass().getSimpleName() + " box " + pieces.get(0).getBoundingBox()
				+ " rot " + pieces.get(0).getRotation() + " orientation " + pieces.get(0).getOrientation());
		String report = context.computeOnClient(mc -> {
			StringBuilder b = new StringBuilder();
			var matcher = SeedCrackerClient.scanner().matcher();
			b.append("scanned ").append(SeedCrackerClient.scanner().scannedChunks()).append(" pending hits ").append(SeedCrackerClient.scanner().pendingHits());
			b.append(" minY ").append(mc.level.getMinY()).append(" chunk minY ").append(mc.level.getChunk(v.found.chunkX(), v.found.chunkZ()).getMinY());
			b.append(" sections ").append(mc.level.getChunk(v.found.chunkX(), v.found.chunkZ()).getSections().length);
			var p0 = pieces.get(0);
			BlockPos center = p0.getBoundingBox().getCenter();
			b.append(" block at piece centre ").append(mc.level.getBlockState(center).getBlock());
			// Raw blocks around the piece centre, to see what the client really has there.
			for (int dy = -2; dy <= 2; dy++) {
				b.append(dy == -2 ? " column " : ",").append(SeedCrackerClient.scanner().canon(mc.level.getBlockState(center.offset(0, dy, 0)).getBlock()));
			}
			if (matcher != null && p0 instanceof net.minecraft.world.level.levelgen.structure.TemplateStructurePiece t) {
				for (var spec : matcher.templates().specs()) {
					if (spec.kind() == v.kind && w == w) {
						for (var variant : spec.variants()) {
							if (variant.rotation() == t.placeSettings().getRotation() && variant.mirror() == t.placeSettings().getMirror()) {
								int[] m = io.github.aabsolute22.seedcracker.world.TemplateMatcher.count(variant, v.found.chunkX() * 16, t.templatePosition().getY(),
										v.found.chunkZ() * 16, (x, y, z) -> SeedCrackerClient.scanner().canon(mc.level.getBlockState(new BlockPos(x, y, z)).getBlock()));
								if (m[0] * 2 > m[1]) {
									b.append(" | ").append(spec.template()).append(' ').append(m[0]).append('/').append(m[1]);
								}
							}
						}
					}
				}
			}
			if (matcher != null && p0 instanceof net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece pe
					&& pe.getElement() instanceof net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement single) {
				String path = single.getTemplateLocation().getPath();
				io.github.aabsolute22.seedcracker.world.TemplateMatcher.BlockSource src = (x, y, z) -> mc.level.hasChunk(x >> 4, z >> 4)
						? SeedCrackerClient.scanner().canon(mc.level.getBlockState(new BlockPos(x, y, z)).getBlock()) : null;
				b.append(" | start ").append(path).append(" at ").append(pe.getPosition().toShortString()).append(" rot ").append(pe.getRotation());
				for (var spec : matcher.templates().specs()) {
					if (!spec.template().equals(path) || spec.kind() != v.kind) {
						continue;
					}
					b.append(" anchor ").append(spec.anchor()).append(" threshold ").append(spec.threshold()).append(" signature ").append(spec.signature().size());
					for (var variant : spec.variants()) {
						int[] m = io.github.aabsolute22.seedcracker.world.TemplateMatcher.count(variant, v.found.chunkX() * 16, pe.getPosition().getY(),
								v.found.chunkZ() * 16, src);
						b.append(' ').append(variant.rotation()).append('=').append(m[0]).append('/').append(m[1]);
					}
					// Every anchor block of the start as the client has it, matched without any biome filter.
					var box = p0.getBoundingBox();
					int anchors = 0;
					int matching = 0;
					java.util.Set<String> around = new java.util.TreeSet<>();
					for (BlockPos q : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
						if (!spec.anchor().equals(src.canonAt(q.getX(), q.getY(), q.getZ()))) {
							continue;
						}
						anchors++;
						around.add(SeedWorld.biomeId(mc.level.getNoiseBiome(q.getX() >> 2, q.getY() >> 2, q.getZ() >> 2)));
						for (var m : matcher.fromAnchor(q.getX(), q.getY(), q.getZ(), spec.anchor(), src)) {
							if (m.kind() == v.kind && m.chunkX() == v.found.chunkX() && m.chunkZ() == v.found.chunkZ()) {
								matching++;
								break;
							}
						}
					}
					b.append(" | anchors ").append(anchors).append(" matching ").append(matching).append(" biomes there ").append(around)
							.append(" allowed ").append(around.stream().filter(spec.biomes()::contains).toList());
				}
			}
			return b.toString();
		});
		log("  diag: " + report);
	}

	/** Small ocean ruins are a handful of blocks; only big ones are recognised. */
	private static boolean bigRuin(SeedWorld w, SeedWorld.Found f) {
		var start = w.startAt(w.set(StructureKind.OCEAN_RUIN.spread), f.chunkX(), f.chunkZ());
		if (start.isEmpty()) {
			return false;
		}
		var box = start.get().getPieces().get(0).getBoundingBox();
		return Math.max(box.getXSpan(), box.getZSpan()) >= 10;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
		log("ok: " + message);
	}
}
