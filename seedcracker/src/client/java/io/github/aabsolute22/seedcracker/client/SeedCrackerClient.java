package io.github.aabsolute22.seedcracker.client;

import java.lang.reflect.Field;
import java.nio.file.Path;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.BiomeManager;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

import io.github.aabsolute22.seedcracker.core.StructureKind;
import io.github.aabsolute22.seedcracker.world.SeedWorld;
import io.github.aabsolute22.seedcracker.world.Worldgen;

public class SeedCrackerClient implements ClientModInitializer {
	public static final String MOD_ID = "seedcracker";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	private static Settings settings;
	private static Session session;
	private static Solver solver;
	private static final Scanner SCANNER = new Scanner();
	private static final Locator LOCATOR = new Locator();
	private static KeyMapping locatorKey;
	private static KeyMapping mapKey;
	private static KeyMapping linesKey;
	private static Field zoomSeedField;
	private static Session.Phase lastPhase;
	private static int ticks;
	private static int lastSampleCount;

	@Override
	public void onInitializeClient() {
		settings = Settings.load();
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		locatorKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.seedcracker.locator", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, category));
		mapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.seedcracker.map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, category));
		linesKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.seedcracker.lines", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, category));

		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> client.execute(() -> openSession(client)));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> client.execute(SeedCrackerClient::closeSession));
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> SCANNER.enqueue(chunk.getPos().x(), chunk.getPos().z()));
		ClientTickEvents.END_CLIENT_TICK.register(SeedCrackerClient::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> Commands.register(dispatcher));
		HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR, Identifier.fromNamespaceAndPath(MOD_ID, "hud"), HudOverlay::extract);
		LevelRenderEvents.BEFORE_GIZMOS.register(WorldRender::render);
	}

	public static Path configDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
	}

	public static Path tempDir() {
		return configDir().resolve("cache");
	}

	public static Settings settings() {
		return settings;
	}

	public static Session session() {
		return session;
	}

	public static Solver solver() {
		return solver;
	}

	public static Scanner scanner() {
		return SCANNER;
	}

	public static Locator locator() {
		return LOCATOR;
	}

	static boolean isLocatorKey(net.minecraft.client.input.KeyEvent event) {
		return locatorKey.matches(event);
	}

	static boolean isMapKey(net.minecraft.client.input.KeyEvent event) {
		return mapKey.matches(event);
	}

	/** One save file per server address or singleplayer world. */
	private static String sessionKey(Minecraft mc) {
		ServerData server = mc.getCurrentServer();
		if (mc.getSingleplayerServer() != null) {
			return "world-" + mc.getSingleplayerServer().getWorldData().getLevelName();
		}
		return server != null ? "server-" + server.ip : "unknown";
	}

	private static void openSession(Minecraft mc) {
		closeSession();
		Worldgen.get(tempDir());
		session = Session.open(sessionKey(mc));
		solver = new Solver(session);
		lastPhase = null;
		lastSampleCount = session.biomeSampleCount();
		LOCATOR.setSeed(session.seed());
		if (mc.getSingleplayerServer() != null && session.seed() == null) {
			long seed = mc.getSingleplayerServer().overworld().getSeed();
			session.setSeed(seed, "singleplayer");
			LOCATOR.setSeed(seed);
		}
		solver.request();
		LOG.info("Seed Cracker session {}", session.key());
	}

	private static void closeSession() {
		if (solver != null) {
			solver.close();
			solver = null;
		}
		if (session != null) {
			session.save();
			session = null;
		}
		SCANNER.reset();
	}

	/** The seed hash the server sent with the login/respawn packet, used for biome blending. */
	static Long hashedSeed(ClientLevel level) {
		try {
			if (zoomSeedField == null) {
				zoomSeedField = BiomeManager.class.getDeclaredField("biomeZoomSeed");
				zoomSeedField.setAccessible(true);
			}
			return zoomSeedField.getLong(level.getBiomeManager());
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	public static SeedWorld.Dim currentDim() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? null : SeedWorld.Dim.of(mc.level.dimension());
	}

	private static void tick(Minecraft mc) {
		Session s = session;
		if (s == null || mc.level == null || mc.player == null) {
			return;
		}
		while (locatorKey.consumeClick()) {
			mc.setScreenAndShow(new LocatorScreen());
		}
		while (mapKey.consumeClick()) {
			mc.setScreenAndShow(new MapScreen());
		}
		while (linesKey.consumeClick()) {
			settings.lines = !settings.lines;
			settings.markers = settings.lines;
			settings.save();
			message(Component.literal("Линии и маркеры: " + (settings.lines ? "вкл" : "выкл")));
		}
		ticks++;
		if (ticks % 40 == 0) {
			Long hashed = hashedSeed(mc.level);
			if (hashed != null && hashed != 0L && (s.hashedSeed() == null || s.hashedSeed().longValue() != hashed)) {
				s.setHashedSeed(hashed);
				solver.request();
			}
		}
		SCANNER.tick(mc.level, s, solver);
		LOCATOR.setSeed(s.seed());
		// New biome cells can settle an ambiguous or hash-less seed.
		int samples = s.biomeSampleCount();
		if (samples >= lastSampleCount + 8 && (s.phase() == Session.Phase.AMBIGUOUS || s.phase() == Session.Phase.UPPER_BITS)) {
			lastSampleCount = samples;
			solver.request();
		}
		if (ticks % 20 == 0) {
			markVisited(mc, s);
			refineTarget(s);
		}
		Session.Phase phase = s.phase();
		if (phase != lastPhase) {
			if (lastPhase != null && (phase == Session.Phase.CONTRADICTION || phase == Session.Phase.AMBIGUOUS || phase == Session.Phase.SLOW)) {
				message(Component.literal("[SC] " + s.detail()).withStyle(ChatFormatting.YELLOW));
			}
			lastPhase = phase;
		}
		if (ticks % 100 == 0) {
			s.saveIfDirty();
		}
	}

	/** A stronghold target starts at its ring position; once its pieces are built it moves to the portal room. */
	private static void refineTarget(Session s) {
		Session.Target t = s.target();
		SeedWorld.Dim dim = currentDim();
		if (t == null || t.refined || t.chunkX == null || dim == null || !Targets.dimId(dim).equals(t.dim) || s.seed() == null) {
			return;
		}
		StructureKind kind = StructureKind.byId(t.kind);
		if (kind != StructureKind.STRONGHOLD) {
			return;
		}
		var found = LOCATOR.structures(dim, kind, t.x, t.z, 256);
		if (found == null) {
			return;
		}
		for (SeedWorld.Found f : found) {
			if (f.chunkX() == t.chunkX && f.chunkZ() == t.chunkZ) {
				SeedWorld.Found d = LOCATOR.details(dim, f);
				if (d.box() != null) {
					Session.Target refined = Targets.of(d, dim);
					refined.label = SeedCrackerClient.russian() ? "Портал в энд" : "End portal";
					s.setTarget(refined);
				}
			}
		}
	}

	/** Structures the player walked up to count as visited, so "nearest" moves on to the next one. */
	private static void markVisited(Minecraft mc, Session s) {
		SeedWorld.Dim dim = currentDim();
		if (dim == null || s.seed() == null) {
			return;
		}
		double px = mc.player.getX();
		double pz = mc.player.getZ();
		for (String id : settings.tracked) {
			StructureKind kind = StructureKind.byId(id);
			if (kind == null || kind.overworld != dim.overworld()) {
				continue;
			}
			var list = LOCATOR.structures(dim, kind, px, pz, settings.radius);
			if (list == null) {
				continue;
			}
			for (SeedWorld.Found f : list) {
				if (f.distanceTo(px, pz) < settings.visitDistance && !s.isVisited(f.key())) {
					s.setVisited(f.key(), true);
				}
			}
		}
	}

	public static void message(Component component) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(component);
		}
	}

	public static boolean russian() {
		Minecraft mc = Minecraft.getInstance();
		return mc.options == null || mc.options.languageCode == null || mc.options.languageCode.startsWith("ru");
	}

	public static String name(StructureKind kind) {
		return russian() ? kind.nameRu : kind.nameEn;
	}

	public static String biomeName(String id) {
		String key = "biome.minecraft." + id;
		return net.minecraft.locale.Language.getInstance().has(key) ? I18n.get(key) : id;
	}

	static void onDetected(Session.Obs o) {
		Session s = session;
		double bits = s == null ? 0 : s.informationBits();
		int count = s == null ? 0 : s.observations().size();
		Minecraft.getInstance().execute(() -> message(Component.literal("[SC] Нашёл: " + o.describe())
				.withStyle(ChatFormatting.AQUA)
				.append(Component.literal(String.format("  (структур %d, ~%.0f бит)", count, bits)).withStyle(ChatFormatting.GRAY))));
	}

	static void onSolved(long seed) {
		Minecraft.getInstance().execute(() -> {
			LOCATOR.setSeed(seed);
			message(seedLine("[SC] Сид найден: ", seed));
		});
	}

	static MutableComponent seedLine(String prefix, long seed) {
		return Component.literal(prefix).withStyle(ChatFormatting.GREEN)
				.append(Component.literal(Long.toString(seed)).withStyle(style -> style.withColor(ChatFormatting.WHITE).withUnderlined(true)
						.withClickEvent(new ClickEvent.CopyToClipboard(Long.toString(seed)))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Скопировать")))))
				.append(Component.literal("  B поиск, M карта").withStyle(ChatFormatting.GRAY));
	}
}
