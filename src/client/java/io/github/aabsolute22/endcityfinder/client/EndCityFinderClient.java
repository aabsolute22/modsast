package io.github.aabsolute22.endcityfinder.client;

import java.nio.file.Path;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

import io.github.aabsolute22.endcityfinder.core.EndCity;

public class EndCityFinderClient implements ClientModInitializer {
	public static final String MOD_ID = "endcityfinder";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	private static Settings settings;
	private static FinderSession session;
	private static final WorldScanner SCANNER = new WorldScanner();
	private static FinderSession.Phase lastPhase;
	private static KeyMapping mapKey;
	private static KeyMapping markersKey;
	private static KeyMapping addKey;
	private static int ticks;
	private static int lastEvidence;

	@Override
	public void onInitializeClient() {
		settings = Settings.load();
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		mapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.endcityfinder.map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
		markersKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.endcityfinder.markers", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
		addKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.endcityfinder.add", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, category));

		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> client.execute(() -> openSession(client)));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> client.execute(EndCityFinderClient::closeSession));
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			if (level.dimension() == Level.END) {
				SCANNER.enqueue(chunk.getPos().x(), chunk.getPos().z());
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(EndCityFinderClient::tick);
		ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, type, time) -> Party.onMessage(message, sender));
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || Party.onMessage(message, null));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> Commands.register(dispatcher));
		HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR, Identifier.fromNamespaceAndPath(MOD_ID, "hud"), HudOverlay::extract);
		LevelRenderEvents.BEFORE_GIZMOS.register(WorldMarkers::render);
	}

	static boolean isMapKey(net.minecraft.client.input.KeyEvent event) {
		return mapKey.matches(event);
	}

	public static Path configDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
	}

	public static Settings settings() {
		return settings;
	}

	public static FinderSession session() {
		return session;
	}

	public static WorldScanner scanner() {
		return SCANNER;
	}

	/** One save file per server address or singleplayer world. */
	private static String sessionKey(Minecraft mc) {
		ServerData server = mc.getCurrentServer();
		if (server != null && !mc.isLocalServer()) {
			return "server-" + server.ip;
		}
		if (mc.getSingleplayerServer() != null) {
			return "world-" + mc.getSingleplayerServer().getWorldData().getLevelName();
		}
		return server != null ? "server-" + server.ip : "unknown";
	}

	private static void openSession(Minecraft mc) {
		closeSession();
		session = FinderSession.open(sessionKey(mc));
		lastPhase = null;
		LOG.info("End City Finder session {}", session.key());
	}

	private static void closeSession() {
		if (session != null) {
			session.close();
			session = null;
		}
		SCANNER.reset();
		Party.reset();
	}

	private static void tick(Minecraft mc) {
		FinderSession s = session;
		if (s == null || mc.level == null || mc.player == null) {
			return;
		}
		while (mapKey.consumeClick()) {
			mc.setScreenAndShow(new EndMapScreen());
		}
		while (markersKey.consumeClick()) {
			settings.worldMarkers = !settings.worldMarkers;
			settings.save();
			message(Component.literal("Маркеры в мире: " + (settings.worldMarkers ? "вкл" : "выкл")));
		}
		while (addKey.consumeClick()) {
			Commands.addNearPlayer(mc);
		}
		Party.tick(mc, s);
		if (mc.level.dimension() != Level.END) {
			return;
		}
		double x = mc.player.getX();
		double z = mc.player.getZ();
		SCANNER.tick(mc.level, s, x, z);
		s.updatePrediction(x, z, settings);
		if (++ticks % 10 == 0) {
			for (EndCity city : s.predicted()) {
				boolean inside = x >= city.minX() - 4 && x <= city.maxX() + 5 && z >= city.minZ() - 4 && z <= city.maxZ() + 5
						&& mc.player.getY() >= city.y() - 8;
				if ((inside || Math.hypot(city.x() - x, city.z() - z) < settings.visitDistance) && s.setVisited(city, true)) {
					Party.onVisited(s, city.key());
					message(Component.literal("Город " + city.x() + " " + city.z() + " залутан, скрываю (/ecf looted показать)").withStyle(ChatFormatting.GRAY));
				}
			}
		}
		FinderSession.Phase phase = s.phase();
		// While several seeds remain, newly explored chunks and terrain samples can rule some out.
		if (phase == FinderSession.Phase.AMBIGUOUS && ticks % 200 == 0) {
			int evidence = s.emptyChunkCount() + s.sampleCount();
			if (evidence > lastEvidence + 64) {
				lastEvidence = evidence;
				s.requestSolve();
			}
		}
		if (phase != lastPhase) {
			if (phase == FinderSession.Phase.SOLVED) {
				Party.onSolved(s);
			}
			if (lastPhase != null && (phase == FinderSession.Phase.SOLVED || phase == FinderSession.Phase.CONTRADICTION
					|| phase == FinderSession.Phase.NEED_MORE_CITIES || phase == FinderSession.Phase.AMBIGUOUS)) {
				message(Component.literal("[ECF] " + s.detail()).withStyle(phase == FinderSession.Phase.SOLVED ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
			}
			lastPhase = phase;
		}
		s.saveIfDirty();
	}

	public static void message(Component component) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(component);
		}
	}

	static void onCityDetected(FinderSession.ObservedCity city) {
		Minecraft.getInstance().execute(() -> message(Component.literal("[ECF] Город #" + city.id + ": " + city.describe()
				+ (city.exact ? "" : " (неточно, подтверди /ecf add стоя в нижнем зале)")).withStyle(ChatFormatting.AQUA)));
	}
}
