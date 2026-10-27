package io.github.aabsolute22.seedcracker.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import io.github.aabsolute22.seedcracker.core.StructureKind;
import io.github.aabsolute22.seedcracker.world.ManualPlacement;
import io.github.aabsolute22.seedcracker.world.SeedWorld;

/**
 * A structure added by hand. If the blocks around the point show its start, that gives the exact chunk (and
 * rotation); otherwise the point itself narrows the start down to one or a few chunks, which the solver then tries
 * one by one.
 */
public final class ManualAdd {
	/** More possible start chunks than this say too little to be worth trying one by one. */
	private static final int MAX_CANDIDATES = 9;

	/** What adding would do; {@code error} set when it cannot be added. */
	public record Plan(StructureKind kind, List<int[]> candidates, int pick, boolean fromBlocks, int x, int y, int z, String note, String error) {
		public boolean ok() {
			return error == null;
		}

		public boolean exact() {
			return candidates.size() == 1;
		}

		/** One line for chat and the screen. */
		public String summary() {
			if (!ok()) {
				return error;
			}
			if (exact()) {
				int[] c = candidates.get(0);
				return (fromBlocks ? "нашёл по блокам: " : "стартовый чанк: ") + c[0] + " " + c[1] + (c[2] >= 0 ? " · поворот " + c[2] * 90 + "°" : "");
			}
			StringBuilder b = new StringBuilder("подходит " + candidates.size() + " чанка: ");
			for (int i = 0; i < candidates.size(); i++) {
				b.append(i == 0 ? "" : ", ").append(candidates.get(i)[0]).append(' ').append(candidates.get(i)[1]);
			}
			return b.toString();
		}
	}

	private ManualAdd() {
	}

	private static Plan fail(StructureKind kind, int x, int y, int z, String error) {
		return new Plan(kind, List.of(), -1, false, x, y, z, "", error);
	}

	/**
	 * @param here whether (x, y, z) is where the player stands; then the kind must be of the current dimension
	 */
	public static Plan plan(StructureKind kind, int x, int y, int z, boolean here) {
		Minecraft mc = Minecraft.getInstance();
		Scanner scanner = SeedCrackerClient.scanner();
		if (!ManualPlacement.useful(kind)) {
			return fail(kind, x, y, z, SeedCrackerClient.name(kind) + " ничего не говорит о сиде");
		}
		SeedWorld.Dim dim = SeedCrackerClient.currentDim();
		boolean sameDim = dim != null && dim.overworld() == kind.overworld;
		if (here && !sameDim) {
			return fail(kind, x, y, z, SeedCrackerClient.name(kind) + " бывает только " + (kind.overworld ? "в верхнем мире" : "в незере"));
		}
		if (scanner.matcher() == null) {
			return fail(kind, x, y, z, "данные генерации ещё грузятся, подожди пару секунд");
		}
		if (sameDim && mc.level != null && mc.level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) != null) {
			List<Scanner.Located> found = scanner.findNear(mc.level, kind, x, z);
			if (!found.isEmpty()) {
				Scanner.Located l = found.get(0);
				List<int[]> c = new ArrayList<>();
				c.add(new int[] {l.chunkX(), l.chunkZ(), l.rotation()});
				return new Plan(kind, c, l.pick(), true, l.x(), l.y(), l.z(), "manual " + l.note(), null);
			}
			if (!here && y == Integer.MIN_VALUE) {
				y = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
			}
		}
		if (y == Integer.MIN_VALUE) {
			y = 64;
		}
		if (!ManualPlacement.positionWorks(kind)) {
			return fail(kind, x, y, z, "не вижу " + SeedCrackerClient.name(kind) + " по блокам рядом. Подойди ближе или укажи стартовый чанк: /sc add "
					+ kind.id() + " chunk <x> <z>");
		}
		List<int[]> cands = new ArrayList<>();
		for (int[] c : scanner.placement().candidates(kind, x, z)) {
			cands.add(new int[] {c[0], c[1], -1});
		}
		if (cands.isEmpty()) {
			return fail(kind, x, y, z, "здесь " + SeedCrackerClient.name(kind) + " начаться не может: проверь тип и координаты");
		}
		if (cands.size() > MAX_CANDIDATES) {
			return fail(kind, x, y, z, "по этой точке подходит " + cands.size() + " чанков, слишком неточно. Подойди ближе, чтобы мод увидел блоки, или укажи чанк: /sc add "
					+ kind.id() + " chunk <x> <z>");
		}
		return new Plan(kind, cands, -1, false, x, y, z, "manual", null);
	}

	/** Adds the plan to the session and restarts the solver; null if the same structure is already there. */
	static Session.Obs commit(Session session, Plan p) {
		Session.Obs o = session.addObservation(p.kind(), p.kind().overworld ? "overworld" : "nether", p.candidates(), p.pick(), false, p.x(), p.y(), p.z(),
				p.note());
		if (o != null) {
			session.setStructureSeeds(List.of(), false);
			SeedCrackerClient.solver().request();
		}
		return o;
	}

	/** Chat line after adding, with an undo link. */
	static Component feedback(Plan p, Session.Obs o) {
		if (o == null) {
			return Component.literal("[SC] Эта структура уже есть в находках").withStyle(ChatFormatting.GRAY);
		}
		MutableComponent line = Component.literal("[SC] Добавил #" + o.id + ": " + SeedCrackerClient.name(p.kind()) + ", " + p.summary())
				.withStyle(p.exact() ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
		line.append(Component.literal("  [отменить]").withStyle(s -> s.withColor(ChatFormatting.RED)
				.withClickEvent(new ClickEvent.RunCommand("/sc remove " + o.id)).withHoverEvent(new HoverEvent.ShowText(Component.literal("/sc remove " + o.id)))));
		if (!p.exact()) {
			line.append(Component.literal("\n    мод переберёт их по очереди. Точнее будет, если " + ManualPlacement.hint(p.kind()))
					.withStyle(ChatFormatting.GRAY));
		}
		return line;
	}
}
