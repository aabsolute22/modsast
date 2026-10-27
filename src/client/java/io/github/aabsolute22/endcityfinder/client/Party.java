package io.github.aabsolute22.endcityfinder.client;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;

import com.mojang.authlib.GameProfile;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import io.github.aabsolute22.endcityfinder.core.ShareCodec;

/**
 * Sharing with friends who also run the mod, through plain {@code /msg} whispers carrying {@link ShareCodec}
 * tokens. Works on any server; the receiving mod hides the tokens from chat. Only whispers from players in the
 * party are applied automatically, anyone else's seed has to be accepted with a click.
 */
public final class Party {
	/** Vanilla kicks for chat spam, so whispers go out one at a time. */
	private static final long SEND_INTERVAL_MS = 1500;
	private static final long VISITED_BATCH_MS = 4000;

	private static final ArrayDeque<String> outgoing = new ArrayDeque<>();
	private static final LongLinkedOpenHashSet pendingVisited = new LongLinkedOpenHashSet();
	private static long lastSend;
	private static long firstPendingVisit;
	private static ShareCodec.Seed offered;
	private static String offeredBy;
	/** The last seed sent or received, so a received seed is not echoed back. */
	private static Long lastSharedSeed;

	private Party() {
	}

	static void reset() {
		outgoing.clear();
		pendingVisited.clear();
		offered = null;
		offeredBy = null;
		lastSharedSeed = null;
	}

	private static void whisper(String player, String token) {
		outgoing.add("msg " + player + " " + token);
	}

	static void tick(Minecraft mc, FinderSession session) {
		long now = System.currentTimeMillis();
		if (!pendingVisited.isEmpty() && now - firstPendingVisit > VISITED_BATCH_MS) {
			long[] cities = pendingVisited.toLongArray();
			pendingVisited.clear();
			for (String member : session.party()) {
				for (String token : ShareCodec.visited(cities)) {
					whisper(member, token);
				}
			}
		}
		if (!outgoing.isEmpty() && now - lastSend > SEND_INTERVAL_MS && mc.getConnection() != null) {
			mc.getConnection().sendCommand(outgoing.poll());
			lastSend = now;
		}
	}

	/** Sends the solved seed and salt, plus every looted city, to one player or (with null) the whole party. */
	static int share(FinderSession session, String player) {
		Long seed = session.solvedSeed();
		if (seed == null) {
			return 0;
		}
		List<String> targets = player == null ? session.party() : List.of(player);
		for (String target : targets) {
			whisper(target, ShareCodec.seed(seed, session.solvedSalt()));
			for (String token : ShareCodec.visited(session.visitedCities())) {
				whisper(target, token);
			}
		}
		lastSharedSeed = seed;
		return targets.size();
	}

	/** Called once the seed is solved here: hand it to the party unless it came from them. */
	static void onSolved(FinderSession session) {
		Long seed = session.solvedSeed();
		if (seed != null && !seed.equals(lastSharedSeed) && !session.party().isEmpty()) {
			int n = share(session, null);
			EndCityFinderClient.message(Component.literal("[ECF] сид отправлен друзьям: " + n).withStyle(ChatFormatting.GREEN));
		}
	}

	/** A city got looted here: tell the party (batched). */
	static void onVisited(FinderSession session, long cityKey) {
		if (session.party().isEmpty()) {
			return;
		}
		if (pendingVisited.isEmpty()) {
			firstPendingVisit = System.currentTimeMillis();
		}
		pendingVisited.add(cityKey);
	}

	static boolean acceptOffer(FinderSession session) {
		if (offered == null) {
			return false;
		}
		lastSharedSeed = offered.seed();
		session.applyShared(offered.seed(), offered.salt());
		offered = null;
		return true;
	}

	/** Whose whisper this is: the signed sender if known, otherwise a party member named before the token. */
	private static String senderOf(String text, GameProfile sender, FinderSession session) {
		if (sender != null) {
			return sender.name();
		}
		int at = ShareCodec.indexOf(text);
		String before = at < 0 ? text : text.substring(0, at);
		for (String member : session.party()) {
			if (before.toLowerCase().contains(member.toLowerCase())) {
				return member;
			}
		}
		return null;
	}

	/**
	 * Handles an incoming chat line. Returns false when it carried a token, so the line is hidden from chat.
	 */
	static boolean onMessage(Component message, GameProfile sender) {
		String text = message.getString();
		Optional<ShareCodec.Message> parsed = ShareCodec.find(text);
		if (parsed.isEmpty()) {
			return true;
		}
		FinderSession session = EndCityFinderClient.session();
		Minecraft mc = Minecraft.getInstance();
		if (session == null || mc.player == null) {
			return false;
		}
		String from = senderOf(text, sender, session);
		if (from != null && from.equalsIgnoreCase(mc.player.getGameProfile().name())) {
			// The echo of our own whisper.
			return false;
		}
		boolean trusted = from != null && session.isPartyMember(from);
		ShareCodec.Message m = parsed.get();
		mc.execute(() -> handle(session, m, from, trusted));
		return false;
	}

	private static void handle(FinderSession session, ShareCodec.Message m, String from, boolean trusted) {
		if (m instanceof ShareCodec.Seed seed) {
			if (seed.seed() == (session.solvedSeed() == null ? -1 : session.solvedSeed())) {
				return;
			}
			if (trusted) {
				lastSharedSeed = seed.seed();
				session.applyShared(seed.seed(), seed.salt());
				EndCityFinderClient.message(Component.literal("[ECF] " + from + " прислал сид энда, города показаны").withStyle(ChatFormatting.GREEN));
			} else {
				offered = seed;
				offeredBy = from;
				EndCityFinderClient.message(Component.literal("[ECF] " + (from == null ? "кто-то" : from) + " прислал сид энда ")
						.append(Component.literal("[принять]").withStyle(style -> style.withColor(ChatFormatting.GREEN)
								.withClickEvent(new ClickEvent.RunCommand("/ecf accept"))
								.withHoverEvent(new HoverEvent.ShowText(Component.literal("Принять сид и показать города"))))));
			}
		} else if (m instanceof ShareCodec.Visited visited && trusted) {
			int added = session.markVisited(visited.cities());
			if (added > 0) {
				EndCityFinderClient.message(Component.literal("[ECF] " + from + ": залутано городов +" + added).withStyle(ChatFormatting.GRAY));
			}
		}
	}

	static String offeredBy() {
		return offeredBy;
	}
}
