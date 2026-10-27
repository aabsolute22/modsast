package io.github.aabsolute22.endcityfinder.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import io.github.aabsolute22.endcityfinder.core.EndCity;

/**
 * In-world markers drawn with vanilla gizmos: beams and outlines for close cities, direction markers at a fixed
 * distance for far ones (so they are never clipped by the far plane), and an arrow to the target city.
 */
public final class WorldMarkers {
	private static final int MAX_MARKERS = 40;
	private static final int MAX_FAR_MARKERS = 16;
	private static final int MAX_FAR_LABELS = 6;
	private static final double FAR_MARKER_DISTANCE = 48.0;

	private WorldMarkers() {
	}

	private static int color(FinderSession s, EndCity city, EndCity target) {
		if (target != null && city.key() == target.key()) {
			return HudOverlay.COLOR_TARGET;
		}
		if (s.isVisited(city)) {
			return HudOverlay.COLOR_VISITED;
		}
		return city.ship() ? HudOverlay.COLOR_SHIP : HudOverlay.COLOR_CITY;
	}

	private static TextGizmo.Style label(int color, double distance) {
		return TextGizmo.Style.forColorAndCentered(color).withScale((float) (TextGizmo.Style.DEFAULT_SCALE * Math.max(1.0, distance / 6.0)));
	}

	static void render(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		FinderSession s = EndCityFinderClient.session();
		Settings settings = EndCityFinderClient.settings();
		if (s == null || mc.level == null || mc.player == null || mc.level.dimension() != Level.END) {
			return;
		}
		if (!settings.worldMarkers && !settings.arrow) {
			return;
		}
		Vec3 cam = context.levelState().cameraRenderState.pos;
		EndCity target = s.target(cam.x, cam.z, settings);
		List<EndCity> cities = new ArrayList<>(s.predicted());
		cities.sort(Comparator.comparingDouble(c -> Math.hypot(c.x() - cam.x, c.z() - cam.z)));
		// Gizmos past the render distance are not drawn, so only cities well inside it get the full treatment.
		double nearLimit = Math.min(settings.nearDistance, mc.options.getEffectiveRenderDistance() * 16 * 0.75);
		try (Gizmos.TemporaryCollection ignored = context.levelRenderer().collectPerFrameRenderThreadGizmos()) {
			if (settings.worldMarkers) {
				int far = 0;
				for (int i = 0; i < Math.min(MAX_MARKERS, cities.size()); i++) {
					EndCity city = cities.get(i);
					boolean isTarget = target != null && city.key() == target.key();
					if (Math.hypot(city.x() - cam.x, city.z() - cam.z) < nearLimit) {
						drawNearCity(s, city, target, cam);
					} else if (isTarget || far < MAX_FAR_MARKERS) {
						// Only the closest few far cities get a label, otherwise the horizon turns into a word cloud.
						drawFarCity(s, city, target, cam, isTarget || far < MAX_FAR_LABELS, far);
						far++;
					}
				}
			}
			if (settings.arrow && target != null) {
				drawArrow(mc, target, cam);
			}
		}
	}

	private static String text(FinderSession s, EndCity city, double distance) {
		return (city.ship() ? "Город с кораблём · " : "Город · ") + (int) distance + " м" + (s.isVisited(city) ? " · посещён" : "");
	}

	private static void drawNearCity(FinderSession s, EndCity city, EndCity target, Vec3 cam) {
		int color = color(s, city, target);
		double cx = city.x() + 0.5;
		double cz = city.z() + 0.5;
		double distance = Math.hypot(cx - cam.x, cz - cam.z);
		// Beam limited to the camera's height band so its ends stay within render distance.
		double bottom = Math.max(city.y(), cam.y - 64);
		double top = Math.max(bottom + 1, Math.min(320, cam.y + 64));
		Gizmos.line(new Vec3(cx, bottom, cz), new Vec3(cx, top, cz), color, 3.0f).setAlwaysOnTop();
		Gizmos.cuboid(new AABB(city.minX(), city.y(), city.minZ(), city.maxX() + 1, city.maxY() + 1, city.maxZ() + 1), GizmoStyle.stroke(color, 2.0f));
		Vec3 labelPos = new Vec3(cx, city.maxY() + 6, cz);
		Gizmos.billboardText(text(s, city, distance), labelPos, label(color, labelPos.distanceTo(cam))).setAlwaysOnTop();
		if (city.ship()) {
			Vec3 ship = new Vec3(city.shipX() + 0.5, city.shipY(), city.shipZ() + 0.5);
			Gizmos.cuboid(new AABB(ship.x - 1.5, ship.y - 1.5, ship.z - 1.5, ship.x + 1.5, ship.y + 1.5, ship.z + 1.5),
					GizmoStyle.strokeAndFill(HudOverlay.COLOR_SHIP, 2.0f, 0x60FFC94A)).setAlwaysOnTop();
			Gizmos.billboardText("корабль, элитры", ship.add(0, 3, 0), label(HudOverlay.COLOR_SHIP, ship.distanceTo(cam))).setAlwaysOnTop();
		}
	}

	/** A short pillar (plus label) at a fixed distance in the city's direction, so it is never clipped. */
	private static void drawFarCity(FinderSession s, EndCity city, EndCity target, Vec3 cam, boolean withLabel, int index) {
		int color = color(s, city, target);
		double cx = city.x() + 0.5;
		double cz = city.z() + 0.5;
		// At eye level, so far cities line up along the horizon instead of drifting off the top of the screen.
		Vec3 aim = new Vec3(cx, cam.y, cz);
		Vec3 p = cam.add(aim.subtract(cam).normalize().scale(FAR_MARKER_DISTANCE));
		Gizmos.line(p.add(0, -4, 0), p.add(0, 4, 0), color, 4.0f).setAlwaysOnTop();
		if (withLabel) {
			double distance = Math.hypot(cx - cam.x, cz - cam.z);
			Gizmos.billboardText(text(s, city, distance), p.add(0, 5.5 + (index % 3) * 1.4, 0), label(color, FAR_MARKER_DISTANCE)).setAlwaysOnTop();
		}
	}

	/** A 3D arrow floating in front of the player, pointing at the target city. */
	private static void drawArrow(Minecraft mc, EndCity target, Vec3 cam) {
		Vec3 look = mc.player.getViewVector(1.0f);
		double tx = target.x() + 0.5 - cam.x;
		double tz = target.z() + 0.5 - cam.z;
		double length = Math.hypot(tx, tz);
		if (length < 1.0) {
			return;
		}
		tx /= length;
		tz /= length;
		// Just below the crosshair wherever the player looks.
		Vec3 base = cam.add(look.scale(3.0)).add(0, -0.7, 0);
		Vec3 tail = base.add(-tx * 0.6, 0, -tz * 0.6);
		Vec3 tip = base.add(tx * 0.6, 0, tz * 0.6);
		int color = target.ship() ? HudOverlay.COLOR_SHIP : HudOverlay.COLOR_TARGET;
		Gizmos.arrow(tail, tip, color, 5.0f).setAlwaysOnTop();
		Gizmos.billboardText((int) length + " м", tip.add(0, 0.25, 0), label(color, 2.5)).setAlwaysOnTop();
	}
}
