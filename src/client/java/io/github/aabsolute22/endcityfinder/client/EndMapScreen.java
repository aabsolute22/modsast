package io.github.aabsolute22.endcityfinder.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import io.github.aabsolute22.endcityfinder.core.EndBiome;
import io.github.aabsolute22.endcityfinder.core.EndCity;
import io.github.aabsolute22.endcityfinder.core.EndIslands;
import io.github.aabsolute22.endcityfinder.core.EndWorld;
import io.github.aabsolute22.endcityfinder.core.Placement;

/** Full-screen top-down map of the End: biomes, predicted and found cities, the player and the target. */
public final class EndMapScreen extends Screen {
	private static final int CELL = 4;
	private static final int BIOME_BUDGET_PER_FRAME = 2500;

	private double centerX;
	private double centerZ;
	/** Blocks per screen pixel. */
	private double scale = 8.0;
	private boolean centered;
	private final Map<Long, EndBiome> biomeCache = new HashMap<>();
	private EndIslands cachedIslands;

	public EndMapScreen() {
		super(Component.literal("End City Finder"));
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

	private int screenX(double blockX) {
		return (int) Math.round(width / 2.0 + (blockX - centerX) / scale);
	}

	private int screenZ(double blockZ) {
		return (int) Math.round(height / 2.0 + (blockZ - centerZ) / scale);
	}

	private double blockX(double sx) {
		return centerX + (sx - width / 2.0) * scale;
	}

	private double blockZ(double sy) {
		return centerZ + (sy - height / 2.0) * scale;
	}

	private static int biomeColor(EndBiome biome) {
		return switch (biome) {
			case THE_END -> 0xFF4D4766;
			case HIGHLANDS -> 0xFFB9B07A;
			case MIDLANDS -> 0xFF8A8458;
			case BARRENS -> 0xFF4A4535;
			case SMALL_ISLANDS -> 0xFF221F2B;
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0xFF0B0910);
		Minecraft mc = Minecraft.getInstance();
		FinderSession s = EndCityFinderClient.session();
		if (s == null) {
			super.extractRenderState(graphics, mouseX, mouseY, delta);
			return;
		}
		List<EndWorld> worlds = s.worlds();
		EndWorld world = worlds.isEmpty() ? null : worlds.get(0);
		if (world != null) {
			drawBiomes(graphics, world.islands());
		}
		drawGrid(graphics);
		Settings settings = EndCityFinderClient.settings();
		double px = mc.player != null ? mc.player.getX() : centerX;
		double pz = mc.player != null ? mc.player.getZ() : centerZ;
		EndCity target = s.target(px, pz, settings);
		EndCity hovered = null;
		for (EndCity city : s.shown(settings)) {
			int x = screenX(city.x());
			int z = screenZ(city.z());
			if (x < -8 || z < -8 || x > width + 8 || z > height + 8) {
				continue;
			}
			int color = s.isVisited(city) ? HudOverlay.COLOR_VISITED : city.ship() ? HudOverlay.COLOR_SHIP : HudOverlay.COLOR_CITY;
			int r = city.ship() ? 3 : 2;
			if (target != null && target.key() == city.key()) {
				graphics.outline(x - r - 2, z - r - 2, 2 * r + 5, 2 * r + 5, HudOverlay.COLOR_TARGET);
			}
			graphics.fill(x - r - 1, z - r - 1, x + r + 2, z + r + 2, 0xFF000000);
			graphics.fill(x - r, z - r, x + r + 1, z + r + 1, color);
			if (Math.abs(mouseX - x) <= r + 2 && Math.abs(mouseY - z) <= r + 2) {
				hovered = city;
			}
		}
		for (FinderSession.ObservedCity observed : s.cities()) {
			int x = screenX(observed.x);
			int z = screenZ(observed.z);
			graphics.outline(x - 5, z - 5, 11, 11, observed.exact ? 0xFFFFFFFF : 0xFF9A9A9A);
		}
		if (mc.player != null) {
			int x = screenX(px);
			int z = screenZ(pz);
			HudOverlay.drawArrow(graphics, x, z, mc.player.getYRot() + 180.0f, 0xFF000000, 1);
			HudOverlay.drawArrow(graphics, x, z, mc.player.getYRot() + 180.0f, 0xFFFFFFFF, 0);
		}
		graphics.fill(0, 0, width, 24, 0xB0000000);
		graphics.text(font, "End City Finder · " + HudOverlay.statusText(s), 6, 4, 0xFFE6D9FF, true);
		graphics.text(font, String.format("%.0f блоков/пиксель · колесо: зум · ЛКМ тащить · ПКМ по городу: цель · %d %d",
				scale, (int) blockX(mouseX), (int) blockZ(mouseY)), 6, 14, 0xFFA0A0A0, true);
		int legendY = height - 12;
		graphics.fill(0, legendY - 4, width, height, 0xB0000000);
		graphics.text(font, "■ город", 6, legendY, HudOverlay.COLOR_CITY, true);
		graphics.text(font, "■ с кораблём", 56, legendY, HudOverlay.COLOR_SHIP, true);
		graphics.text(font, "■ посещён", 136, legendY, HudOverlay.COLOR_VISITED, true);
		graphics.text(font, "□ найден тобой", 200, legendY, 0xFFFFFFFF, true);
		if (hovered != null) {
			int d = (int) Math.hypot(hovered.x() - px, hovered.z() - pz);
			String tip = hovered.x() + " " + hovered.y() + " " + hovered.z() + " · " + d + " м" + (hovered.ship() ? " · корабль" : "")
					+ (s.isVisited(hovered) ? " · посещён" : "");
			graphics.setTooltipForNextFrame(font, Component.literal(tip), mouseX, mouseY);
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	private void drawGrid(GuiGraphicsExtractor graphics) {
		// Region grid lines (one End city per 320x320 region) when zoomed in enough to be useful.
		int span = Placement.END_CITIES.spacing() * 16;
		if (span / scale < 24) {
			return;
		}
		double left = blockX(0);
		double top = blockZ(0);
		for (long gx = (long) Math.floor(left / span) * span; screenX(gx) < width; gx += span) {
			graphics.verticalLine(screenX(gx), 24, height, 0x20FFFFFF);
		}
		for (long gz = (long) Math.floor(top / span) * span; screenZ(gz) < height; gz += span) {
			graphics.horizontalLine(0, width, screenZ(gz), 0x20FFFFFF);
		}
	}

	private void drawBiomes(GuiGraphicsExtractor graphics, EndIslands islands) {
		if (islands != cachedIslands) {
			biomeCache.clear();
			cachedIslands = islands;
		}
		int budget = BIOME_BUDGET_PER_FRAME;
		double chunksPerCell = Math.max(1.0, CELL * scale / 16.0);
		int step = (int) Math.ceil(chunksPerCell);
		for (int sy = 24; sy < height; sy += CELL) {
			for (int sx = 0; sx < width; sx += CELL) {
				int chunkX = Math.floorDiv(Math.floorDiv((int) blockX(sx + CELL / 2.0), 16), step) * step;
				int chunkZ = Math.floorDiv(Math.floorDiv((int) blockZ(sy + CELL / 2.0), 16), step) * step;
				long key = Placement.pack(chunkX, chunkZ);
				EndBiome biome = biomeCache.get(key);
				if (biome == null) {
					if (budget-- <= 0) {
						continue;
					}
					biome = islands.biomeOfChunk(chunkX, chunkZ);
					if (biomeCache.size() > 200_000) {
						biomeCache.clear();
					}
					biomeCache.put(key, biome);
				}
				graphics.fill(sx, sy, sx + CELL, sy + CELL, biomeColor(biome));
			}
		}
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
		double bx = blockX(mouseX);
		double bz = blockZ(mouseY);
		scale = Math.max(0.5, Math.min(128.0, scale * (scrollY > 0 ? 0.8 : 1.25)));
		centerX = bx - (mouseX - width / 2.0) * scale;
		centerZ = bz - (mouseY - height / 2.0) * scale;
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		FinderSession s = EndCityFinderClient.session();
		if (s != null && event.button() == 1) {
			for (EndCity city : s.shown(EndCityFinderClient.settings())) {
				if (Math.abs(event.x() - screenX(city.x())) <= 5 && Math.abs(event.y() - screenZ(city.z())) <= 5) {
					s.setTarget(city);
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (EndCityFinderClient.isMapKey(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}
}
