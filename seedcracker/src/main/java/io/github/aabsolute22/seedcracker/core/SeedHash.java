package io.github.aabsolute22.seedcracker.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The server sends {@code BiomeManager.obfuscateSeed(seed)} (first 8 bytes of SHA-256 of the seed) to every client
 * for biome blending. With the 48-bit structure seed known, the remaining 16 bits fall out of it in milliseconds.
 */
public final class SeedHash {
	private SeedHash() {
	}

	/** Same as Guava's {@code Hashing.sha256().hashLong(seed).asLong()}: little-endian in, little-endian out. */
	public static long obfuscate(long seed, MessageDigest sha) {
		byte[] in = new byte[8];
		for (int i = 0; i < 8; i++) {
			in[i] = (byte) (seed >>> (8 * i));
		}
		byte[] out = sha.digest(in);
		long v = 0;
		for (int i = 7; i >= 0; i--) {
			v = v << 8 | (out[i] & 0xFFL);
		}
		return v;
	}

	public static long obfuscate(long seed) {
		return obfuscate(seed, sha256());
	}

	public static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** All full seeds with these low 48 bits whose hash is {@code hashed}. */
	public static List<Long> fullSeeds(long structureSeed, long hashed) {
		MessageDigest sha = sha256();
		List<Long> out = new ArrayList<>(1);
		long low = structureSeed & LegacyRandom.MASK;
		for (long high = 0; high < 1 << 16; high++) {
			long seed = high << 48 | low;
			if (obfuscate(seed, sha) == hashed) {
				out.add(seed);
			}
		}
		return out;
	}

	/**
	 * Upper 16 bits in the order worth trying first when nothing else is known: seeds typed as text or small
	 * numbers are sign-extended 32-bit values, so their top bits are all 0 or all 1.
	 */
	public static int[] upperBitsOrder(long structureSeed) {
		int[] order = new int[1 << 16];
		boolean negative = (structureSeed & 0x80000000L) != 0;
		int first = negative ? 0xFFFF : 0;
		int second = negative ? 0 : 0xFFFF;
		order[0] = first;
		order[1] = second;
		int i = 2;
		for (int h = 0; h < 1 << 16; h++) {
			if (h != first && h != second) {
				order[i++] = h;
			}
		}
		return order;
	}
}
