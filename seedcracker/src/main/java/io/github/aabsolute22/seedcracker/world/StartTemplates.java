package io.github.aabsolute22.seedcracker.world;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import io.github.aabsolute22.seedcracker.core.StartRules;
import io.github.aabsolute22.seedcracker.core.StructureKind;

/**
 * Start pieces that can be recognised by their blocks, each with the rule tying its template position to the start
 * chunk. Everything is read from the vanilla data at runtime, so a template change in an update is picked up.
 */
public final class StartTemplates {
	/** How the template position follows from the start chunk origin. */
	public enum Rule {
		/** Jigsaw start: origin minus the rotated start jigsaw (zero when there is none), rotation around (0,0,0). */
		JIGSAW,
		/** Placed at origin + offset, rotated around a fixed pivot (igloo, shipwreck, ocean ruins). */
		PIVOT,
		/** Placed at the origin, rotated around the template centre and optionally mirrored front-back (ruined portals). */
		CENTER_MIRROR,
		/** A child of an invisible start piece (outpost towers): fixed offsets per start rotation, from the jigsaw joint. */
		CHILD
	}

	/** Where a child piece lands when the start piece has rotation {@code startRotation}. */
	public record ChildPlacement(Rotation startRotation, Rotation rotation, BlockPos offset) {
	}

	/** One template block, in template coordinates. */
	public record TemplateBlock(int x, int y, int z, String canon) {
	}

	public record Spec(StructureKind kind, String structure, String template, Rule rule, BlockPos pivot, BlockPos offset, Vec3i size,
			List<TemplateBlock> signature, String anchor, double threshold, boolean rotationCounts, java.util.Set<String> biomes,
			List<ChildPlacement> placements) {
		public List<Mirror> mirrors() {
			return rule == Rule.CENTER_MIRROR ? List.of(Mirror.NONE, Mirror.FRONT_BACK) : List.of(Mirror.NONE);
		}

		/** Template position relative to the start chunk's origin (y relative to the start height). */
		public BlockPos templateOffset(Rotation rotation) {
			return switch (rule) {
				case JIGSAW -> BlockPos.ZERO.subtract(StructureTemplate.transform(pivot, Mirror.NONE, rotation, BlockPos.ZERO));
				case PIVOT -> offset;
				case CENTER_MIRROR, CHILD -> BlockPos.ZERO;
			};
		}

		public BlockPos rotationPivot() {
			return rule == Rule.JIGSAW ? BlockPos.ZERO : pivot;
		}

		/** World offset of a template block from (chunk origin x, template y, chunk origin z). */
		public BlockPos place(TemplateBlock b, Rotation rotation, Mirror mirror) {
			BlockPos local = StructureTemplate.transform(new BlockPos(b.x, b.y, b.z), mirror, rotation, rotationPivot());
			BlockPos t = templateOffset(rotation);
			return new BlockPos(t.getX() + local.getX(), local.getY(), t.getZ() + local.getZ());
		}

		/** Every way this spec can sit relative to its start chunk. */
		public List<Variant> variants() {
			List<Variant> out = new ArrayList<>();
			if (rule == Rule.CHILD) {
				for (ChildPlacement p : placements) {
					out.add(variant(p.rotation(), Mirror.NONE, p.offset(), p.startRotation()));
				}
				return out;
			}
			for (Rotation r : Rotation.values()) {
				for (Mirror m : mirrors()) {
					out.add(variant(r, m, null, r));
				}
			}
			return out;
		}

		public Variant variant(Rotation rotation, Mirror mirror) {
			return variant(rotation, mirror, null, rotation);
		}

		private Variant variant(Rotation rotation, Mirror mirror, BlockPos childOffset, Rotation startRotation) {
			int n = signature.size();
			int[] dx = new int[n];
			int[] dy = new int[n];
			int[] dz = new int[n];
			String[] canon = new String[n];
			List<Integer> anchors = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				TemplateBlock b = signature.get(i);
				BlockPos p = childOffset == null ? place(b, rotation, mirror)
						: childOffset.offset(StructureTemplate.transform(new BlockPos(b.x, b.y, b.z), Mirror.NONE, rotation, BlockPos.ZERO));
				dx[i] = p.getX();
				dy[i] = p.getY();
				dz[i] = p.getZ();
				canon[i] = b.canon;
				if (b.canon.equals(anchor)) {
					anchors.add(i);
				}
			}
			return new Variant(this, rotation, mirror, startRotation, dx, dy, dz, canon, anchors.stream().mapToInt(Integer::intValue).toArray());
		}
	}

	/** A spec under one rotation and mirror, with block offsets ready for matching. */
	public record Variant(Spec spec, Rotation rotation, Mirror mirror, Rotation startRotation, int[] dx, int[] dy, int[] dz, String[] canon,
			int[] anchors) {
	}

	private static final int MAX_SIGNATURE = 360;

	private final List<Spec> specs = new ArrayList<>();
	private final Map<String, java.util.Set<String>> biomesByStructure = new HashMap<>();
	private final Map<String, List<Variant>> byAnchor = new HashMap<>();

	public StartTemplates(Worldgen worldgen) {
		var structures = worldgen.registries().lookupOrThrow(Registries.STRUCTURE);
		for (StructureKind kind : StructureKind.values()) {
			for (String id : kind.structures) {
				structures.getOptional(Identifier.withDefaultNamespace(id)).ifPresent(st -> {
					java.util.Set<String> ids = new java.util.HashSet<>();
					for (var holder : st.biomes()) {
						holder.unwrapKey().ifPresent(k -> ids.add(k.identifier().getPath()));
					}
					biomesByStructure.put(id, ids);
				});
			}
		}
		for (StructureKind kind : List.of(StructureKind.VILLAGE, StructureKind.ANCIENT_CITY, StructureKind.TRAIL_RUINS, StructureKind.TRIAL_CHAMBERS,
				StructureKind.BASTION)) {
			for (String id : kind.structures) {
				Optional<Structure> s = structures.getOptional(Identifier.withDefaultNamespace(id));
				if (s.isPresent() && s.get() instanceof JigsawStructure jigsaw) {
					addJigsaw(worldgen, kind, id, jigsaw);
				}
			}
		}
		addPivot(worldgen, StructureKind.IGLOO, "igloo", "igloo/top", new BlockPos(3, 5, 5), BlockPos.ZERO, 0.75);
		for (Identifier t : templatesUnder(worldgen, "shipwreck/")) {
			addPivot(worldgen, StructureKind.SHIPWRECK, "shipwreck", t.getPath(), new BlockPos(4, 0, 15), BlockPos.ZERO, 0.7);
		}
		for (Identifier t : templatesUnder(worldgen, "underwater_ruin/")) {
			if (t.getPath().contains("big_")) {
				addPivot(worldgen, StructureKind.OCEAN_RUIN, "ocean_ruin_cold", t.getPath(), BlockPos.ZERO, BlockPos.ZERO, 0.7);
			}
		}
		for (Identifier t : templatesUnder(worldgen, "ruined_portal/")) {
			addPortal(worldgen, StructureKind.RUINED_PORTAL, t.getPath());
			addPortal(worldgen, StructureKind.RUINED_PORTAL_NETHER, t.getPath());
		}
		addOutpostChildren(worldgen);
		for (Spec spec : specs) {
			for (Variant v : spec.variants()) {
				byAnchor.computeIfAbsent(spec.anchor, k -> new ArrayList<>()).add(v);
			}
		}
	}

	public List<Spec> specs() {
		return specs;
	}

	/** Variants with at least one anchor block of this canonical type. */
	public List<Variant> variantsAnchoredOn(String canon) {
		return byAnchor.getOrDefault(canon, List.of());
	}

	public java.util.Set<String> anchors() {
		return byAnchor.keySet();
	}

	private static List<Identifier> templatesUnder(Worldgen worldgen, String prefix) {
		List<Identifier> out = new ArrayList<>();
		worldgen.templates().listTemplates().filter(id -> id.getNamespace().equals("minecraft") && id.getPath().startsWith(prefix))
				.sorted().forEach(out::add);
		return out;
	}

	@SuppressWarnings("unchecked")
	static List<StructureTemplate.StructureBlockInfo> blocks(StructureTemplate template) {
		try {
			Field f = StructureTemplate.class.getDeclaredField("palettes");
			f.setAccessible(true);
			List<StructureTemplate.Palette> palettes = (List<StructureTemplate.Palette>) f.get(template);
			return palettes.isEmpty() ? List.of() : palettes.get(0).blocks();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("StructureTemplate layout changed", e);
		}
	}

	private static Optional<Identifier> startJigsawName(JigsawStructure structure) {
		try {
			Field f = JigsawStructure.class.getDeclaredField("startJigsawName");
			f.setAccessible(true);
			@SuppressWarnings("unchecked")
			Optional<Identifier> name = (Optional<Identifier>) f.get(structure);
			return name;
		} catch (ReflectiveOperationException e) {
			return Optional.empty();
		}
	}

	private void addJigsaw(Worldgen worldgen, StructureKind kind, String structureId, JigsawStructure structure) {
		Optional<Identifier> jigsawName = startJigsawName(structure);
		for (Pair<StructurePoolElement, Integer> entry : structure.getStartPool().value().getTemplates()) {
			List<SinglePoolElement> singles = new ArrayList<>();
			if (entry.getFirst() instanceof SinglePoolElement single) {
				singles.add(single);
			} else if (entry.getFirst() instanceof ListPoolElement list) {
				for (StructurePoolElement e : list.getElements()) {
					if (e instanceof SinglePoolElement single) {
						singles.add(single);
					}
				}
			}
			for (SinglePoolElement single : singles) {
				Identifier id = single.getTemplateLocation();
				StructureTemplate template = worldgen.templates().getOrCreate(id);
				BlockPos jigsaw = BlockPos.ZERO;
				if (jigsawName.isPresent()) {
					Optional<BlockPos> named = namedJigsaw(template, jigsawName.get());
					if (named.isEmpty()) {
						continue;
					}
					jigsaw = named.get();
				}
				add(kind, structureId, id.getPath(), Rule.JIGSAW, jigsaw, BlockPos.ZERO, template, null, kind == StructureKind.BASTION ? 0.7 : 0.75);
			}
		}
	}

	private static Optional<BlockPos> namedJigsaw(StructureTemplate template, Identifier name) {
		for (StructureTemplate.JigsawBlockInfo j : template.getJigsaws(BlockPos.ZERO, Rotation.NONE)) {
			if (j.name().equals(name)) {
				return Optional.of(j.info().pos());
			}
		}
		return Optional.empty();
	}

	private void addPivot(Worldgen worldgen, StructureKind kind, String structureId, String path, BlockPos pivot, BlockPos offset, double threshold) {
		StructureTemplate template = worldgen.templates().getOrCreate(Identifier.withDefaultNamespace(path));
		add(kind, structureId, path, Rule.PIVOT, pivot, offset, template, null, threshold);
	}

	private void addPortal(Worldgen worldgen, StructureKind kind, String path) {
		StructureTemplate template = worldgen.templates().getOrCreate(Identifier.withDefaultNamespace(path));
		Vec3i size = template.getSize();
		BlockPos pivot = new BlockPos(size.getX() / 2, 0, size.getZ() / 2);
		String structureId = kind == StructureKind.RUINED_PORTAL_NETHER ? "ruined_portal_nether" : "ruined_portal";
		add(kind, structureId, path, Rule.CENTER_MIRROR, pivot, BlockPos.ZERO, template, "obsidian", 0.8);
	}

	/**
	 * Outposts start with an empty base plate at the chunk origin; the tower hangs off its jigsaw. For every plate
	 * rotation and jigsaw, the child that can attach lands at a fixed offset with a fixed rotation.
	 */
	private void addOutpostChildren(Worldgen worldgen) {
		var structures = worldgen.registries().lookupOrThrow(Registries.STRUCTURE);
		var pools = worldgen.registries().lookupOrThrow(Registries.TEMPLATE_POOL);
		Optional<Structure> s = structures.getOptional(Identifier.withDefaultNamespace("pillager_outpost"));
		if (s.isEmpty() || !(s.get() instanceof JigsawStructure jigsaw)) {
			return;
		}
		Map<String, List<ChildPlacement>> byTemplate = new HashMap<>();
		for (Pair<StructurePoolElement, Integer> plateEntry : jigsaw.getStartPool().value().getTemplates()) {
			if (!(plateEntry.getFirst() instanceof SinglePoolElement plate)) {
				continue;
			}
			StructureTemplate plateTemplate = worldgen.templates().getOrCreate(plate.getTemplateLocation());
			for (Rotation startRotation : Rotation.values()) {
				for (StructureTemplate.JigsawBlockInfo parent : plateTemplate.getJigsaws(BlockPos.ZERO, startRotation)) {
					var pool = pools.get(parent.pool());
					if (pool.isEmpty()) {
						continue;
					}
					BlockPos target = parent.info().pos().relative(net.minecraft.world.level.block.JigsawBlock.getFrontFacing(parent.info().state()));
					for (Pair<StructurePoolElement, Integer> childEntry : pool.get().value().getTemplates()) {
						if (!(childEntry.getFirst() instanceof SinglePoolElement child)) {
							continue;
						}
						StructureTemplate childTemplate = worldgen.templates().getOrCreate(child.getTemplateLocation());
						for (Rotation rotation : Rotation.values()) {
							for (StructureTemplate.JigsawBlockInfo joint : childTemplate.getJigsaws(BlockPos.ZERO, rotation)) {
								if (net.minecraft.world.level.block.JigsawBlock.canAttach(parent, joint)) {
									BlockPos offset = target.subtract(joint.info().pos());
									byTemplate.computeIfAbsent(child.getTemplateLocation().getPath(), k -> new ArrayList<>())
											.add(new ChildPlacement(startRotation, rotation, offset));
								}
							}
						}
					}
				}
			}
		}
		for (Map.Entry<String, List<ChildPlacement>> e : byTemplate.entrySet()) {
			StructureTemplate template = worldgen.templates().getOrCreate(Identifier.withDefaultNamespace(e.getKey()));
			add(StructureKind.PILLAGER_OUTPOST, "pillager_outpost", e.getKey(), Rule.CHILD, BlockPos.ZERO, BlockPos.ZERO, template, null, 0.75,
					List.copyOf(e.getValue()));
		}
	}

	private void add(StructureKind kind, String structureId, String path, Rule rule, BlockPos pivot, BlockPos offset, StructureTemplate template,
			String forcedAnchor, double threshold) {
		add(kind, structureId, path, rule, pivot, offset, template, forcedAnchor, threshold, List.of());
	}

	private void add(StructureKind kind, String structureId, String path, Rule rule, BlockPos pivot, BlockPos offset, StructureTemplate template,
			String forcedAnchor, double threshold, List<ChildPlacement> placements) {
		List<TemplateBlock> all = new ArrayList<>();
		Map<String, Integer> counts = new HashMap<>();
		for (StructureTemplate.StructureBlockInfo info : blocks(template)) {
			String canon = Canon.of(BuiltInRegistries.BLOCK.getKey(info.state().getBlock()).getPath());
			if (Canon.ignored(canon)) {
				continue;
			}
			if (forcedAnchor != null && !canon.equals(forcedAnchor)) {
				continue;
			}
			all.add(new TemplateBlock(info.pos().getX(), info.pos().getY(), info.pos().getZ(), canon));
			counts.merge(canon, 1, Integer::sum);
		}
		String anchor = forcedAnchor;
		if (anchor == null) {
			int best = Integer.MAX_VALUE;
			for (Map.Entry<String, Integer> e : counts.entrySet()) {
				if (Canon.anchorable(e.getKey()) && e.getValue() < best) {
					best = e.getValue();
					anchor = e.getKey();
				}
			}
		}
		if (anchor == null) {
			int best = Integer.MAX_VALUE;
			for (Map.Entry<String, Integer> e : counts.entrySet()) {
				if (Canon.fallbackAnchor(e.getKey()) && e.getValue() < best) {
					best = e.getValue();
					anchor = e.getKey();
				}
			}
		}
		if (anchor == null || all.size() < 8) {
			return;
		}
		// Deterministic subsample keeps scoring cheap on big templates; anchors always stay in.
		List<TemplateBlock> signature = new ArrayList<>();
		List<TemplateBlock> rest = new ArrayList<>();
		for (TemplateBlock b : all) {
			(b.canon.equals(anchor) ? signature : rest).add(b);
		}
		java.util.Collections.shuffle(rest, new Random(path.hashCode()));
		for (TemplateBlock b : rest) {
			if (signature.size() >= MAX_SIGNATURE) {
				break;
			}
			signature.add(b);
		}
		java.util.Set<String> biomes = new java.util.HashSet<>();
		for (String id : kind.structures) {
			biomes.addAll(biomesByStructure.getOrDefault(id, java.util.Set.of()));
		}
		specs.add(new Spec(kind, structureId, path, rule, pivot, offset, template.getSize(), List.copyOf(signature), anchor, threshold,
				StartRules.ROTATION_FIRST.contains(structureId), java.util.Set.copyOf(biomes), placements));
	}
}
