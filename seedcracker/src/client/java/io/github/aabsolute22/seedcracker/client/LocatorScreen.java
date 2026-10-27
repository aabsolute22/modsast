package io.github.aabsolute22.seedcracker.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;

import io.github.aabsolute22.seedcracker.core.StructureKind;
import io.github.aabsolute22.seedcracker.world.SeedWorld;

/**
 * The search screen: type a structure or biome name, pick a category on the left, click a result on the right
 * to make it the target. Everything comes from the background locator, so the screen never blocks.
 */
public final class LocatorScreen extends Screen {
	private static final int ROW = 14;
	private static final int RESULT_ROW = 24;
	private static final int LEFT_W = 190;
	private static final int TOP = 44;

	private record Category(String id, String label, String search, int color, StructureKind kind, String biome) {
	}

	private record Row(String title, String sub, int color, Runnable click, Runnable rightClick, boolean highlighted) {
	}

	private static String lastQuery = "";
	private static String lastSelected;

	private EditBox search;
	private final List<Category> categories = new ArrayList<>();
	private List<Category> filtered = new ArrayList<>();
	private String selected;
	private double leftScroll;
	private double rightScroll;
	private List<Row> rows = List.of();
	private Button trackButton;

	public LocatorScreen() {
		super(Component.literal("Seed Cracker"));
	}

	@Override
	protected void init() {
		buildCategories();
		if (selected == null) {
			Session s = SeedCrackerClient.session();
			boolean nether = SeedCrackerClient.currentDim() == SeedWorld.Dim.NETHER;
			selected = lastSelected != null && categories.stream().anyMatch(c -> c.id.equals(lastSelected)) ? lastSelected
					: s != null && s.seed() != null ? (nether ? "s:fortress" : "s:village") : "found";
		}
		search = new EditBox(font, 10, 22, LEFT_W, 16, Component.literal("search"));
		search.setHint(Component.literal(SeedCrackerClient.russian() ? "Поиск: деревня, крепость, вишнёвая роща…" : "Search: village, fortress, cherry…"));
		search.setMaxLength(64);
		search.setValue(lastQuery);
		search.setResponder(q -> {
			lastQuery = q;
			refilter();
			leftScroll = 0;
		});
		addRenderableWidget(search);
		setFocused(search);
		search.setFocused(true);
		int by = height - 24;
		int bw = Math.min(118, (width - 20 - 4 * 4) / 5);
		addRenderableWidget(Button.builder(Component.literal("Карта (M)"), b -> Minecraft.getInstance().setScreenAndShow(new MapScreen()))
				.bounds(10, by, bw, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Убрать цель"), b -> {
			Session s = SeedCrackerClient.session();
			if (s != null) {
				s.setTarget(null);
			}
		}).bounds(10 + (bw + 4), by, bw, 18).build());
		addRenderableWidget(toggle(10 + 2 * (bw + 4), by, bw, "Линии", () -> SeedCrackerClient.settings().lines, v -> SeedCrackerClient.settings().lines = v));
		addRenderableWidget(toggle(10 + 3 * (bw + 4), by, bw, "Метки", () -> SeedCrackerClient.settings().markers, v -> SeedCrackerClient.settings().markers = v));
		addRenderableWidget(toggle(10 + 4 * (bw + 4), by, bw, "Слизни", () -> SeedCrackerClient.settings().slimeChunks,
				v -> SeedCrackerClient.settings().slimeChunks = v));
		trackButton = Button.builder(Component.literal(""), b -> toggleTracked()).bounds(width - 150, TOP - 2, 140, 16).build();
		addRenderableWidget(trackButton);
		refilter();
	}

	private Button toggle(int x, int y, int w, String name, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
		Button[] holder = new Button[1];
		holder[0] = Button.builder(Component.literal(name + ": " + (get.getAsBoolean() ? "вкл" : "выкл")), b -> {
			set.accept(!get.getAsBoolean());
			SeedCrackerClient.settings().save();
			b.setMessage(Component.literal(name + ": " + (get.getAsBoolean() ? "вкл" : "выкл")));
		}).bounds(x, y, w, 18).build();
		return holder[0];
	}

	private void buildCategories() {
		categories.clear();
		SeedWorld.Dim dim = SeedCrackerClient.currentDim();
		boolean overworld = dim != SeedWorld.Dim.NETHER;
		categories.add(new Category("found", "Найденные тобой", "найденные found detected observations", 0xFFFFFFFF, null, null));
		for (StructureKind k : StructureKind.values()) {
			if (k.overworld == overworld) {
				categories.add(new Category("s:" + k.id(), SeedCrackerClient.name(k), (k.nameRu + " " + k.nameEn + " " + k.id()).toLowerCase(Locale.ROOT),
						k.color, k, null));
			}
		}
		if (overworld) {
			categories.add(new Category("slime", "Слайм-чанки", "слизни слайм slime chunks", 0xFF7CFC6A, null, null));
		}
		MultiNoiseBiomeSourceParameterList.Preset preset = overworld ? MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD
				: MultiNoiseBiomeSourceParameterList.Preset.NETHER;
		List<String> biomes = new ArrayList<>();
		preset.usedBiomes().forEach(key -> biomes.add(key.identifier().getPath()));
		biomes.sort((a, b) -> SeedCrackerClient.biomeName(a).compareToIgnoreCase(SeedCrackerClient.biomeName(b)));
		for (String b : biomes) {
			String name = SeedCrackerClient.biomeName(b);
			categories.add(new Category("b:" + b, name, (name + " " + b.replace('_', ' ') + " биом biome").toLowerCase(Locale.ROOT), 0xFF7CF2A0, null, b));
		}
	}

	private void refilter() {
		String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
		filtered = new ArrayList<>();
		for (Category c : categories) {
			if (q.isEmpty() || c.label.toLowerCase(Locale.ROOT).contains(q) || c.search.contains(q)) {
				filtered.add(c);
			}
		}
		if (!q.isEmpty() && !filtered.isEmpty() && filtered.stream().noneMatch(c -> c.id.equals(selected))) {
			select(filtered.get(0).id);
		}
	}

	private void select(String id) {
		selected = id;
		lastSelected = id;
		rightScroll = 0;
	}

	private Category selectedCategory() {
		for (Category c : categories) {
			if (c.id.equals(selected)) {
				return c;
			}
		}
		return categories.isEmpty() ? null : categories.get(0);
	}

	private void toggleTracked() {
		Category c = selectedCategory();
		if (c == null || c.kind == null) {
			return;
		}
		Settings st = SeedCrackerClient.settings();
		if (!st.tracked.remove(c.kind.id())) {
			st.tracked.add(c.kind.id());
		}
		st.save();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------------------------------------------------------------- rows

	private List<Row> buildRows(Category c) {
		Minecraft mc = Minecraft.getInstance();
		Session s = SeedCrackerClient.session();
		SeedWorld.Dim dim = SeedCrackerClient.currentDim();
		List<Row> out = new ArrayList<>();
		if (c == null || s == null || mc.player == null || dim == null) {
			return out;
		}
		double px = mc.player.getX();
		double pz = mc.player.getZ();
		Settings settings = SeedCrackerClient.settings();
		Session.Target target = s.target();
		if (c.id.equals("found")) {
			for (Session.Obs o : s.observations()) {
				StructureKind k = o.kindEnum();
				int color = k != null ? k.color : 0xFFFFFFFF;
				double d = Math.hypot(o.x - px, o.z - pz);
				String sub = o.x + " " + o.y + " " + o.z + " · " + (o.auto ? "сам" : "вручную") + " · #" + o.id + " (ПКМ удалить)";
				out.add(new Row(o.describe() + " · " + Targets.distance(d), sub, color, () -> setTarget(Targets.point(o.x, o.y, o.z, dim, o.describe())),
						() -> {
							s.removeObservation(o.id);
							SeedCrackerClient.solver().request();
						}, false));
			}
			if (out.isEmpty()) {
				out.add(new Row("Пока ничего", "полетай: мод сам замечает деревни, храмы, корабли, порталы, клады, крепости…", 0xFFA8A8B8, null, null, false));
			}
			return out;
		}
		if (s.seed() == null) {
			out.add(new Row("Сид ещё не найден", HudOverlay.statusText(s), 0xFFFFD27A, null, null, false));
			return out;
		}
		if (c.kind != null) {
			List<SeedWorld.Found> found = SeedCrackerClient.locator().structures(dim, c.kind, px, pz, settings.radius);
			if (found == null) {
				out.add(new Row("Ищу…", "радиус " + settings.radius + " блоков", 0xFFA8A8B8, null, null, false));
				return out;
			}
			if (found.isEmpty()) {
				out.add(new Row("В радиусе " + settings.radius + " нет", "/sc radius <блоки> чтобы искать дальше", 0xFFA8A8B8, null, null, false));
			}
			for (SeedWorld.Found f : found) {
				if (out.size() >= 60) {
					break;
				}
				boolean visited = s.isVisited(f.key());
				if (visited && settings.hideVisited) {
					continue;
				}
				double d = f.distanceTo(px, pz);
				boolean isTarget = target != null && target.x == f.x() && target.z == f.z();
				String title = Targets.label(f) + " · " + Targets.distance(d) + " " + Targets.compass(f.x() - px, f.z() - pz) + (visited ? " · был" : "");
				String sub = f.x() + " " + f.y() + " " + f.z() + "  (ЛКМ вести, ПКМ отметить посещённым)";
				out.add(new Row(title, sub, visited ? 0xFF8A8A8A : c.kind.color, () -> setTarget(Targets.of(f, dim)),
						() -> s.setVisited(f.key(), !visited), isTarget));
			}
			return out;
		}
		if (c.biome != null) {
			List<BlockPos> spots = SeedCrackerClient.locator().biomes(dim, c.biome, px, pz, Math.max(settings.radius, 6000));
			if (spots == null) {
				out.add(new Row("Ищу…", "", 0xFFA8A8B8, null, null, false));
				return out;
			}
			if (spots.isEmpty()) {
				out.add(new Row("Рядом не нашлось", "", 0xFFA8A8B8, null, null, false));
			}
			for (BlockPos p : spots) {
				double d = Math.hypot(p.getX() - px, p.getZ() - pz);
				out.add(new Row(c.label + " · " + Targets.distance(d) + " " + Targets.compass(p.getX() - px, p.getZ() - pz),
						p.getX() + " " + p.getY() + " " + p.getZ(), c.color, () -> setTarget(Targets.biome(c.biome, p, dim)), null, false));
			}
			return out;
		}
		if (c.id.equals("slime")) {
			int pcx = (int) Math.floor(px) >> 4;
			int pcz = (int) Math.floor(pz) >> 4;
			List<int[]> chunks = new ArrayList<>();
			for (int dx = -24; dx <= 24; dx++) {
				for (int dz = -24; dz <= 24; dz++) {
					if (SeedWorld.isSlimeChunk(s.seed(), pcx + dx, pcz + dz)) {
						chunks.add(new int[] {pcx + dx, pcz + dz});
					}
				}
			}
			chunks.sort((a, b) -> Double.compare(Math.hypot(a[0] * 16 + 8 - px, a[1] * 16 + 8 - pz), Math.hypot(b[0] * 16 + 8 - px, b[1] * 16 + 8 - pz)));
			for (int[] ch : chunks.subList(0, Math.min(30, chunks.size()))) {
				int x = ch[0] * 16 + 8;
				int z = ch[1] * 16 + 8;
				out.add(new Row("Чанк " + ch[0] + " " + ch[1] + " · " + Targets.distance(Math.hypot(x - px, z - pz)), "слизни спавнятся ниже y 40",
						c.color, () -> setTarget(Targets.point(x, 40, z, dim, "Слайм-чанк")), null, false));
			}
		}
		return out;
	}

	private void setTarget(Session.Target t) {
		Session s = SeedCrackerClient.session();
		if (s != null) {
			s.setTarget(t);
			SeedCrackerClient.message(Component.literal("[SC] Цель: " + t.label + " · " + t.x + " " + t.y + " " + t.z));
		}
		onClose();
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0xC40A0B12);
		Session s = SeedCrackerClient.session();
		String status = s == null ? "" : HudOverlay.statusText(s);
		g.text(font, "Seed Cracker", 10, 7, 0xFFFFFFFF, true);
		g.text(font, status, 10 + font.width("Seed Cracker") + 10, 7, s != null && s.seed() != null ? 0xFF9CF29C : 0xFFFFD27A, true);

		// Left: categories.
		int listTop = TOP;
		int listBottom = height - 30;
		g.fill(8, listTop - 2, 10 + LEFT_W + 2, listBottom, 0x60000000);
		g.enableScissor(8, listTop - 2, 10 + LEFT_W + 2, listBottom);
		int y = listTop - (int) leftScroll;
		for (Category c : filtered) {
			if (y + ROW > listTop - 2 && y < listBottom) {
				boolean sel = c.id.equals(selected);
				boolean hover = mouseX >= 10 && mouseX < 10 + LEFT_W && mouseY >= y && mouseY < y + ROW;
				if (sel || hover) {
					g.fill(10, y, 10 + LEFT_W, y + ROW, sel ? 0x50FFFFFF : 0x28FFFFFF);
				}
				g.fill(14, y + 4, 20, y + 10, c.color);
				String label = c.label;
				if (c.kind != null && SeedCrackerClient.settings().tracked.contains(c.kind.id())) {
					label += "  ◉";
				}
				g.text(font, label, 25, y + 3, sel ? 0xFFFFFFFF : 0xFFD8D8E0, false);
			}
			y += ROW;
		}
		g.disableScissor();

		// Right: results of the selected category.
		Category c = selectedCategory();
		rows = buildRows(c);
		int rx = 10 + LEFT_W + 12;
		int rw = width - rx - 10;
		g.fill(rx - 2, listTop - 2, width - 8, listBottom, 0x60000000);
		if (c != null) {
			g.text(font, c.label, rx + 4, listTop + 2, c.color, true);
		}
		boolean trackable = c != null && c.kind != null;
		trackButton.visible = trackable;
		if (trackable) {
			boolean on = SeedCrackerClient.settings().tracked.contains(c.kind.id());
			trackButton.setMessage(Component.literal(on ? "◉ Показываю в мире" : "○ Показывать в мире"));
		}
		int resultsTop = listTop + 18;
		g.enableScissor(rx - 2, resultsTop, width - 8, listBottom);
		y = resultsTop - (int) rightScroll;
		for (Row r : rows) {
			if (y + RESULT_ROW > resultsTop && y < listBottom) {
				boolean hover = r.click != null && mouseX >= rx && mouseX < rx + rw && mouseY >= y && mouseY < y + RESULT_ROW;
				if (hover || r.highlighted) {
					g.fill(rx, y, rx + rw, y + RESULT_ROW - 2, r.highlighted ? 0x4060F0FF : 0x30FFFFFF);
				}
				g.fill(rx, y, rx + 2, y + RESULT_ROW - 2, r.color);
				g.text(font, r.title, rx + 6, y + 2, r.color, false);
				g.text(font, r.sub, rx + 6, y + 12, 0xFF9A9AA8, false);
			}
			y += RESULT_ROW;
		}
		g.disableScissor();
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		int listBottom = height - 30;
		if (mx >= 10 && mx < 10 + LEFT_W && my >= TOP && my < listBottom) {
			int index = (int) ((my - TOP + leftScroll) / ROW);
			if (index >= 0 && index < filtered.size()) {
				select(filtered.get(index).id);
				return true;
			}
		}
		int rx = 10 + LEFT_W + 12;
		int resultsTop = TOP + 18;
		if (mx >= rx && mx < width - 10 && my >= resultsTop && my < listBottom) {
			int index = (int) ((my - resultsTop + rightScroll) / RESULT_ROW);
			if (index >= 0 && index < rows.size()) {
				Row r = rows.get(index);
				Runnable action = event.button() == 1 ? r.rightClick : r.click;
				if (action != null) {
					action.run();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX < 10 + LEFT_W + 6) {
			leftScroll = clamp(leftScroll - scrollY * ROW * 3, filtered.size() * ROW - (height - 30 - TOP));
		} else {
			rightScroll = clamp(rightScroll - scrollY * RESULT_ROW * 2, rows.size() * RESULT_ROW - (height - 30 - TOP - 18));
		}
		return true;
	}

	private static double clamp(double v, double max) {
		return Math.max(0, Math.min(Math.max(0, max), v));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && !filtered.isEmpty()) {
			Category c = selectedCategory();
			List<Row> r = buildRows(c);
			if (!r.isEmpty() && r.get(0).click != null) {
				r.get(0).click.run();
				return true;
			}
		}
		if (!search.isFocused() && SeedCrackerClient.isLocatorKey(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}
}
