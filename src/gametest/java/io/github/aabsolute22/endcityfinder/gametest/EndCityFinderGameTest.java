package io.github.aabsolute22.endcityfinder.gametest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

import io.github.aabsolute22.endcityfinder.client.EndCityFinderClient;
import io.github.aabsolute22.endcityfinder.client.EndMapScreen;
import io.github.aabsolute22.endcityfinder.client.FinderSession;
import io.github.aabsolute22.endcityfinder.core.EndCity;
import io.github.aabsolute22.endcityfinder.core.EndWorld;

/**
 * Plays the mod for real: a dedicated server with a known seed, the client flies to the pillars and three End
 * cities, and the mod has to detect everything on its own, crack the seed and predict the remaining cities.
 */
public class EndCityFinderGameTest implements FabricClientGameTest {
	private static final long SEED = 123456789L;
	private static final int MINUTE = 20 * 60;

	private static FinderSession session() {
		return EndCityFinderClient.session();
	}

	private static void log(String message) {
		System.out.println("[ECF-GAMETEST] " + message);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
		log("ok: " + message);
	}

	/** Waits for a condition; on timeout dumps the mod state and a screenshot before failing. */
	private static void await(ClientGameTestContext context, String what, java.util.function.Predicate<net.minecraft.client.Minecraft> condition, int timeout) {
		try {
			context.waitFor(condition, timeout);
		} catch (AssertionError e) {
			FinderSession s = session();
			if (s != null) {
				log("TIMEOUT waiting for " + what + "; phase " + s.phase() + " (" + s.detail() + "), spikes " + s.knownSpikes()
						+ ", empty chunks " + s.emptyChunkCount() + ", samples " + s.sampleCount() + ", candidates " + s.candidateCount());
				for (FinderSession.ObservedCity c : s.cities()) {
					log("  city #" + c.id + " exact=" + c.exact + " auto=" + c.auto + " " + c.describe());
				}
			}
			context.takeScreenshot("ecf_timeout");
			throw e;
		}
	}

	private static void teleport(TestDedicatedServerContext server, double x, double y, double z) {
		server.runCommand(String.format("execute in minecraft:the_end run tp Player0 %.1f %.1f %.1f", x, y, z));
	}

	private static void teleportFacing(TestDedicatedServerContext server, double x, double y, double z, double fx, double fy, double fz) {
		server.runCommand(String.format("execute in minecraft:the_end run tp Player0 %.1f %.1f %.1f facing %.1f %.1f %.1f", x, y, z, fx, fy, fz));
	}

	private static boolean hasExactCity(EndCity city) {
		FinderSession s = session();
		if (s == null) {
			return false;
		}
		for (FinderSession.ObservedCity c : s.cities()) {
			if (c.exact && c.candidates.size() == 1 && c.candidates.get(0)[0] == city.chunkX() && c.candidates.get(0)[1] == city.chunkZ()
					&& c.candidates.get(0)[2] == city.rotation().ordinal()) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		EndWorld truth = new EndWorld(SEED);
		List<EndCity> cities = new ArrayList<>(truth.citiesNear(0, 0, 2500));
		cities.sort(Comparator.comparingDouble(c -> Math.hypot(c.x(), c.z())));
		log("ground truth: " + cities.size() + " cities within 2500, nearest " + cities.get(0));

		Properties properties = new Properties();
		properties.setProperty("view-distance", "10");
		properties.setProperty("simulation-distance", "6");
		try (TestDedicatedServerContext server = context.worldBuilder()
				.adjustSettings(creator -> {
					creator.setSeed(Long.toString(SEED));
					// Consistent test settings turn structures off; End cities are the whole point here.
					creator.setGenerateStructures(true);
				})
				.createServer(properties);
				TestDedicatedServerConnection connection = server.connect()) {
			server.runCommand("gamemode creative Player0");
			context.waitFor(mc -> session() != null, MINUTE);

			// 1. Pillars: fly to the main island and let the mod read them.
			teleportFacing(server, 0, 130, 60, 0, 80, 0);
			connection.waitForChunksDownload(MINUTE);
			await(context, "pillars", mc -> session().knownSpikes() == 10, 2 * MINUTE);
			check(session().knownSpikes() == 10, "all 10 pillars read");
			context.takeScreenshot("ecf_pillars");

			// 2. Three cities: stand above each one and wait for automatic detection.
			for (int i = 0; i < 3; i++) {
				EndCity city = cities.get(i);
				teleport(server, city.x() + 0.5, city.y() + 12, city.z() + 0.5);
				connection.waitForChunksDownload(MINUTE);
				await(context, "city " + i, mc -> hasExactCity(city), 3 * MINUTE);
				check(hasExactCity(city), "city " + i + " detected exactly at chunk " + city.chunkX() + " " + city.chunkZ() + " " + city.rotation());
			}

			// 3. The solver must land on the real seed.
			await(context, "solve", mc -> session().phase() == FinderSession.Phase.SOLVED, 15 * MINUTE);
			check(session().phase() == FinderSession.Phase.SOLVED, "solved: " + session().detail());
			check(session().worlds().size() == 1 && session().worlds().get(0).seed() == (SEED & ((1L << 48) - 1)), "found the real structure seed");

			// 4. Predictions match the ground truth around the player.
			await(context, "predictions", mc -> !session().predicted().isEmpty(), 2 * MINUTE);
			double px = context.computeOnClient(mc -> mc.player.getX());
			double pz = context.computeOnClient(mc -> mc.player.getZ());
			Set<Long> predicted = new HashSet<>();
			for (EndCity c : session().predicted()) {
				predicted.add(c.key());
			}
			int matched = 0;
			for (EndCity c : truth.citiesNear((int) px, (int) pz, 2000)) {
				check(predicted.contains(c.key()), "predicted city at " + c.x() + " " + c.z());
				matched++;
			}
			log("matched " + matched + " cities");

			// 5. Visuals: a nearby city with outline, beam and label, plus the HUD arrow.
			EndCity next = cities.get(3);
			teleportFacing(server, next.x() + 70, next.maxY() + 25, next.z() + 70, next.x(), next.y() + 20, next.z());
			connection.waitForChunksRender(MINUTE);
			context.waitTicks(40);
			context.takeScreenshot("ecf_near_city");

			// Far markers: look across the End from high up between cities.
			teleportFacing(server, cities.get(0).x(), 160, cities.get(0).z(), cities.get(5).x(), 80, cities.get(5).z());
			connection.waitForChunksRender(MINUTE);
			context.waitTicks(40);
			context.takeScreenshot("ecf_far_markers");

			// 6. The map screen.
			context.setScreen(EndMapScreen::new);
			context.waitTicks(30);
			context.takeScreenshot("ecf_map");
			context.setScreen(() -> null);

			// 7. Client command through the chat, like a player would type it.
			context.getInput().pressKey(options -> options.keyChat);
			context.getInput().typeChars("/ecf list 8");
			context.getInput().holdKeyFor(InputConstants.KEY_RETURN, 0);
			context.waitTicks(20);
			context.takeScreenshot("ecf_list");
			context.getInput().pressKey(options -> options.keyChat);
			context.getInput().typeChars("/ecf status");
			context.getInput().holdKeyFor(InputConstants.KEY_RETURN, 0);
			context.waitTicks(20);
			context.takeScreenshot("ecf_status");
			log("done");
		}
	}
}
