package io.github.aabsolute22.seedcracker.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import io.github.aabsolute22.seedcracker.core.StructureKind;
import io.github.aabsolute22.seedcracker.world.SeedWorld;

/**
 * Lines to structures, drawn with vanilla gizmos through terrain: a soft arc from the player's chest to the target
 * with a colour gradient, light pulses running towards the target and a glow halo; at the target a beacon beam, a
 * breathing ring and a label. Targets beyond the render distance get the arc to a marker on the horizon instead.
 */
public final class WorldRender {
	private static final double PULSE_SPACING = 7.0;
	private static final double PULSE_SPEED = 9.0;

	private WorldRender() {
	}

	static void render(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		Session s = SeedCrackerClient.session();
		Settings settings = SeedCrackerClient.settings();
		SeedWorld.Dim dim = SeedCrackerClient.currentDim();
		if (s == null || mc.level == null || mc.player == null || dim == null) {
			return;
		}
		Vec3 cam = context.levelState().cameraRenderState.pos;
		// Lines leave from the player, which is the camera only in first person.
		Vec3 eye = mc.options.getCameraType().isFirstPerson() ? cam : mc.player.getEyePosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		double time = (System.currentTimeMillis() % 3_600_000L) / 1000.0;
		double reach = Math.max(48, Math.min(240, mc.options.getEffectiveRenderDistance() * 16 * 0.7));
		try (Gizmos.TemporaryCollection ignored = context.levelRenderer().collectPerFrameRenderThreadGizmos()) {
			Session.Target target = s.target();
			if (settings.lines && target != null && Targets.dimId(dim).equals(target.dim)) {
				Vec3 to = new Vec3(target.x + 0.5, target.y + 1.0, target.z + 0.5);
				drawPath(cam, eye, to, Targets.color(target), target.label, true, reach, time);
			}
			if (settings.markers && s.seed() != null) {
				drawTracked(mc, s, settings, dim, cam, eye, reach, time, target);
			}
			if (settings.markers && s.seed() == null) {
				drawObservations(s, dim, cam, reach);
			}
			if (settings.slimeChunks && s.seed() != null && dim == SeedWorld.Dim.OVERWORLD) {
				drawSlimeChunks(s.seed(), cam);
			}
		}
	}

	private static void drawTracked(Minecraft mc, Session s, Settings settings, SeedWorld.Dim dim, Vec3 cam, Vec3 eye, double reach, double time,
			Session.Target target) {
		Locator locator = SeedCrackerClient.locator();
		for (String id : settings.tracked) {
			StructureKind kind = StructureKind.byId(id);
			if (kind == null || kind.overworld != dim.overworld()) {
				continue;
			}
			List<SeedWorld.Found> found = locator.structures(dim, kind, cam.x, cam.z, settings.radius);
			if (found == null) {
				continue;
			}
			int lines = 0;
			for (SeedWorld.Found f : found) {
				if (settings.hideVisited && s.isVisited(f.key())) {
					continue;
				}
				if (target != null && target.x == f.x() && target.z == f.z()) {
					continue;
				}
				if (lines >= settings.linesPerKind) {
					break;
				}
				lines++;
				int color = s.isVisited(f.key()) ? 0xFF8A8A8A : kind.color;
				Vec3 to = new Vec3(f.x() + 0.5, f.y() + 1.0, f.z() + 0.5);
				drawPath(cam, eye, to, color, Targets.label(f), false, reach, time);
				if (Math.hypot(f.x() - cam.x, f.z() - cam.z) < reach) {
					SeedWorld.Found d = locator.details(dim, f);
					if (d.box() != null) {
						var b = d.box();
						Gizmos.cuboid(new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1),
								GizmoStyle.stroke(withAlpha(color, 0x90), 1.5f));
					}
					for (var extra : d.extras()) {
						Vec3 e = new Vec3(extra.getX() + 0.5, extra.getY() + 0.5, extra.getZ() + 0.5);
						Gizmos.cuboid(new AABB(e.x - 0.6, e.y - 0.6, e.z - 0.6, e.x + 0.6, e.y + 0.6, e.z + 0.6),
								GizmoStyle.strokeAndFill(0xFFFF8A3C, 2f, 0x60FF8A3C)).setAlwaysOnTop();
						Gizmos.billboardText(SeedCrackerClient.russian() ? "спавнер ифритов" : "blaze spawner", e.add(0, 1.4, 0),
								label(0xFFFF8A3C, e.distanceTo(cam))).setAlwaysOnTop();
					}
				}
			}
		}
	}

	/**
	 * Until the seed is known, what was found so far: the start chunk (or every chunk it may be) outlined at the
	 * structure's height, and a label. A wrong one is easy to spot this way.
	 */
	private static void drawObservations(Session s, SeedWorld.Dim dim, Vec3 cam, double reach) {
		String dimId = Targets.dimId(dim);
		for (Session.Obs o : s.observations()) {
			double d = Math.hypot(o.x - cam.x, o.z - cam.z);
			if (!dimId.equals(o.dim) || d > reach + 48) {
				continue;
			}
			StructureKind k = o.kindEnum();
			int color = k != null ? k.color : 0xFFFFFFFF;
			boolean exact = o.candidates.size() == 1;
			for (int[] c : o.candidates) {
				double x0 = c[0] * 16;
				double z0 = c[1] * 16;
				Gizmos.cuboid(new AABB(x0, o.y, z0, x0 + 16, o.y + 0.1, z0 + 16), GizmoStyle.stroke(withAlpha(color, exact ? 220 : 120), exact ? 2.5f : 1.5f))
						.setAlwaysOnTop();
			}
			String text = "#" + o.id + " " + (k != null ? SeedCrackerClient.name(k) : o.kind) + (o.auto ? "" : " · вручную")
					+ (exact ? "" : " · " + o.candidates.size() + " чанка");
			Vec3 at = new Vec3(o.x + 0.5, o.y + 2.5, o.z + 0.5);
			Gizmos.billboardText(text, at, label(color, at.distanceTo(cam))).setAlwaysOnTop();
		}
	}

	/**
	 * Where the arc starts: by the player's right hand, towards the target. Off to the side on purpose: an arc whose
	 * plane goes through the eye would look like a straight stick.
	 */
	private static Vec3 start(Vec3 eye, Vec3 target) {
		Vec3 flat = new Vec3(target.x - eye.x, 0, target.z - eye.z);
		Vec3 dir = flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : flat.normalize();
		Vec3 right = new Vec3(-dir.z, 0, dir.x);
		return eye.add(dir.scale(0.7)).add(right.scale(0.4)).add(0, -0.5, 0);
	}

	/**
	 * The arc itself. Near targets get the full arc plus the beacon; far ones end on a marker {@code reach} blocks away
	 * at a height between the player and the target.
	 */
	private static void drawPath(Vec3 cam, Vec3 eye, Vec3 target, int color, String text, boolean main, double reach, double time) {
		Vec3 a = start(eye, target);
		double flat = Math.hypot(target.x - a.x, target.z - a.z);
		boolean near = flat <= reach;
		Vec3 end;
		if (near) {
			end = target;
		} else {
			double k = reach / flat;
			double y = a.y + (target.y - a.y) * k;
			y = Math.max(cam.y - 24, Math.min(cam.y + 24, y));
			end = new Vec3(a.x + (target.x - a.x) * k, y, a.z + (target.z - a.z) * k);
		}
		double length = a.distanceTo(end);
		if (length < 0.5) {
			return;
		}
		// Quadratic Bezier lifted in the middle: reads as a path, not a laser.
		Vec3 mid = a.add(end).scale(0.5).add(0, Math.min(main ? 26 : 14, length * (main ? 0.16 : 0.1)), 0);
		int segments = (int) Math.max(12, Math.min(72, length / 1.4));
		Vec3 prev = a;
		double travelled = 0;
		float glowWidth = main ? 9f : 5f;
		float coreWidth = main ? 3f : 1.8f;
		for (int i = 1; i <= segments; i++) {
			double t = (double) i / segments;
			double u = 1 - t;
			Vec3 p = a.scale(u * u).add(mid.scale(2 * u * t)).add(end.scale(t * t));
			double seg = prev.distanceTo(p);
			travelled += seg;
			// Light pulses every few blocks, moving towards the target.
			double phase = (travelled - time * PULSE_SPEED) / PULSE_SPACING;
			double pulse = Math.pow(Math.max(0, Math.cos(2 * Math.PI * phase)), 6);
			double fadeIn = Math.min(1.0, travelled / 3.0);
			int base = mix(0xFFFFFFFF, color, 0.35 + 0.65 * t);
			int core = mix(base, 0xFFFFFFFF, pulse * 0.75);
			int coreAlpha = (int) (fadeIn * (150 + 105 * Math.max(t, pulse)));
			int glowAlpha = (int) (fadeIn * (main ? 64 : 36) * (1 + 1.4 * pulse));
			Gizmos.line(prev, p, withAlpha(color, glowAlpha), glowWidth).setAlwaysOnTop();
			Gizmos.line(prev, p, withAlpha(core, coreAlpha), coreWidth).setAlwaysOnTop();
			prev = p;
		}
		double distance = Math.hypot(target.x - cam.x, target.z - cam.z);
		if (near) {
			drawBeacon(target, color, main, time, cam);
		} else {
			drawHorizonMarker(end, cam, color, main, time);
		}
		if (main || near) {
			Vec3 labelPos = near ? target.add(0, 3.2, 0) : end.add(0, 1.6, 0);
			String line = text + " · " + Targets.distance(distance);
			Gizmos.billboardText(line, labelPos, label(color, labelPos.distanceTo(cam) * (main ? 1.0 : 0.85))).setAlwaysOnTop();
		}
	}

	/** Beam into the sky, a breathing ring on the ground and a small diamond at the spot. */
	private static void drawBeacon(Vec3 target, int color, boolean main, double time, Vec3 cam) {
		double top = Math.min(target.y + (main ? 90 : 45), cam.y + 100);
		double bottom = target.y - 1;
		int steps = 12;
		for (int i = 0; i < steps; i++) {
			double y0 = bottom + (top - bottom) * i / steps;
			double y1 = bottom + (top - bottom) * (i + 1) / steps;
			double fade = 1 - (double) i / steps;
			Gizmos.line(new Vec3(target.x, y0, target.z), new Vec3(target.x, y1, target.z), withAlpha(color, (int) (85 * fade)), main ? 11f : 6f).setAlwaysOnTop();
			Gizmos.line(new Vec3(target.x, y0, target.z), new Vec3(target.x, y1, target.z), withAlpha(mix(color, 0xFFFFFFFF, 0.4), (int) (230 * fade)),
					main ? 2.5f : 1.6f).setAlwaysOnTop();
		}
		double breathe = 0.5 + 0.5 * Math.sin(time * 2.4);
		float r1 = (float) (1.6 + 0.6 * breathe);
		float r2 = (float) (3.2 + 1.4 * ((time * 0.7) % 1.0) * 2);
		Gizmos.circle(target.add(0, -0.9, 0), r1, GizmoStyle.stroke(withAlpha(color, 220), 2.5f)).setAlwaysOnTop();
		Gizmos.circle(target.add(0, -0.9, 0), r2, GizmoStyle.stroke(withAlpha(color, (int) (160 * (1 - ((time * 0.7) % 1.0)))), 1.5f)).setAlwaysOnTop();
		drawDiamond(target.add(0, 0.6 + 0.25 * Math.sin(time * 3), 0), cam, color, 0.55);
	}

	/** Far targets: a spinning diamond on the horizon in the target's direction. */
	private static void drawHorizonMarker(Vec3 at, Vec3 cam, int color, boolean main, double time) {
		drawDiamond(at, cam, color, main ? 1.1 : 0.7);
		Gizmos.line(at.add(0, -3.5, 0), at.add(0, 3.5, 0), withAlpha(color, 90), main ? 5f : 3f).setAlwaysOnTop();
	}

	private static void drawDiamond(Vec3 c, Vec3 cam, int color, double size) {
		Vec3 view = c.subtract(cam);
		Vec3 right = new Vec3(-view.z, 0, view.x);
		right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize().scale(size);
		Vec3 up = new Vec3(0, size * 1.3, 0);
		Vec3[] p = {c.add(up), c.add(right), c.subtract(up), c.subtract(right)};
		for (int i = 0; i < 4; i++) {
			Gizmos.line(p[i], p[(i + 1) % 4], withAlpha(color, 70), 6f).setAlwaysOnTop();
			Gizmos.line(p[i], p[(i + 1) % 4], withAlpha(mix(color, 0xFFFFFFFF, 0.3), 255), 2f).setAlwaysOnTop();
		}
	}

	/** Outlines of slime chunks around the player at their feet level. */
	private static void drawSlimeChunks(long seed, Vec3 cam) {
		int pcx = (int) Math.floor(cam.x) >> 4;
		int pcz = (int) Math.floor(cam.z) >> 4;
		double y = Math.floor(cam.y) - 1.6;
		List<int[]> slime = new ArrayList<>();
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				if (SeedWorld.isSlimeChunk(seed, pcx + dx, pcz + dz)) {
					slime.add(new int[] {pcx + dx, pcz + dz});
				}
			}
		}
		for (int[] c : slime) {
			double x0 = c[0] * 16;
			double z0 = c[1] * 16;
			Gizmos.cuboid(new AABB(x0, y, z0, x0 + 16, y + 0.05, z0 + 16), GizmoStyle.strokeAndFill(0xFF7CFC6A, 2f, 0x307CFC6A));
		}
	}

	static TextGizmo.Style label(int color, double distance) {
		return TextGizmo.Style.forColorAndCentered(color).withScale((float) (TextGizmo.Style.DEFAULT_SCALE * Math.max(1.0, distance / 7.0)));
	}

	static int withAlpha(int color, int alpha) {
		return Math.max(0, Math.min(255, alpha)) << 24 | (color & 0xFFFFFF);
	}

	/** Linear blend of two ARGB colours, {@code t} = 0 gives {@code a}. */
	static int mix(int a, int b, double t) {
		t = Math.max(0, Math.min(1, t));
		int ar = a >> 16 & 255;
		int ag = a >> 8 & 255;
		int ab = a & 255;
		int br = b >> 16 & 255;
		int bg = b >> 8 & 255;
		int bb = b & 255;
		int r = (int) (ar + (br - ar) * t);
		int g = (int) (ag + (bg - ag) * t);
		int bl = (int) (ab + (bb - ab) * t);
		return 0xFF000000 | r << 16 | g << 8 | bl;
	}
}
