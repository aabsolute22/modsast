package io.github.aabsolute22.seedcracker.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import io.github.aabsolute22.seedcracker.core.StructureKind;
import io.github.aabsolute22.seedcracker.world.ManualPlacement;
import io.github.aabsolute22.seedcracker.world.SeedWorld;

/**
 * Adding a structure by hand: pick the type on the left, stand in it (or type its coordinates) and see right away
 * which start chunk that gives before adding it.
 */
public final class AddScreen extends Screen {
	private static final int ROW = 14;
	private static final int LEFT_W = 170;
	private static final int TOP = 30;

	private static StructureKind lastKind;

	private final Screen parent;
	private final List<StructureKind> kinds = new ArrayList<>();
	private StructureKind selected;
	private EditBox xBox;
	private EditBox zBox;
	private Button addButton;
	private ManualAdd.Plan plan;
	private int dirty = 1;
	private String done;

	public AddScreen(Screen parent) {
		this(parent, null);
	}

	public AddScreen(Screen parent, StructureKind kind) {
		super(Component.literal("Добавить структуру"));
		this.parent = parent;
		this.selected = kind;
	}

	@Override
	protected void init() {
		kinds.clear();
		SeedWorld.Dim dim = SeedCrackerClient.currentDim();
		for (StructureKind k : StructureKind.values()) {
			if (ManualPlacement.useful(k) && (dim == null || k.overworld == dim.overworld())) {
				kinds.add(k);
			}
		}
		if (selected == null) {
			selected = lastKind != null && kinds.contains(lastKind) ? lastKind : kinds.isEmpty() ? null : kinds.get(0);
		}
		int rx = 10 + LEFT_W + 14;
		BlockPos p = playerPos();
		xBox = coordBox(rx + 14, TOP + 52, xBox != null ? xBox.getValue() : Integer.toString(p.getX()));
		zBox = coordBox(rx + 104, TOP + 52, zBox != null ? zBox.getValue() : Integer.toString(p.getZ()));
		addRenderableWidget(Button.builder(Component.literal("Где я стою"), b -> {
			BlockPos q = playerPos();
			xBox.setValue(Integer.toString(q.getX()));
			zBox.setValue(Integer.toString(q.getZ()));
		}).bounds(rx + 184, TOP + 51, 90, 18).build());
		addButton = addRenderableWidget(Button.builder(Component.literal("Добавить"), b -> add()).bounds(rx, height - 28, 120, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Назад"), b -> onClose()).bounds(rx + 126, height - 28, 90, 20).build());
	}

	private EditBox coordBox(int x, int y, String value) {
		EditBox box = new EditBox(font, x, y, 76, 16, Component.literal("coord"));
		box.setMaxLength(9);
		box.setFilter(t -> t.isEmpty() || t.matches("-?\\d*"));
		box.setValue(value);
		box.setResponder(t -> dirty = 1);
		addRenderableWidget(box);
		return box;
	}

	private static BlockPos playerPos() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? BlockPos.ZERO : mc.player.blockPosition();
	}

	@Override
	public void tick() {
		// Looking for the start in the blocks takes a moment; do it once the typing stops.
		if (dirty > 0 && ++dirty > 6) {
			dirty = 0;
			replan();
		}
	}

	private void replan() {
		plan = null;
		if (selected == null) {
			return;
		}
		Integer x = parse(xBox.getValue());
		Integer z = parse(zBox.getValue());
		if (x == null || z == null) {
			return;
		}
		BlockPos p = playerPos();
		boolean here = x == p.getX() && z == p.getZ();
		plan = ManualAdd.plan(selected, x, here ? p.getY() : Integer.MIN_VALUE, z, here);
	}

	private static Integer parse(String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private void add() {
		Session s = SeedCrackerClient.session();
		if (dirty > 0) {
			dirty = 0;
			replan();
		}
		if (s == null || plan == null || !plan.ok()) {
			return;
		}
		Session.Obs o = ManualAdd.commit(s, plan);
		SeedCrackerClient.message(ManualAdd.feedback(plan, o));
		done = o == null ? "Эта структура уже есть" : "Добавил #" + o.id + ". Можно добавить ещё";
	}

	private void select(StructureKind k) {
		selected = k;
		lastKind = k;
		done = null;
		dirty = 1;
	}

	@Override
	public void onClose() {
		if (parent == null) {
			super.onClose();
		} else {
			Minecraft.getInstance().setScreenAndShow(parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0xC40A0B12);
		g.text(font, "Добавить структуру вручную", 10, 9, 0xFFFFFFFF, true);
		Session s = SeedCrackerClient.session();
		if (s != null) {
			g.text(font, HudOverlay.statusText(s), 10 + font.width("Добавить структуру вручную") + 12, 9,
					s.seed() != null ? 0xFF9CF29C : 0xFFFFD27A, true);
		}

		int listBottom = height - 10;
		g.fill(8, TOP - 2, 10 + LEFT_W + 2, listBottom, 0x60000000);
		int y = TOP;
		for (StructureKind k : kinds) {
			if (y + ROW > listBottom) {
				break;
			}
			boolean sel = k == selected;
			boolean hover = mouseX >= 10 && mouseX < 10 + LEFT_W && mouseY >= y && mouseY < y + ROW;
			if (sel || hover) {
				g.fill(10, y, 10 + LEFT_W, y + ROW, sel ? 0x50FFFFFF : 0x25FFFFFF);
			}
			g.fill(14, y + 4, 20, y + 10, k.color);
			g.text(font, SeedCrackerClient.name(k), 25, y + 3, sel ? 0xFFFFFFFF : 0xFFD8D8E0, false);
			y += ROW;
		}

		int rx = 10 + LEFT_W + 14;
		int rw = width - rx - 10;
		if (selected != null) {
			g.text(font, SeedCrackerClient.name(selected), rx, TOP, selected.color, true);
			int hy = TOP + 14;
			for (String line : wrap("Как добавить: " + ManualPlacement.hint(selected) + ", потом «Добавить».", rw)) {
				g.text(font, line, rx, hy, 0xFFC8C8D0, false);
				hy += 10;
			}
		}
		g.text(font, "X", rx + 4, TOP + 56, 0xFFA0A0A8, false);
		g.text(font, "Z", rx + 94, TOP + 56, 0xFFA0A0A8, false);

		int py = TOP + 80;
		if (plan == null) {
			g.text(font, dirty > 0 ? "Смотрю…" : "Введи координаты", rx, py, 0xFFA0A0A8, false);
		} else {
			int color = !plan.ok() ? 0xFFFF7A7A : plan.exact() ? 0xFF9CF29C : 0xFFFFD27A;
			for (String line : wrap(plan.ok() ? capital(plan.summary()) : plan.error(), rw)) {
				g.text(font, line, rx, py, color, false);
				py += 11;
			}
			py += 4;
			String explain;
			if (!plan.ok()) {
				explain = "";
			} else if (plan.fromBlocks()) {
				explain = "Стартовый кусок виден в прогруженных чанках, это точно.";
			} else if (plan.exact()) {
				explain = "По положению точки. Ошибиться можно, только если стоишь не там, где сказано выше.";
			} else {
				explain = "Точнее не сказать: мод переберёт эти чанки по очереди. Встань ближе к указанному месту, чтобы остался один.";
			}
			for (String line : wrap(explain, rw)) {
				g.text(font, line, rx, py, 0xFF9A9AA8, false);
				py += 10;
			}
			if (s != null && s.seed() != null) {
				py += 6;
				g.text(font, "Сид уже найден, новые находки ему не нужны.", rx, py, 0xFF9A9AA8, false);
			}
		}
		if (done != null) {
			g.text(font, done, rx, height - 42, 0xFF9CF29C, false);
		}
		addButton.active = plan != null && plan.ok() && dirty == 0;
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	private static String capital(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** Greedy word wrap to a pixel width. */
	private List<String> wrap(String text, int maxWidth) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (font.width(candidate) > maxWidth && !line.isEmpty()) {
				out.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (!line.isEmpty()) {
			out.add(line.toString());
		}
		return out;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (mx >= 10 && mx < 10 + LEFT_W && my >= TOP) {
			int index = (int) ((my - TOP) / ROW);
			if (index >= 0 && index < kinds.size()) {
				select(kinds.get(index));
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}
}
