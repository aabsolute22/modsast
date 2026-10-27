package io.github.aabsolute22.seedcracker.world;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * The seed-independent part of vanilla world generation: every worldgen registry (with tags) loaded from the
 * built-in vanilla data pack, exactly as a new server would, plus the structure template manager.
 */
public final class Worldgen {
	private static volatile CompletableFuture<Worldgen> instance;

	private final MultiPackResourceManager resources;
	private final RegistryAccess.Frozen registries;
	private final StructureTemplateManager templates;

	private Worldgen(Path tempDir) throws Exception {
		resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource()));
		LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
		// Tags of the static registries are needed to parse worldgen data but are never applied to the game's registries.
		List<Registry.PendingTags<?>> pending = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
		RegistryAccess.Frozen forLoading = layers.getAccessForLoading(RegistryLayer.WORLDGEN);
		List<HolderLookup.RegistryLookup<?>> lookups = TagLoader.buildUpdatedLookups(forLoading, pending);
		registries = RegistryDataLoader.load(resources, lookups, RegistryDataLoader.WORLDGEN_REGISTRIES, Runnable::run).join();
		Files.createDirectories(tempDir);
		templates = new StructureTemplateManager(resources, LevelStorageSource.createDefault(tempDir).createAccess("templates"),
				DataFixers.getDataFixer(), BuiltInRegistries.BLOCK);
	}

	/** Starts loading in the background (once); later calls return the same future. */
	public static CompletableFuture<Worldgen> get(Path tempDir) {
		CompletableFuture<Worldgen> f = instance;
		if (f == null) {
			synchronized (Worldgen.class) {
				f = instance;
				if (f == null) {
					f = CompletableFuture.supplyAsync(() -> {
						try {
							return new Worldgen(tempDir);
						} catch (Exception e) {
							throw new IllegalStateException("Could not load vanilla worldgen data", e);
						}
					}, Background.EXECUTOR);
					instance = f;
				}
			}
		}
		return f;
	}

	/** The loaded instance, or null while still loading. */
	public static Worldgen now() {
		CompletableFuture<Worldgen> f = instance;
		return f != null && f.isDone() && !f.isCompletedExceptionally() ? f.join() : null;
	}

	public RegistryAccess.Frozen registries() {
		return registries;
	}

	public StructureTemplateManager templates() {
		return templates;
	}

	public MultiPackResourceManager resources() {
		return resources;
	}
}
