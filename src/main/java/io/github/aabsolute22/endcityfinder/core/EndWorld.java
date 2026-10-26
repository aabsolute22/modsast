package io.github.aabsolute22.endcityfinder.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Everything the mod can derive about the End from a 48-bit structure seed. */
public final class EndWorld {
	private static final int MIN_CITY_Y = 60;

	private final long seed;
	private final Placement placement;
	private final EndIslands islands;
	private final EndTerrain terrain;
	private final ConcurrentHashMap<Long, EndCity> regionCache = new ConcurrentHashMap<>();
	private static final EndCity NONE = new EndCity(0, 0, 0, Rotation.NONE, false, 0, 0, 0, 0, 0, 0, 0, 0);

	public EndWorld(long seed) {
		this(seed, Placement.END_CITIES);
	}

	public EndWorld(long seed, Placement placement) {
		this.seed = seed & LegacyRandom.MASK;
		this.placement = placement;
		this.islands = new EndIslands(this.seed);
		this.terrain = new EndTerrain(islands, this.seed);
	}

	public long seed() {
		return seed;
	}

	public Placement placement() {
		return placement;
	}

	public EndIslands islands() {
		return islands;
	}

	public EndTerrain terrain() {
		return terrain;
	}

	public static Rotation rotation(long seed, int chunkX, int chunkZ) {
		LegacyRandom random = new LegacyRandom(0L);
		random.setLargeFeatureSeed(seed, chunkX, chunkZ);
		return Rotation.byIndex(random.nextInt(4));
	}

	/**
	 * {@code EndCityStructure.findGenerationPoint} + biome check for a city start in this chunk.
	 * Returns null when vanilla would not place a city here (regardless of the region placement).
	 */
	public EndCity cityAt(int chunkX, int chunkZ) {
		List<EndCityPieces.Piece> pieces = piecesAt(chunkX, chunkZ);
		if (pieces == null) {
			return null;
		}
		EndCityPieces.Piece base = pieces.get(0);
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		int maxY = base.y;
		EndCityPieces.Piece ship = null;
		for (EndCityPieces.Piece p : pieces) {
			minX = Math.min(minX, p.minX);
			minZ = Math.min(minZ, p.minZ);
			maxX = Math.max(maxX, p.maxX);
			maxZ = Math.max(maxZ, p.maxZ);
			maxY = Math.max(maxY, p.maxY);
			if (p.template == EndCityPieces.Template.SHIP) {
				ship = p;
			}
		}
		int shipX = ship == null ? 0 : (ship.minX + ship.maxX) / 2;
		int shipY = ship == null ? 0 : ship.minY + 10;
		int shipZ = ship == null ? 0 : (ship.minZ + ship.maxZ) / 2;
		return new EndCity(chunkX, chunkZ, base.y, base.rotation, ship != null, minX, minZ, maxX, maxZ, maxY, shipX, shipY, shipZ);
	}

	/** Pieces of the city starting in this chunk (base floor first), or null if no city can start here. */
	List<EndCityPieces.Piece> piecesAt(int chunkX, int chunkZ) {
		if (!islands.biomeOfChunk(chunkX, chunkZ).allowsEndCity()) {
			return null;
		}
		LegacyRandom random = new LegacyRandom(0L);
		random.setLargeFeatureSeed(seed, chunkX, chunkZ);
		Rotation rotation = Rotation.byIndex(random.nextInt(4));
		int dx = 5;
		int dz = 5;
		if (rotation == Rotation.CLOCKWISE_90) {
			dx = -5;
		} else if (rotation == Rotation.CLOCKWISE_180) {
			dx = -5;
			dz = -5;
		} else if (rotation == Rotation.COUNTERCLOCKWISE_90) {
			dz = -5;
		}
		int x = chunkX * 16 + 7;
		int z = chunkZ * 16 + 7;
		int y = terrain.surfaceY(x, z);
		if (y < MIN_CITY_Y) {
			return null;
		}
		y = Math.min(y, terrain.surfaceY(x, z + dz));
		if (y < MIN_CITY_Y) {
			return null;
		}
		y = Math.min(y, terrain.surfaceY(x + dx, z));
		if (y < MIN_CITY_Y) {
			return null;
		}
		y = Math.min(y, terrain.surfaceY(x + dx, z + dz));
		if (y < MIN_CITY_Y) {
			return null;
		}
		return EndCityPieces.generate(random, x, y, z, rotation);
	}

	/** The city of a placement region, or null if the region has none. */
	public EndCity cityInRegion(int regionX, int regionZ) {
		long key = Placement.pack(regionX, regionZ);
		EndCity cached = regionCache.get(key);
		if (cached == null) {
			long chunk = placement.potentialChunk(seed, regionX, regionZ);
			EndCity city = cityAt(Placement.unpackX(chunk), Placement.unpackZ(chunk));
			cached = city == null ? NONE : city;
			regionCache.put(key, cached);
		}
		return cached == NONE ? null : cached;
	}

	/** Whether the start chunk of the region containing this chunk is exactly this chunk and holds a city. */
	public boolean hasCityStartingIn(int chunkX, int chunkZ) {
		EndCity city = cityInRegion(placement.region(chunkX), placement.region(chunkZ));
		return city != null && city.chunkX() == chunkX && city.chunkZ() == chunkZ;
	}

	/** All cities whose region intersects the square of the given block radius around (x, z). */
	public List<EndCity> citiesNear(int blockX, int blockZ, int radius) {
		int span = placement.spacing() * 16;
		int r0x = Math.floorDiv(blockX - radius, span);
		int r1x = Math.floorDiv(blockX + radius, span);
		int r0z = Math.floorDiv(blockZ - radius, span);
		int r1z = Math.floorDiv(blockZ + radius, span);
		List<EndCity> result = new ArrayList<>();
		for (int rx = r0x; rx <= r1x; rx++) {
			for (int rz = r0z; rz <= r1z; rz++) {
				EndCity city = cityInRegion(rx, rz);
				if (city != null) {
					result.add(city);
				}
			}
		}
		return result;
	}
}
