package io.github.aabsolute22.seedcracker.client;

import net.minecraft.core.BlockPos;

import io.github.aabsolute22.seedcracker.core.StructureKind;
import io.github.aabsolute22.seedcracker.world.SeedWorld;

/** Building and describing navigation targets. */
public final class Targets {
	private Targets() {
	}

	public static String dimId(SeedWorld.Dim dim) {
		return dim == SeedWorld.Dim.NETHER ? "nether" : "overworld";
	}

	public static Session.Target of(SeedWorld.Found f, SeedWorld.Dim dim) {
		Session.Target t = new Session.Target();
		t.kind = f.kind().id();
		t.dim = dimId(dim);
		t.x = f.x();
		t.y = f.y();
		t.z = f.z();
		t.label = label(f);
		t.chunkX = f.chunkX();
		t.chunkZ = f.chunkZ();
		t.refined = f.box() != null;
		return t;
	}

	public static Session.Target biome(String biome, BlockPos pos, SeedWorld.Dim dim) {
		Session.Target t = new Session.Target();
		t.kind = "biome:" + biome;
		t.dim = dimId(dim);
		t.x = pos.getX();
		t.y = pos.getY();
		t.z = pos.getZ();
		t.label = SeedCrackerClient.biomeName(biome);
		return t;
	}

	public static Session.Target point(int x, int y, int z, SeedWorld.Dim dim, String label) {
		Session.Target t = new Session.Target();
		t.kind = "point";
		t.dim = dimId(dim);
		t.x = x;
		t.y = y;
		t.z = z;
		t.label = label;
		return t;
	}

	public static String label(SeedWorld.Found f) {
		String v = variantName(f.variant());
		return SeedCrackerClient.name(f.kind()) + (v.isEmpty() ? "" : " (" + v + ")");
	}

	public static String variantName(String v) {
		if (v == null || v.isEmpty()) {
			return "";
		}
		boolean zombie = v.endsWith("_zombie");
		String base = zombie ? v.substring(0, v.length() - "_zombie".length()) : v;
		String name = switch (base) {
			case "plains" -> "равнины";
			case "desert" -> "пустыня";
			case "savanna" -> "саванна";
			case "snowy" -> "снежная";
			case "taiga" -> "тайга";
			case "units" -> "жилой";
			case "hoglin_stable" -> "конюшня хоглинов";
			case "treasure" -> "сокровищница";
			case "bridge" -> "мост";
			case "standard" -> "";
			case "jungle" -> "джунгли";
			case "swamp" -> "болото";
			case "mountain" -> "горы";
			case "ocean" -> "океан";
			case "nether" -> "";
			case "cold" -> "холодные";
			case "warm" -> "тёплые";
			case "beached" -> "на берегу";
			case "mesa" -> "бесплодные земли";
			default -> base;
		};
		if (!SeedCrackerClient.russian()) {
			name = base.replace('_', ' ');
		}
		return zombie ? name + (SeedCrackerClient.russian() ? ", зомби" : ", zombie") : name;
	}

	public static int color(Session.Target t) {
		if (t.kind != null && t.kind.startsWith("biome:")) {
			return 0xFF7CF2A0;
		}
		StructureKind k = StructureKind.byId(t.kind);
		return k != null ? k.color : 0xFF5CF2FF;
	}

	/** Compass direction of (dx, dz) as a short word. */
	public static String compass(double dx, double dz) {
		double angle = Math.toDegrees(Math.atan2(dx, -dz));
		String[] ru = {"С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"};
		String[] en = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
		int i = (int) Math.floorMod(Math.round(angle / 45.0), 8L);
		return SeedCrackerClient.russian() ? ru[i] : en[i];
	}

	public static String distance(double d) {
		return d >= 10000 ? String.format("%.1f км", d / 1000) : (int) d + " м";
	}
}
