package io.github.aabsolute22.endcityfinder.core;

/** Same order as Minecraft's {@code Rotation} enum, so {@code nextInt(4)} indexes it directly. */
public enum Rotation {
	NONE,
	CLOCKWISE_90,
	CLOCKWISE_180,
	COUNTERCLOCKWISE_90;

	private static final Rotation[] VALUES = values();

	public static Rotation byIndex(int index) {
		return VALUES[index & 3];
	}

	public Rotation rotated(Rotation other) {
		return byIndex(ordinal() + other.ordinal());
	}

	/** Rotates a horizontal offset around the origin like {@code StructureTemplate.transform}. */
	public int rotateX(int x, int z) {
		return switch (this) {
			case NONE -> x;
			case CLOCKWISE_90 -> -z;
			case CLOCKWISE_180 -> -x;
			case COUNTERCLOCKWISE_90 -> z;
		};
	}

	public int rotateZ(int x, int z) {
		return switch (this) {
			case NONE -> z;
			case CLOCKWISE_90 -> x;
			case CLOCKWISE_180 -> -z;
			case COUNTERCLOCKWISE_90 -> -x;
		};
	}
}
