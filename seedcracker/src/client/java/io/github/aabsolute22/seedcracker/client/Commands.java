package io.github.aabsolute22.seedcracker.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import io.github.aabsolute22.seedcracker.core.StructureKind;
import io.github.aabsolute22.seedcracker.world.SeedWorld;

/** The {@code /sc} client command. */
public final class Commands {
	private Commands() {
	}

	private static final SuggestionProvider<FabricClientCommandSource> KINDS = (ctx, builder) -> {
		List<String> ids = new ArrayList<>();
		for (StructureKind k : StructureKind.values()) {
			ids.add(k.id());
		}
		return SharedSuggestionProvider.suggest(ids, builder);
	};

	/** Kinds that say something about the seed, current dimension first. */
	private static final SuggestionProvider<FabricClientCommandSource> MANUAL_KINDS = (ctx, builder) -> {
		List<String> ids = new ArrayList<>();
		SeedWorld.Dim dim = SeedCrackerClient.currentDim();
		for (StructureKind k : StructureKind.values()) {
			if (io.github.aabsolute22.seedcracker.world.ManualPlacement.useful(k) && (dim == null || k.overworld == dim.overworld())) {
				ids.add(k.id());
			}
		}
		return SharedSuggestionProvider.suggest(ids, builder);
	};

	private static final SuggestionProvider<FabricClientCommandSource> BIOMES = (ctx, builder) -> {
		List<String> ids = new ArrayList<>();
		MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD.usedBiomes().forEach(k -> ids.add(k.identifier().getPath()));
		MultiNoiseBiomeSourceParameterList.Preset.NETHER.usedBiomes().forEach(k -> ids.add(k.identifier().getPath()));
		return SharedSuggestionProvider.suggest(ids, builder);
	};

	static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("sc")
				.executes(ctx -> status(ctx.getSource()))
				.then(literal("status").executes(ctx -> status(ctx.getSource())))
				.then(literal("help").executes(ctx -> help(ctx.getSource())))
				.then(literal("find").then(argument("kind", StringArgumentType.word()).suggests(KINDS)
						.executes(ctx -> find(ctx.getSource(), StringArgumentType.getString(ctx, "kind"), 8))
						.then(argument("count", IntegerArgumentType.integer(1, 50))
								.executes(ctx -> find(ctx.getSource(), StringArgumentType.getString(ctx, "kind"), IntegerArgumentType.getInteger(ctx, "count"))))))
				.then(literal("biome").then(argument("biome", StringArgumentType.word()).suggests(BIOMES)
						.executes(ctx -> biome(ctx.getSource(), StringArgumentType.getString(ctx, "biome")))))
				.then(literal("target")
						.then(literal("clear").executes(ctx -> {
							Session s = session(ctx.getSource());
							if (s != null) {
								s.setTarget(null);
								ctx.getSource().sendFeedback(Component.literal("Цель убрана"));
							}
							return 1;
						}))
						.then(argument("x", IntegerArgumentType.integer()).then(argument("y", IntegerArgumentType.integer())
								.then(argument("z", IntegerArgumentType.integer()).executes(ctx -> target(ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z"),
										"Точка"))))))
				.then(literal("track").then(argument("kind", StringArgumentType.word()).suggests(KINDS)
						.executes(ctx -> track(ctx.getSource(), StringArgumentType.getString(ctx, "kind")))))
				.then(literal("seed")
						.executes(ctx -> showSeed(ctx.getSource()))
						.then(literal("clear").executes(ctx -> setSeed(ctx.getSource(), null)))
						.then(argument("seed", LongArgumentType.longArg()).executes(ctx -> setSeed(ctx.getSource(), LongArgumentType.getLong(ctx, "seed")))))
				.then(literal("structures").executes(ctx -> structures(ctx.getSource())))
				.then(literal("remove").then(argument("id", IntegerArgumentType.integer(1))
						.executes(ctx -> remove(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
				.then(literal("add")
						.executes(ctx -> {
							Minecraft mc = ctx.getSource().getClient();
							mc.execute(() -> mc.execute(() -> mc.setScreenAndShow(new AddScreen(null))));
							return 1;
						})
						.then(argument("kind", StringArgumentType.word()).suggests(MANUAL_KINDS)
								.executes(ctx -> addHere(ctx.getSource(), StringArgumentType.getString(ctx, "kind")))
								.then(literal("chunk").then(argument("chunkX", IntegerArgumentType.integer()).then(argument("chunkZ", IntegerArgumentType.integer())
										.executes(ctx -> addChunk(ctx.getSource(), StringArgumentType.getString(ctx, "kind"), IntegerArgumentType.getInteger(ctx, "chunkX"),
												IntegerArgumentType.getInteger(ctx, "chunkZ"), -1))
										.then(argument("rotation", IntegerArgumentType.integer(0, 270))
												.executes(ctx -> addChunk(ctx.getSource(), StringArgumentType.getString(ctx, "kind"),
														IntegerArgumentType.getInteger(ctx, "chunkX"), IntegerArgumentType.getInteger(ctx, "chunkZ"),
														IntegerArgumentType.getInteger(ctx, "rotation") / 90))))))
								.then(argument("x", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer())
										.executes(ctx -> addAt(ctx.getSource(), StringArgumentType.getString(ctx, "kind"), IntegerArgumentType.getInteger(ctx, "x"),
												IntegerArgumentType.getInteger(ctx, "z")))))))
				.then(literal("crack").executes(ctx -> {
					Solver solver = SeedCrackerClient.solver();
					if (solver != null) {
						solver.allowSlow();
						ctx.getSource().sendFeedback(Component.literal("Запускаю перебор, даже если он долгий"));
					}
					return 1;
				}))
				.then(literal("cancel").executes(ctx -> {
					Solver solver = SeedCrackerClient.solver();
					if (solver != null) {
						solver.cancelRunning();
						ctx.getSource().sendFeedback(Component.literal("Перебор остановлен"));
					}
					return 1;
				}))
				.then(literal("radius").then(argument("blocks", IntegerArgumentType.integer(256, 30000)).executes(ctx -> {
					Settings st = SeedCrackerClient.settings();
					st.radius = IntegerArgumentType.getInteger(ctx, "blocks");
					st.save();
					ctx.getSource().sendFeedback(Component.literal("Радиус поиска: " + st.radius + " блоков"));
					return 1;
				})))
				.then(literal("lines").executes(ctx -> toggle(ctx.getSource(), "Линия к цели", () -> {
					Settings st = SeedCrackerClient.settings();
					st.lines = !st.lines;
					return st.lines;
				})))
				.then(literal("markers").executes(ctx -> toggle(ctx.getSource(), "Метки отслеживаемых структур", () -> {
					Settings st = SeedCrackerClient.settings();
					st.markers = !st.markers;
					return st.markers;
				})))
				.then(literal("hud").executes(ctx -> toggle(ctx.getSource(), "HUD", () -> {
					Settings st = SeedCrackerClient.settings();
					st.hud = !st.hud;
					return st.hud;
				})))
				.then(literal("slime").executes(ctx -> toggle(ctx.getSource(), "Слайм-чанки", () -> {
					Settings st = SeedCrackerClient.settings();
					st.slimeChunks = !st.slimeChunks;
					return st.slimeChunks;
				})))
				.then(literal("visited").executes(ctx -> toggle(ctx.getSource(), "Скрывать посещённые", () -> {
					Settings st = SeedCrackerClient.settings();
					st.hideVisited = !st.hideVisited;
					return st.hideVisited;
				})))
				.then(literal("gui").executes(ctx -> {
					Minecraft mc = ctx.getSource().getClient();
					mc.execute(() -> mc.execute(() -> mc.setScreenAndShow(new LocatorScreen())));
					return 1;
				}))
				.then(literal("map").executes(ctx -> {
					Minecraft mc = ctx.getSource().getClient();
					mc.execute(() -> mc.execute(() -> mc.setScreenAndShow(new MapScreen())));
					return 1;
				}))
				.then(literal("reset").then(literal("confirm").executes(ctx -> {
					Session s = session(ctx.getSource());
					if (s != null) {
						SeedCrackerClient.solver().cancelRunning();
						s.reset();
						SeedCrackerClient.locator().setSeed(null);
						SeedCrackerClient.solver().request();
						ctx.getSource().sendFeedback(Component.literal("Все данные этого мира удалены"));
					}
					return 1;
				}))));
	}

	private static Session session(FabricClientCommandSource source) {
		Session s = SeedCrackerClient.session();
		if (s == null) {
			source.sendError(Component.literal("Нет активного мира"));
		}
		return s;
	}

	private static int toggle(FabricClientCommandSource source, String what, java.util.function.BooleanSupplier flip) {
		boolean on = flip.getAsBoolean();
		SeedCrackerClient.settings().save();
		source.sendFeedback(Component.literal(what + ": " + (on ? "вкл" : "выкл")));
		return 1;
	}

	private static int help(FabricClientCommandSource source) {
		String[] lines = {
			"Seed Cracker: летай и исследуй, мод сам замечает структуры и биомы и вычисляет сид.",
			"B поиск структур и биомов · M карта · V линии вкл/выкл",
			"/sc find <тип> [n] ближайшие · /sc biome <биом> · /sc track <тип> метки в мире",
			"/sc target <x> <y> <z> | clear · /sc seed [число|clear] · /sc structures · /sc remove <id>",
			"/sc add экран ручного ввода · /sc add <тип> структура, где ты стоишь · /sc add <тип> <x> <z> по координатам",
			"/sc add <тип> chunk <чанкX> <чанкZ> [0|90|180|270] по стартовому чанку · /sc crack долгий перебор · /sc cancel",
			"/sc radius <блоки> · /sc slime · /sc lines · /sc markers · /sc hud · /sc reset confirm"
		};
		for (String line : lines) {
			source.sendFeedback(Component.literal(line));
		}
		return 1;
	}

	private static int status(FabricClientCommandSource source) {
		Session s = session(source);
		if (s == null) {
			return 0;
		}
		source.sendFeedback(Component.literal(HudOverlay.statusText(s)).withStyle(ChatFormatting.GOLD));
		String variants = s.seed() == null && !s.structureSeeds().isEmpty() ? " · вариантов сида структур " + s.structureSeeds().size() : "";
		source.sendFeedback(Component.literal(String.format("структур %d (~%.0f бит) · биомов %d · хэш сида %s",
				s.observations().size(), s.informationBits(), s.biomeSampleCount(), s.hashedSeed() != null ? "есть" : "нет") + variants));
		if (s.seed() != null) {
			source.sendFeedback(SeedCrackerClient.seedLine("сид: ", s.seed()));
		} else {
			source.sendFeedback(Component.literal("Быстрее всего помогают пирамиды, храмы в джунглях, хижины ведьм, иглу, аванпосты, корабли и клады.")
					.withStyle(ChatFormatting.GRAY));
		}
		return 1;
	}

	private static int showSeed(FabricClientCommandSource source) {
		Session s = session(source);
		if (s == null) {
			return 0;
		}
		if (s.seed() == null) {
			source.sendFeedback(Component.literal("Сид ещё не найден: " + s.detail()));
		} else {
			source.sendFeedback(SeedCrackerClient.seedLine("сид: ", s.seed()));
		}
		return 1;
	}

	private static int setSeed(FabricClientCommandSource source, Long seed) {
		Session s = session(source);
		if (s == null) {
			return 0;
		}
		s.setSeed(seed, "manual");
		SeedCrackerClient.locator().setSeed(seed);
		SeedCrackerClient.solver().request();
		source.sendFeedback(Component.literal(seed == null ? "Сид сброшен, ищу заново" : "Сид задан: " + seed));
		return 1;
	}

	private static StructureKind kind(FabricClientCommandSource source, String id) {
		StructureKind k = StructureKind.byId(id);
		if (k == null) {
			source.sendError(Component.literal("Нет такого типа: " + id));
		}
		return k;
	}

	private static int find(FabricClientCommandSource source, String id, int count) {
		Session s = session(source);
		StructureKind k = kind(source, id);
		SeedWorld.Dim dim = SeedCrackerClient.currentDim();
		if (s == null || k == null || dim == null) {
			return 0;
		}
		if (s.seed() == null) {
			source.sendFeedback(Component.literal("Сид ещё не найден: " + s.detail()));
			return 0;
		}
		if (k.overworld != dim.overworld()) {
			source.sendFeedback(Component.literal(SeedCrackerClient.name(k) + " бывает только в " + (k.overworld ? "верхнем мире" : "незере")));
			return 0;
		}
		Minecraft mc = source.getClient();
		double px = mc.player.getX();
		double pz = mc.player.getZ();
		List<SeedWorld.Found> found = SeedCrackerClient.locator().structures(dim, k, px, pz, SeedCrackerClient.settings().radius);
		if (found == null) {
			source.sendFeedback(Component.literal("Ищу… повтори через секунду"));
			return 1;
		}
		if (found.isEmpty()) {
			source.sendFeedback(Component.literal("В радиусе " + SeedCrackerClient.settings().radius + " ничего нет"));
			return 1;
		}
		source.sendFeedback(Component.literal(SeedCrackerClient.name(k) + " рядом:").withStyle(ChatFormatting.GOLD));
		for (int i = 0; i < Math.min(count, found.size()); i++) {
			SeedWorld.Found f = found.get(i);
			double d = f.distanceTo(px, pz);
			String line = (i + 1) + ". " + Targets.label(f) + " · " + f.x() + " " + f.y() + " " + f.z() + " · " + Targets.distance(d) + " "
					+ Targets.compass(f.x() - px, f.z() - pz) + (s.isVisited(f.key()) ? " · был" : "");
			source.sendFeedback(clickable(line, "/sc target " + f.x() + " " + f.y() + " " + f.z(), "Нажми, чтобы вести сюда", k.color));
		}
		return 1;
	}

	private static MutableComponent clickable(String text, String command, String hover, int color) {
		return Component.literal(text).withStyle(style -> style.withColor(color & 0xFFFFFF)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	private static int biome(FabricClientCommandSource source, String id) {
		Session s = session(source);
		SeedWorld.Dim dim = SeedCrackerClient.currentDim();
		if (s == null || dim == null) {
			return 0;
		}
		if (s.seed() == null) {
			source.sendFeedback(Component.literal("Сид ещё не найден: " + s.detail()));
			return 0;
		}
		Minecraft mc = source.getClient();
		double px = mc.player.getX();
		double pz = mc.player.getZ();
		List<BlockPos> spots = SeedCrackerClient.locator().biomes(dim, id, px, pz, Math.max(6000, SeedCrackerClient.settings().radius));
		if (spots == null) {
			source.sendFeedback(Component.literal("Ищу… повтори через пару секунд"));
			return 1;
		}
		if (spots.isEmpty()) {
			source.sendFeedback(Component.literal("Рядом нет: " + SeedCrackerClient.biomeName(id)));
			return 1;
		}
		for (BlockPos p : spots) {
			String line = SeedCrackerClient.biomeName(id) + " · " + p.getX() + " " + p.getY() + " " + p.getZ() + " · "
					+ Targets.distance(Math.hypot(p.getX() - px, p.getZ() - pz));
			source.sendFeedback(clickable(line, "/sc target " + p.getX() + " " + p.getY() + " " + p.getZ(), "Нажми, чтобы вести сюда", 0x7CF2A0));
		}
		return 1;
	}

	private static int target(FabricClientCommandSource source, int x, int y, int z, String label) {
		Session s = session(source);
		SeedWorld.Dim dim = SeedCrackerClient.currentDim();
		if (s == null || dim == null) {
			return 0;
		}
		// Use the structure's own label when the point is one of the located structures.
		String name = label;
		Minecraft mc = source.getClient();
		for (StructureKind k : StructureKind.values()) {
			if (k.overworld != dim.overworld() || s.seed() == null) {
				continue;
			}
			List<SeedWorld.Found> found = SeedCrackerClient.locator().structures(dim, k, mc.player.getX(), mc.player.getZ(), SeedCrackerClient.settings().radius);
			if (found != null) {
				for (SeedWorld.Found f : found) {
					if (f.x() == x && f.z() == z) {
						s.setTarget(Targets.of(f, dim));
						source.sendFeedback(Component.literal("Цель: " + Targets.label(f) + " · " + x + " " + y + " " + z));
						return 1;
					}
				}
			}
		}
		s.setTarget(Targets.point(x, y, z, dim, name));
		source.sendFeedback(Component.literal("Цель: " + x + " " + y + " " + z));
		return 1;
	}

	private static int track(FabricClientCommandSource source, String id) {
		StructureKind k = kind(source, id);
		if (k == null) {
			return 0;
		}
		Settings st = SeedCrackerClient.settings();
		boolean on = !st.tracked.remove(k.id());
		if (on) {
			st.tracked.add(k.id());
		}
		st.save();
		source.sendFeedback(Component.literal(SeedCrackerClient.name(k) + ": " + (on ? "показываю в мире" : "не показываю")));
		return 1;
	}

	private static int structures(FabricClientCommandSource source) {
		Session s = session(source);
		if (s == null) {
			return 0;
		}
		List<Session.Obs> obs = s.observations();
		if (obs.isEmpty()) {
			source.sendFeedback(Component.literal("Пока ни одной структуры"));
		}
		for (Session.Obs o : obs) {
			String line = "#" + o.id + " " + o.describe() + " · " + o.x + " " + o.y + " " + o.z + (o.auto ? "" : " · вручную");
			source.sendFeedback(Component.literal(line).withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand("/sc remove " + o.id))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Нажми, чтобы удалить")))));
		}
		return 1;
	}

	private static int remove(FabricClientCommandSource source, int id) {
		Session s = session(source);
		if (s == null) {
			return 0;
		}
		boolean ok = s.removeObservation(id);
		if (ok) {
			s.setStructureSeeds(List.of(), false);
			SeedCrackerClient.solver().request();
		}
		source.sendFeedback(Component.literal(ok ? "Удалил #" + id : "Нет #" + id));
		return 1;
	}

	/** {@code /sc add <kind>}: the structure the player stands in. */
	private static int addHere(FabricClientCommandSource source, String id) {
		StructureKind k = kind(source, id);
		Minecraft mc = source.getClient();
		if (k == null || session(source) == null || mc.player == null) {
			return 0;
		}
		BlockPos p = mc.player.blockPosition();
		return addPlan(source, ManualAdd.plan(k, p.getX(), p.getY(), p.getZ(), true));
	}

	/** {@code /sc add <kind> <x> <z>}: a structure at block coordinates (from F3, a friend or a treasure map). */
	private static int addAt(FabricClientCommandSource source, String id, int x, int z) {
		StructureKind k = kind(source, id);
		if (k == null || session(source) == null) {
			return 0;
		}
		return addPlan(source, ManualAdd.plan(k, x, Integer.MIN_VALUE, z, false));
	}

	private static int addPlan(FabricClientCommandSource source, ManualAdd.Plan plan) {
		if (!plan.ok()) {
			source.sendError(Component.literal(plan.error()));
			return 0;
		}
		Session.Obs o = ManualAdd.commit(session(source), plan);
		source.sendFeedback(ManualAdd.feedback(plan, o));
		return 1;
	}

	/** {@code /sc add <kind> chunk <x> <z> [rotation]}: the exact start chunk, for those who know it. */
	private static int addChunk(FabricClientCommandSource source, String id, int chunkX, int chunkZ, int rotation) {
		Session s = session(source);
		StructureKind k = kind(source, id);
		if (s == null || k == null) {
			return 0;
		}
		if (!io.github.aabsolute22.seedcracker.world.ManualPlacement.useful(k)) {
			source.sendError(Component.literal(SeedCrackerClient.name(k) + " ничего не говорит о сиде"));
			return 0;
		}
		int ox = chunkX - k.spread.region(chunkX) * k.spread.spacing();
		int oz = chunkZ - k.spread.region(chunkZ) * k.spread.spacing();
		if (ox >= k.spread.limit() || oz >= k.spread.limit()) {
			source.sendError(Component.literal("В чанке " + chunkX + " " + chunkZ + " " + SeedCrackerClient.name(k) + " начаться не может"));
			return 0;
		}
		List<int[]> c = new ArrayList<>();
		c.add(new int[] {chunkX, chunkZ, rotation});
		return addPlan(source, new ManualAdd.Plan(k, c, -1, false, chunkX * 16 + 8, 64, chunkZ * 16 + 8, "manual chunk", null));
	}
}
