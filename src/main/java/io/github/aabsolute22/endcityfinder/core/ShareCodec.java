package io.github.aabsolute22.endcityfinder.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Compact text tokens for sharing results between players through ordinary chat whispers, so it works on any
 * server without a server-side mod. A token is {@code ecf1:<kind>:<payload>#<checksum>}.
 */
public final class ShareCodec {
	public static final String PREFIX = "ecf1";
	/** Keeps whispers well under the 256 character chat limit. */
	private static final int MAX_PAYLOAD = 180;
	private static final Pattern TOKEN = Pattern.compile("ecf1:([sv]):([0-9a-z.,:\\-]+)#([0-9a-z]+)");

	public sealed interface Message permits Seed, Visited {
	}

	/** A solved 48-bit structure seed and the server's End city salt. */
	public record Seed(long seed, int salt) implements Message {
	}

	/** Start chunks (packed with {@link Placement#pack}) of cities the sender has looted. */
	public record Visited(long[] cities) implements Message {
	}

	private ShareCodec() {
	}

	private static String checksum(String kind, String payload) {
		CRC32 crc = new CRC32();
		crc.update((kind + ":" + payload).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		return Long.toString(crc.getValue() & 0xFFFFF, 36);
	}

	private static String token(String kind, String payload) {
		return PREFIX + ":" + kind + ":" + payload + "#" + checksum(kind, payload);
	}

	public static String seed(long seed, int salt) {
		return token("s", Long.toString(seed & LegacyRandom.MASK, 36) + ":" + Integer.toString(salt, 36));
	}

	/** Splits the cities into as many whisper-sized tokens as needed. */
	public static List<String> visited(long[] cities) {
		List<String> tokens = new ArrayList<>();
		StringBuilder payload = new StringBuilder();
		for (long key : cities) {
			String entry = Integer.toString(Placement.unpackX(key), 36) + "." + Integer.toString(Placement.unpackZ(key), 36);
			if (payload.length() > 0 && payload.length() + entry.length() + 1 > MAX_PAYLOAD) {
				tokens.add(token("v", payload.toString()));
				payload.setLength(0);
			}
			if (payload.length() > 0) {
				payload.append(',');
			}
			payload.append(entry);
		}
		if (payload.length() > 0) {
			tokens.add(token("v", payload.toString()));
		}
		return tokens;
	}

	/** Finds and decodes a token anywhere in a chat line (whatever formatting the server wraps it in). */
	public static Optional<Message> find(String text) {
		Matcher m = TOKEN.matcher(text);
		if (!m.find()) {
			return Optional.empty();
		}
		String kind = m.group(1);
		String payload = m.group(2);
		if (!checksum(kind, payload).equals(m.group(3))) {
			return Optional.empty();
		}
		try {
			if (kind.equals("s")) {
				String[] parts = payload.split(":");
				return parts.length == 2 ? Optional.of(new Seed(Long.parseLong(parts[0], 36), Integer.parseInt(parts[1], 36))) : Optional.empty();
			}
			String[] entries = payload.split(",");
			long[] cities = new long[entries.length];
			for (int i = 0; i < entries.length; i++) {
				String[] xz = entries[i].split("\\.");
				if (xz.length != 2) {
					return Optional.empty();
				}
				cities[i] = Placement.pack(Integer.parseInt(xz[0], 36), Integer.parseInt(xz[1], 36));
			}
			return Optional.of(new Visited(cities));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	/** Index in {@code text} where a token starts, or -1. */
	public static int indexOf(String text) {
		Matcher m = TOKEN.matcher(text);
		return m.find() ? m.start() : -1;
	}
}
