package io.github.aabsolute22.endcityfinder.client;

import org.joml.Matrix3x2fStack;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.Level;

import io.github.aabsolute22.endcityfinder.core.EndCity;

/** Top-of-screen status line and the arrow to the target city. */
public final class HudOverlay {
	static final int COLOR_SHIP = 0xFFFFC94A;
	static final int COLOR_CITY = 0xFFD58CFF;
	static final int COLOR_TARGET = 0xFF5CF2FF;
	static final int COLOR_VISITED = 0xFF8A8A8A;

	private HudOverlay() {
	}

	static String phaseName(FinderSession.Phase phase) {
		return switch (phase) {
			case NEED_PILLARS -> "нужны пилоны";
			case NEED_CITIES -> "нужен город";
			case NEED_MORE_CITIES -> "нужно больше городов";
			case SEARCHING -> "перебор";
			case FILTERING -> "проверка кандидатов";
			case AMBIGUOUS -> "несколько вариантов";
			case SOLVED -> "сид найден";
			case CONTRADICTION -> "противоречие";
		};
	}

	static String statusText(FinderSession s) {
		StringBuilder b = new StringBuilder("ECF · ");
		switch (s.phase()) {
			case SOLVED -> b.append("сид найден · городов рядом ").append(s.predicted().size());
			case AMBIGUOUS -> b.append("кандидатов ").append(s.candidateCount()).append(" · общих городов ").append(s.predicted().size());
			case SEARCHING -> b.append(String.format("перебор %.0f%%", Math.max(0, s.progress()) * 100));
			case FILTERING -> b.append("проверяю кандидатов");
			case NEED_PILLARS -> b.append("нужны пилоны ").append(s.knownSpikes()).append("/10");
			case NEED_CITIES -> b.append("пилоны ").append(s.knownSpikes()).append("/10 · нужен город");
			case NEED_MORE_CITIES -> b.append("городов ").append(s.cities().size()).append(" · нужно больше");
			case CONTRADICTION -> b.append("противоречие, см. /ecf status");
		}
		return b.toString();
	}

	static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		FinderSession s = EndCityFinderClient.session();
		Settings settings = EndCityFinderClient.settings();
		if (s == null || mc.player == null || mc.level == null || mc.level.dimension() != Level.END || !settings.hud) {
			return;
		}
		Font font = mc.font;
		graphics.text(font, statusText(s), 4, 4, 0xFFE6D9FF, true);
		double px = mc.player.getX();
		double pz = mc.player.getZ();
		EndCity target = s.target(px, pz, settings);
		if (target == null || !settings.arrow) {
			return;
		}
		double dx = target.x() + 0.5 - px;
		double dz = target.z() + 0.5 - pz;
		int distance = (int) Math.hypot(dx, dz);
		float relative = relativeYaw(dx, dz, mc.player.getYRot());
		int color = target.ship() ? COLOR_SHIP : COLOR_TARGET;
		int cx = graphics.guiWidth() / 2;
		int cy = 34;
		drawArrow(graphics, cx, cy, relative, 0xC0000000, 2);
		drawArrow(graphics, cx, cy, relative, color, 0);
		String line = distance + " м" + (target.ship() ? " · корабль" : "");
		graphics.centeredText(font, line, cx, cy + 14, color);
		graphics.centeredText(font, target.x() + " " + target.y() + " " + target.z(), cx, cy + 24, 0xFFBFBFBF);
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
