package io.github.aabsolute22.seedcracker.client;

import org.joml.Matrix3x2fStack;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import io.github.aabsolute22.seedcracker.world.SeedWorld;

/** Status line (top left) and the compass to the current target (top centre). */
public final class HudOverlay {
	static final int TEXT = 0xFFE8E8F0;
	static final int DIM = 0xFFA8A8B8;

	private HudOverlay() {
	}

	static String statusText(Session s) {
		StringBuilder b = new StringBuilder("SC · ");
		switch (s.phase()) {
			case LOADING -> b.append("загрузка");
			case COLLECTING -> b.append(String.format("структур %d (~%.0f бит) · %s", s.observations().size(), s.informationBits(), s.detail()));
			case CRACKING -> {
				b.append(String.format("перебор %.0f%%", Math.max(0, s.progress()) * 100));
				long eta = s.etaMillis();
				double p = s.progress();
				if (eta > 0 && p > 0.02) {
					b.append(" · осталось ~").append(Solver.duration((long) (eta * (1 - p))));
				}
			}
			case UPPER_BITS -> {
				b.append(s.detail());
				if (s.progress() >= 0) {
					b.append(String.format(" %.0f%%", s.progress() * 100));
				}
			}
			case SOLVED -> b.append("сид ").append(s.seed()).append(" · B поиск · M карта");
			default -> b.append(s.detail());
		}
		return b.toString();
	}

	static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		Session s = SeedCrackerClient.session();
		Settings settings = SeedCrackerClient.settings();
		if (s == null || mc.player == null || mc.level == null || !settings.hud) {
			return;
		}
		Font font = mc.font;
		SeedWorld.Dim dim = SeedCrackerClient.currentDim();
		if (dim == null) {
			return;
		}
		graphics.text(font, statusText(s), 4, 4, s.phase() == Session.Phase.SOLVED ? 0xFF9CF29C : TEXT, true);
		Session.Target t = s.target();
		if (t == null || !Targets.dimId(dim).equals(t.dim)) {
			return;
		}
		double dx = t.x + 0.5 - mc.player.getX();
		double dz = t.z + 0.5 - mc.player.getZ();
		double distance = Math.hypot(dx, dz);
		float relative = relativeYaw(dx, dz, mc.player.getYRot());
		int color = Targets.color(t);
		int cx = graphics.guiWidth() / 2;
		int cy = 30;
		drawArrow(graphics, cx, cy, relative, 0xB0000000, 2);
		drawArrow(graphics, cx, cy, relative, color, 0);
		graphics.centeredText(font, t.label + " · " + Targets.distance(distance) + " " + Targets.compass(dx, dz), cx, cy + 14, color);
		int dy = t.y - (int) mc.player.getY();
		String height = dy > 3 ? " · ↑" + dy : dy < -3 ? " · ↓" + (-dy) : "";
		graphics.centeredText(font, t.x + " " + t.y + " " + t.z + height, cx, cy + 24, DIM);
	}

	/** Angle of a horizontal direction relative to the player's yaw, in degrees, positive = to the right. */
	static float relativeYaw(double dx, double dz, float playerYaw) {
		double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
		return (float) Math.IEEEremainder(targetYaw - playerYaw, 360.0);
	}

	/** An arrow pointing up when {@code angle} is 0, rotated clockwise; {@code grow} thickens it for an outline. */
	static void drawArrow(GuiGraphicsExtractor graphics, int x, int y, float angle, int color, int grow) {
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.rotate((float) Math.toRadians(angle));
		graphics.fill(-1 - grow, -3, 2 + grow, 9 + grow, color);
		for (int i = 0; i <= 6 + grow; i++) {
			graphics.fill(-i, -10 - grow + i, i + 1, -9 - grow + i, color);
		}
		pose.popMatrix();
	}
}
