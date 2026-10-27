package io.github.aabsolute22.seedcracker.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import io.github.aabsolute22.seedcracker.core.StructureKind;
import io.github.aabsolute22.seedcracker.world.Background;
import io.github.aabsolute22.seedcracker.world.SeedWorld;

/** Top-down biome map from the cracked seed with tracked structures, the player and the target. */
public final class MapScreen extends Screen {
	private static final int CELL = 4;
	private static final int BATCH = 900;
	private static final Map<Long, Integer> COLORS = new ConcurrentHashMap<>();
	private static final Map<Long, String> NAMES = new ConcurrentHashMap<>();
	private static final AtomicBoolean WORKING = new AtomicBoolean();
	private static Long colorSeed;

	private double centerX;
	private double centerZ;
	/** Blocks per screen pixel. */
	private double scale = 4.0;
	private boolean centered;
	private final List<Long> missing = new ArrayList<>();

	public MapScreen() {
		super(Component.literal("Seed Cracker map"));
	}

	@Override
	protected void init() {
		Minecraft mc = Minecraft.getInstance();
		if (!centered && mc.player != null) {
			centerX = mc.player.getX();
			centerZ = mc.player.getZ();
			centered = true;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int sx(double blockX) {
		return (int) Math.round(width / 2.0 + (blockX - centerX) / scale);
	}

	private int sz(double blockZ) {
		return (int) Math.round(height / 2.0 + (blockZ - centerZ) / scale);
	}

	private double bx(double screenX) {
		return centerX + (screenX - width / 2.0) * scale;
	}

	private double bz(double screenY) {
		return centerZ + (screenY - height / 2.0) * scale;
	}

	/** Cell size in blocks: a power of two near CELL pixels, so zooming reuses cached cells. */
	private int cellBlocks() {
		int b = 4;
		while (b < CELL * scale) {
			b *= 2;
		}
		return b;
	}

	private static long cellKey(int size, int cx, int cz, SeedWorld.Dim dim) {
		return ((long) Integer.numberOfTrailingZeros(size) << 58) ^ ((long) dim.ordinal() << 57) ^ ((long) (cx & 0x0FFFFFFF) << 28) ^ (cz & 0x0FFFFFFF);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0xFF0B0C12);
		Minecraft mc = Minecraft.getInstance();
		Session s = SeedCrackerClient.session();
		SeedWorld.Dim dim = SeedCrackerClient.currentDim();
		if (s == null || dim == null) {
			super.extractRenderState(g, mouseX, mouseY, delta);
			return;
		}
		Long seed = s.seed();
		if (seed != null) {
			if (!seed.equals(colorSeed)) {
				COLORS.clear();
				NAMES.clear();
				colorSeed = seed;
			}
			drawBiomes(g, dim);
		} else {
			g.centeredText(font, "Сид ещё не найден · " + HudOverlay.statusText(s), width / 2, height / 2, 0xFFFFD27A);
		}
		double px = mc.player != null ? mc.player.getX() : centerX;
		double pz = mc.player != null ? mc.player.getZ() : centerZ;
		String hovered = null;
		int radius = (int) Math.min(30000, Math.max(width, height) * scale * 0.75);
		Settings settings = SeedCrackerClient.settings();
		List<StructureKind> kinds = new ArrayList<>();
		for (String id : settings.tracked) {
			StructureKind k = StructureKind.byId(id);
			if (k != null && k.overworld == dim.overworld()) {
				kinds.add(k);
			}
		}
		if (kinds.isEmpty()) {
			kinds.add(dim.overworld() ? StructureKind.VILLAGE : StructureKind.FORTRESS);
			kinds.add(dim.overworld() ? StructureKind.STRONGHOLD : StructureKind.BASTION);
		}
		Session.Target target = s.target();
		if (seed != null) {
			for (StructureKind k : kinds) {
				List<SeedWorld.Found> found = SeedCrackerClient.locator().structures(dim, k, centerX, centerZ, radius);
				if (found == null) {
					continue;
				}
				for (SeedWorld.Found f : found) {
					int x = sx(f.x());
					int z = sz(f.z());
					if (x < -6 || z < -6 || x > width + 6 || z > height + 6) {
						continue;
					}
					boolean visited = s.isVisited(f.key());
					int r = 2;
					g.fill(x - r - 1, z - r - 1, x + r + 2, z + r + 2, 0xFF000000);
					g.fill(x - r, z - r, x + r + 1, z + r + 1, visited ? 0xFF7A7A7A : k.color);
					if (Math.abs(mouseX - x) <= r + 2 && Math.abs(mouseY - z) <= r + 2) {
						hovered = Targets.label(f) + " · " + f.x() + " " + f.y() + " " + f.z() + " · " + Targets.distance(f.distanceTo(px, pz))
								+ " · ПКМ: вести";
					}
				}
			}
		}
		for (Session.Obs o : s.observations()) {
			if (o.dim.equals(Targets.dimId(dim))) {
				int x = sx(o.x);
				int z = sz(o.z);
				g.outline(x - 4, z - 4, 9, 9, 0xFFFFFFFF);
			}
		}
		if (target != null && target.dim.equals(Targets.dimId(dim))) {
			int x = sx(target.x);
			int z = sz(target.z);
			g.outline(x - 6, z - 6, 13, 13, Targets.color(target));
			g.outline(x - 7, z - 7, 15, 15, 0xFF000000);
		}
		if (mc.player != null) {
			int x = sx(px);
			int z = sz(pz);
			HudOverlay.drawArrow(g, x, z, mc.player.getYRot() + 180.0f, 0xFF000000, 1);
			HudOverlay.drawArrow(g, x, z, mc.player.getYRot() + 180.0f, 0xFFFFFFFF, 0);
		}
		g.fill(0, 0, width, 24, 0xE8101018);
		StringBuilder legend = new StringBuilder();
		for (StructureKind k : kinds) {
			legend.append(legend.length() == 0 ? "" : ", ").append(SeedCrackerClient.name(k));
		}
		g.text(font, "Карта · " + legend + " (что показывать: B → «Показывать в мире»)", 6, 4, 0xFFE8E8F0, true);
		String biome = seed != null && SeedCrackerClient.locator().worldNow(dim) != null ? cachedBiomeName(dim, mouseX, mouseY) : "";
		g.text(font, String.format("%.0f бл/пикс · колесо зум · ЛКМ тащить · ПКМ по структуре: цель · %d %d %s", scale, (int) bx(mouseX), (int) bz(mouseY), biome),
				6, 14, 0xFFA0A0A0, true);
		if (hovered != null) {
			g.setTooltipForNextFrame(font, Component.literal(hovered), mouseX, mouseY);
		}
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	private String cachedBiomeName(SeedWorld.Dim dim, int mouseX, int mouseY) {
		int size = cellBlocks();
		String id = NAMES.get(cellKey(size, Math.floorDiv((int) Math.floor(bx(mouseX)), size), Math.floorDiv((int) Math.floor(bz(mouseY)), size), dim));
		return id == null ? "" : "· " + SeedCrackerClient.biomeName(id);
	}

	private void drawBiomes(GuiGraphicsExtractor g, SeedWorld.Dim dim) {
		SeedCrackerClient.locator().prepare(dim);
		int size = cellBlocks();
		double cellPx = size / scale;
		int cx0 = Math.floorDiv((int) Math.floor(bx(0)), size);
		int cx1 = Math.floorDiv((int) Math.floor(bx(width)), size);
		int cz0 = Math.floorDiv((int) Math.floor(bz(24)), size);
		int cz1 = Math.floorDiv((int) Math.floor(bz(height)), size);
		missing.clear();
		for (int cz = cz0; cz <= cz1; cz++) {
			for (int cx = cx0; cx <= cx1; cx++) {
				long key = cellKey(size, cx, cz, dim);
				Integer color = COLORS.get(key);
				int x0 = sx(cx * (double) size);
				int z0 = sz(cz * (double) size);
				int x1 = (int) Math.ceil(x0 + cellPx);
				int z1 = (int) Math.ceil(z0 + cellPx);
				if (color == null) {
					// A coarser cell, if known, stands in until the fine one arrives.
					color = coarser(size, cx, cz, dim);
					if (missing.size() < BATCH) {
						missing.add(key);
					}
				}
				if (color != null) {
					g.fill(x0, z0, x1, z1, color);
				}
			}
		}
		if (!missing.isEmpty() && WORKING.compareAndSet(false, true)) {
			int s = size;
			List<int[]> cells = new ArrayList<>();
			for (int cz = cz0; cz <= cz1 && cells.size() < BATCH; cz++) {
				for (int cx = cx0; cx <= cx1 && cells.size() < BATCH; cx++) {
					if (!COLORS.containsKey(cellKey(s, cx, cz, dim))) {
						cells.add(new int[] {cx, cz});
					}
				}
			}
			Background.EXECUTOR.submit(() -> {
				try {
					SeedWorld w = SeedCrackerClient.locator().worldNow(dim);
					if (w == null) {
						return;
					}
					for (int[] c : cells) {
						int x = c[0] * s + s / 2;
						int z = c[1] * s + s / 2;
						String id = w.surfaceBiome(x, z);
						long key = cellKey(s, c[0], c[1], dim);
						COLORS.put(key, BiomeColors.of(id));
						NAMES.put(key, id);
					}
					if (COLORS.size() > 400_000) {
						COLORS.clear();
						NAMES.clear();
					}
				} catch (Throwable t) {
					SeedCrackerClient.LOG.warn("Map biomes failed", t);
				} finally {
					WORKING.set(false);
				}
			});
		}
	}

	private static Integer coarser(int size, int cx, int cz, SeedWorld.Dim dim) {
		for (int s = size * 2, k = 1; s <= size * 16; s *= 2, k++) {
			Integer c = COLORS.get(cellKey(s, cx >> k, cz >> k, dim));
			if (c != null) {
				return c;
			}
		}
		return null;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (event.button() == 0) {
			centerX -= dragX * scale;
			centerZ -= dragY * scale;
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		double x = bx(mouseX);
		double z = bz(mouseY);
		scale = Math.max(0.5, Math.min(256.0, scale * (scrollY > 0 ? 0.8 : 1.25)));
		centerX = x - (mouseX - width / 2.0) * scale;
		centerZ = z - (mouseY - height / 2.0) * scale;
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		Session s = SeedCrackerClient.session();
		SeedWorld.Dim dim = SeedCrackerClient.currentDim();
		if (s != null && dim != null && event.button() == 1 && s.seed() != null) {
			int radius = (int) Math.min(30000, Math.max(width, height) * scale * 0.75);
			SeedWorld.Found best = null;
			double bestD = 8;
			List<StructureKind> kinds = new ArrayList<>();
			for (String id : SeedCrackerClient.settings().tracked) {
				StructureKind k = StructureKind.byId(id);
				if (k != null && k.overworld == dim.overworld()) {
					kinds.add(k);
				}
			}
			if (kinds.isEmpty()) {
				kinds.add(dim.overworld() ? StructureKind.VILLAGE : StructureKind.FORTRESS);
				kinds.add(dim.overworld() ? StructureKind.STRONGHOLD : StructureKind.BASTION);
			}
			for (StructureKind k : kinds) {
				List<SeedWorld.Found> found = SeedCrackerClient.locator().structures(dim, k, centerX, centerZ, radius);
				if (found == null) {
					continue;
				}
				for (SeedWorld.Found f : found) {
					double d = Math.hypot(event.x() - sx(f.x()), event.y() - sz(f.z()));
					if (d < bestD) {
						bestD = d;
						best = f;
					}
				}
			}
			if (best != null) {
				s.setTarget(Targets.of(best, dim));
				SeedCrackerClient.message(Component.literal("[SC] Цель: " + Targets.label(best) + " · " + best.x() + " " + best.y() + " " + best.z()));
			} else {
				int x = (int) bx(event.x());
				int z = (int) bz(event.y());
				s.setTarget(Targets.point(x, 70, z, dim, "Точка на карте"));
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (SeedCrackerClient.isMapKey(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}
}
