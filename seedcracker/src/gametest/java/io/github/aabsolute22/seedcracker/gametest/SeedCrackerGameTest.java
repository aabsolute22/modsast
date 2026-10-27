package io.github.aabsolute22.seedcracker.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;

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

	private static void teleportFacing(TestDedicatedServerContext server, SeedWorld.Dim dim, double x, double y, double z, double fx, double fy, double fz) {
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
			List<SeedWorld.Found> found = over.near(k, 0, 0, 1800);
			if (!found.isEmpty()) {
				visits.add(new Visit(k, found.get(0), SeedWorld.Dim.OVERWORLD));
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
		try (TestDedicatedServerContext server = context.worldBuilder()
				.adjustSettings(creator -> {
					creator.setSeed(Long.toString(SEED));
					creator.setGenerateStructures(true);
				})
				.createServer(properties);
				TestDedicatedServerConnection connection = server.connect()) {
			server.runCommand("gamemode creative Player0");
			server.runCommand("gamerule doDaylightCycle false");
			server.runCommand("time set noon");
			context.waitFor(mc -> session() != null, MINUTE);
			context.waitFor(mc -> Worldgen.now() != null, 2 * MINUTE);
			log("hashed seed received: " + session().hashedSeed());

			int hits = 0;
			List<String> missed = new ArrayList<>();
			Session.Phase solvedAt = null;
			for (Visit v : visits) {
				double y = v.dim == SeedWorld.Dim.NETHER ? Math.max(v.found.y() + 12, 40) : Math.max(v.found.y(), 64) + 30;
				teleport(server, v.dim, v.found.x() + 0.5, y, v.found.z() + 0.5);
				connection.waitForChunksDownload(MINUTE);
				boolean ok = waitQuietly(context, mc -> detected(v), 40 * 20);
				log((ok ? "DETECTED " : "MISSED   ") + v.kind.id() + " " + v.found.variant() + " at chunk " + v.found.chunkX() + " " + v.found.chunkZ()
						+ " · phase " + session().phase() + " (" + session().detail() + ")");
				if (ok) {
					hits++;
				} else {
					missed.add(v.kind.id());
				}
				if (solvedAt == null && session().phase() == Session.Phase.SOLVED) {
					solvedAt = session().phase();
					log("solved after " + hits + " detections");
				}
			}
			log("detected " + hits + "/" + visits.size() + ", missed " + missed);
			for (Session.Obs o : session().observations()) {
				log("  observation #" + o.id + " " + o.describe() + " note " + o.note);
			}
			boolean solved = waitQuietly(context, mc -> session().phase() == Session.Phase.SOLVED, 6 * MINUTE);
			log("final phase " + session().phase() + " · " + session().detail() + " · biome samples " + session().biomeSampleCount());
			check(solved && session().seed() != null && session().seed() == SEED, "cracked the real seed " + SEED);

			// Locating matches the ground truth.
			double px = context.computeOnClient(mc -> mc.player.getX());
			double pz = context.computeOnClient(mc -> mc.player.getZ());
			teleport(server, SeedWorld.Dim.OVERWORLD, 0.5, 120, 0.5);
			connection.waitForChunksDownload(MINUTE);
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
			teleportFacing(server, SeedWorld.Dim.OVERWORLD, sx, village.y() + 25, sz, village.x(), village.y(), village.z());
			connection.waitForChunksRender(MINUTE);
			context.waitTicks(60);
			context.takeScreenshot("sc_line_near");
			teleportFacing(server, SeedWorld.Dim.OVERWORLD, 0.5, 140, 0.5, village.x(), village.y() + 30, village.z());
			connection.waitForChunksRender(MINUTE);
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

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
		log("ok: " + message);
	}
}
