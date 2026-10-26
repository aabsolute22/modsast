package io.github.aabsolute22.endcityfinder.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import io.github.aabsolute22.endcityfinder.core.BaseFloorLocator;
import io.github.aabsolute22.endcityfinder.core.EndCity;
import io.github.aabsolute22.endcityfinder.core.EndSpikes;
import io.github.aabsolute22.endcityfinder.core.SeedCracker;

/** The {@code /ecf} client command. */
public final class Commands {
	private static List<EndCity> lastListed = List.of();

	private Commands() {
	}

	static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("ecf")
				.executes(ctx -> status(ctx.getSource()))
				.then(literal("status").executes(ctx -> status(ctx.getSource())))
				.then(literal("help").executes(ctx -> help(ctx.getSource())))
				.then(literal("add")
						.executes(ctx -> {
							addNearPlayer(ctx.getSource().getClient());
							return 1;
						})
						.then(argument("x", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer())
								.executes(ctx -> addApprox(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "z"))))))
				.then(literal("chunk").then(argument("cx", IntegerArgumentType.integer()).then(argument("cz", IntegerArgumentType.integer())
						.executes(ctx -> addChunk(ctx, -1))
						.then(argument("rotation", IntegerArgumentType.integer(0, 270))
								.executes(ctx -> addChunk(ctx, IntegerArgumentType.getInteger(ctx, "rotation")))))))
				.then(literal("cities").executes(ctx -> cities(ctx.getSource())))
				.then(literal("remove").then(argument("id", IntegerArgumentType.integer(1))
						.executes(ctx -> remove(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
				.then(literal("pillars").executes(ctx -> pillars(ctx.getSource())))
				.then(literal("pillar").then(argument("index", IntegerArgumentType.integer(0, 9)).then(argument("height", IntegerArgumentType.integer(76, 103))
						.executes(ctx -> pillar(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "index"), IntegerArgumentType.getInteger(ctx, "height"))))))
				.then(literal("list")
						.executes(ctx -> list(ctx.getSource(), 10))
						.then(argument("count", IntegerArgumentType.integer(1, 100))
								.executes(ctx -> list(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))
				.then(literal("target")
						.then(literal("nearest").executes(ctx -> target(ctx.getSource(), 0)))
						.then(argument("n", IntegerArgumentType.integer(1))
								.executes(ctx -> target(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))
				.then(literal("visited").then(argument("n", IntegerArgumentType.integer(1))
						.executes(ctx -> visited(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))
				.then(literal("seed")
						.then(literal("clear").executes(ctx -> seed(ctx.getSource(), null)))
						.then(argument("seed", LongArgumentType.longArg())
								.executes(ctx -> seed(ctx.getSource(), LongArgumentType.getLong(ctx, "seed")))))
				.then(literal("solve").executes(ctx -> {
					FinderSession s = session(ctx.getSource());
					if (s != null) {
						s.requestSolve();
						ctx.getSource().sendFeedback(Component.literal("Пересчитываю"));
					}
					return 1;
				}))
				.then(literal("ships").executes(ctx -> toggle(ctx.getSource(), "Только города с кораблями", () -> {
					Settings st = EndCityFinderClient.settings();
					st.shipsOnly = !st.shipsOnly;
					return st.shipsOnly;
				})))
				.then(literal("markers").executes(ctx -> toggle(ctx.getSource(), "Маркеры в мире", () -> {
					Settings st = EndCityFinderClient.settings();
					st.worldMarkers = !st.worldMarkers;
					return st.worldMarkers;
				})))
				.then(literal("arrow").executes(ctx -> toggle(ctx.getSource(), "Стрелка к ближайшему", () -> {
					Settings st = EndCityFinderClient.settings();
					st.arrow = !st.arrow;
					return st.arrow;
				})))
				.then(literal("hud").executes(ctx -> toggle(ctx.getSource(), "HUD", () -> {
					Settings st = EndCityFinderClient.settings();
					st.hud = !st.hud;
					return st.hud;
				})))
				.then(literal("radius").then(argument("blocks", IntegerArgumentType.integer(500, 30000)).executes(ctx -> {
					Settings st = EndCityFinderClient.settings();
					st.radius = IntegerArgumentType.getInteger(ctx, "blocks");
					st.save();
					ctx.getSource().sendFeedback(Component.literal("Радиус поиска: " + st.radius + " блоков"));
					return 1;
				})))
				.then(literal("map").executes(ctx -> {
					Minecraft mc = ctx.getSource().getClient();
					// The chat screen closes right after the command, so open the map on the next tick.
					mc.execute(() -> mc.execute(() -> mc.setScreenAndShow(new EndMapScreen())));
					return 1;
				}))
				.then(literal("reset").then(literal("confirm").executes(ctx -> {
					FinderSession s = session(ctx.getSource());
					if (s != null) {
						s.clearAll();
						EndCityFinderClient.scanner().reset();
						ctx.getSource().sendFeedback(Component.literal("Все данные этого мира удалены"));
					}
					return 1;
				}))));
	}

	private static FinderSession session(FabricClientCommandSource source) {
		FinderSession s = EndCityFinderClient.session();
		if (s == null) {
			source.sendError(Component.literal("Нет активного мира"));
		}
		return s;
	}

	private static int help(FabricClientCommandSource source) {
		String[] lines = {
			"/ecf status: что известно и что делает солвер",
			"/ecf add: город рядом (встань в нижнем зале главной башни)",
			"/ecf add <x> <z>: примерные координаты основания города",
			"/ecf chunk <cx> <cz> [0|90|180|270]: точный стартовый чанк",
			"/ecf cities, /ecf remove <id>: найденные города",
			"/ecf pillars, /ecf pillar <0-9> <высота>: пилоны",
			"/ecf list [n], /ecf target <n|nearest>, /ecf visited <n>",
			"/ecf seed <число>: если сид уже известен (подходит и полный сид мира)",
			"/ecf ships, /ecf markers, /ecf arrow, /ecf hud, /ecf radius <блоки>, /ecf map",
			"Клавиши: J карта, K маркеры, L добавить город рядом"
		};
		for (String line : lines) {
			source.sendFeedback(Component.literal(line));
		}
		return 1;
	}

	private static int status(FabricClientCommandSource source) {
		FinderSession s = session(source);
		if (s == null) {
			return 0;
		}
		List<FinderSession.ObservedCity> cities = s.cities();
		long exact = cities.stream().filter(c -> c.exact).count();
		source.sendFeedback(Component.literal("End City Finder: " + s.phase() + "  " + s.detail()).withStyle(ChatFormatting.GOLD));
		source.sendFeedback(Component.literal("пилоны " + s.knownSpikes() + "/10 · города " + cities.size() + " (точных " + exact + ") · пустых чанков "
				+ s.emptyChunkCount() + " · сэмплов рельефа " + s.sampleCount() + " · кандидатов " + s.candidateCount()));
		double p = s.progress();
		if (p >= 0) {
			source.sendFeedback(Component.literal(String.format("перебор: %.0f%%", p * 100)));
		}
		if (s.phase() == FinderSession.Phase.SOLVED || s.phase() == FinderSession.Phase.AMBIGUOUS) {
			source.sendFeedback(Component.literal("предсказано городов рядом: " + s.predicted().size()));
		}
		return 1;
	}

	static void addNearPlayer(Minecraft mc) {
		FinderSession s = EndCityFinderClient.session();
		if (s == null || mc.player == null) {
			return;
		}
		double x = mc.player.getX();
		double z = mc.player.getZ();
		List<FinderSession.ObservedCity> added = EndCityFinderClient.scanner().detectCities(s, true, x, z);
		if (added.isEmpty()) {
			FinderSession.ObservedCity city = s.addCity(BaseFloorLocator.around((int) x, (int) z, 20), (int) x, (int) z, false, false);
			EndCityFinderClient.message(Component.literal("Блоков города рядом не видно, записал примерно: #" + city.id + " " + city.describe()).withStyle(ChatFormatting.YELLOW));
			return;
		}
		for (FinderSession.ObservedCity city : added) {
			EndCityFinderClient.message(Component.literal("Город #" + city.id + ": " + city.describe() + (city.exact ? " (точно)" : " (неточно)")).withStyle(ChatFormatting.AQUA));
		}
	}

	private static int addApprox(FabricClientCommandSource source, int x, int z) {
		FinderSession s = session(source);
		if (s == null) {
			return 0;
		}
		FinderSession.ObservedCity city = s.addCity(BaseFloorLocator.around(x, z, 16), x, z, false, false);
		source.sendFeedback(Component.literal("Город #" + city.id + ": " + city.describe()));
		return 1;
	}

	private static int addChunk(CommandContext<FabricClientCommandSource> ctx, int rotationDegrees) {
		FinderSession s = session(ctx.getSource());
		if (s == null) {
			return 0;
		}
		int cx = IntegerArgumentType.getInteger(ctx, "cx");
		int cz = IntegerArgumentType.getInteger(ctx, "cz");
		if (rotationDegrees >= 0 && rotationDegrees % 90 != 0) {
			ctx.getSource().sendError(Component.literal("Поворот: 0, 90, 180 или 270"));
			return 0;
		}
		int rotation = rotationDegrees < 0 ? -1 : rotationDegrees / 90;
		FinderSession.ObservedCity city = s.addCity(List.of(new SeedCracker.Candidate(cx, cz, rotation)), cx * 16 + 7, cz * 16 + 7, false, true);
		ctx.getSource().sendFeedback(Component.literal("Город #" + city.id + ": " + city.describe()));
		return 1;
	}

	private static int cities(FabricClientCommandSource source) {
		FinderSession s = session(source);
		if (s == null) {
			return 0;
		}
		List<FinderSession.ObservedCity> cities = s.cities();
		if (cities.isEmpty()) {
			source.sendFeedback(Component.literal("Пока ни одного города"));
		}
		for (FinderSession.ObservedCity c : cities) {
			String kind = c.exact ? "точно" : "примерно";
			if (c.auto && !c.exact) {
				kind = "не используется, подтверди /ecf add";
			}
			source.sendFeedback(Component.literal("#" + c.id + "  " + c.x + " " + c.z + "  " + c.describe() + "  [" + kind + "]")
					.withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand("/ecf remove " + c.id))
							.withHoverEvent(new HoverEvent.ShowText(Component.literal("Нажми, чтобы удалить")))));
		}
		return 1;
	}

	private static int remove(FabricClientCommandSource source, int id) {
		FinderSession s = session(source);
		if (s == null) {
			return 0;
		}
		source.sendFeedback(Component.literal(s.removeCity(id) ? "Удалил #" + id : "Нет города #" + id));
		return 1;
	}

	private static int pillars(FabricClientCommandSource source) {
		FinderSession s = session(source);
		if (s == null) {
			return 0;
		}
		int[] spikes = s.spikes();
		for (int i = 0; i < spikes.length; i++) {
			String value = spikes[i] < 0 ? "?" : "высота " + EndSpikes.heightOfSize(spikes[i]) + ", радиус " + (2 + spikes[i] / 3);
			source.sendFeedback(Component.literal("#" + i + " (" + EndSpikes.centerX(i) + ", " + EndSpikes.centerZ(i) + "): " + value));
		}
		return 1;
	}

	private static int pillar(FabricClientCommandSource source, int index, int height) {
		FinderSession s = session(source);
		if (s == null) {
			return 0;
		}
		int k = EndSpikes.sizeOfHeight(height);
		if (k < 0) {
			source.sendError(Component.literal("Высота пилона бывает 76, 79, 82 ... 103 (верхний бедрок)"));
			return 0;
		}
		s.setSpike(index, k);
		source.sendFeedback(Component.literal("Пилон #" + index + ": высота " + height));
		return 1;
	}

	static String direction(double dx, double dz) {
		String[] names = {"ю", "юз", "з", "сз", "с", "св", "в", "юв"};
		double yaw = Math.toDegrees(Math.atan2(-dx, dz));
		return names[Math.floorMod((int) Math.round(yaw / 45.0), 8)];
	}

	private static int list(FabricClientCommandSource source, int count) {
		FinderSession s = session(source);
		if (s == null) {
			return 0;
		}
		double px = source.getPlayer().getX();
		double pz = source.getPlayer().getZ();
		List<EndCity> cities = new ArrayList<>(s.predicted());
		if (cities.isEmpty()) {
			source.sendFeedback(Component.literal("Предсказаний пока нет: " + s.detail()));
			return 1;
		}
		cities.sort((a, b) -> Double.compare(Math.hypot(a.x() - px, a.z() - pz), Math.hypot(b.x() - px, b.z() - pz)));
		lastListed = cities;
		for (int i = 0; i < Math.min(count, cities.size()); i++) {
			EndCity c = cities.get(i);
			double dx = c.x() - px;
			double dz = c.z() - pz;
			boolean visited = s.isVisited(c);
			MutableComponent line = Component.literal(String.format("#%d  %5d м %-2s  [%d %d %d]", i + 1, (int) Math.hypot(dx, dz), direction(dx, dz), c.x(), c.y(), c.z()));
			if (c.ship()) {
				line.append(Component.literal("  корабль").withStyle(ChatFormatting.GOLD));
			}
			if (visited) {
				line.append(Component.literal("  посещён").withStyle(ChatFormatting.GRAY));
			}
			int n = i + 1;
			line.withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand("/ecf target " + n))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("чанк " + c.chunkX() + " " + c.chunkZ() + (c.ship() ? ", корабль у " + c.shipX() + " " + c.shipY() + " " + c.shipZ() : "") + "\nнажми, чтобы сделать целью"))));
			source.sendFeedback(visited ? line.withStyle(ChatFormatting.DARK_GRAY) : line);
		}
		return 1;
	}

	private static EndCity listed(FabricClientCommandSource source, int n) {
		if (n < 1 || n > lastListed.size()) {
			source.sendError(Component.literal("Сначала /ecf list, номер из этого списка"));
			return null;
		}
		return lastListed.get(n - 1);
	}

	private static int target(FabricClientCommandSource source, int n) {
		FinderSession s = session(source);
		if (s == null) {
			return 0;
		}
		if (n == 0) {
			s.setTarget(null);
			source.sendFeedback(Component.literal("Цель: ближайший непосещённый город"));
			return 1;
		}
		EndCity city = listed(source, n);
		if (city != null) {
			s.setTarget(city);
			source.sendFeedback(Component.literal("Цель: город " + city.x() + " " + city.z()));
		}
		return 1;
	}

	private static int visited(FabricClientCommandSource source, int n) {
		FinderSession s = session(source);
		EndCity city = listed(source, n);
		if (s == null || city == null) {
			return 0;
		}
		boolean now = !s.isVisited(city);
		s.setVisited(city, now);
		source.sendFeedback(Component.literal("Город " + city.x() + " " + city.z() + (now ? " посещён" : " не посещён")));
		return 1;
	}

	private static int seed(FabricClientCommandSource source, Long seed) {
		FinderSession s = session(source);
		if (s == null) {
			return 0;
		}
		s.setManualSeed(seed);
		source.sendFeedback(Component.literal(seed == null ? "Сид сброшен, считаю по наблюдениям" : "Использую сид " + seed));
		return 1;
	}

	private interface Toggle {
		boolean flip();
	}

	private static int toggle(FabricClientCommandSource source, String name, Toggle toggle) {
		boolean value = toggle.flip();
		EndCityFinderClient.settings().save();
		source.sendFeedback(Component.literal(name + ": " + (value ? "вкл" : "выкл")));
		return 1;
	}
}
