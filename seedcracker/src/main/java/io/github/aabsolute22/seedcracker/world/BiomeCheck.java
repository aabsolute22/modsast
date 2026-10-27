package io.github.aabsolute22.seedcracker.world;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import io.github.aabsolute22.seedcracker.core.SeedHash;

/**
 * Finds the top 16 bits of the seed from biomes the client has seen. Only the six climate noises are built for
 * each candidate (the rest of the noise router is replaced by constants), which is what makes 65536 tries cheap.
 */
public final class BiomeCheck {
	/** One biome cell the server sent: quart coordinates and the biome id (without namespace). */
	public record Sample(int quartX, int quartY, int quartZ, String biome) {
	}

	private static final Set<String> CLIMATE = Set.of("temperature", "vegetation", "continents", "erosion", "depth", "ridges");

	private final MultiNoiseBiomeSource biomeSource;
	private final NoiseGeneratorSettings climateSettings;
	private final HolderGetter<NormalNoise.NoiseParameters> noises;
	private final AtomicBoolean cancel = new AtomicBoolean();
	private final AtomicInteger done = new AtomicInteger();

	public BiomeCheck(Worldgen worldgen, SeedWorld.Dim dim) {
		var registries = worldgen.registries();
		this.biomeSource = MultiNoiseBiomeSource.createFromPreset(
				registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(dim.preset));
		this.climateSettings = climateOnly(registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(dim.settings).value());
		this.noises = registries.lookupOrThrow(Registries.NOISE);
	}

	/** A copy of the settings whose router keeps only the climate functions. */
	static NoiseGeneratorSettings climateOnly(NoiseGeneratorSettings settings) {
		try {
			NoiseRouter router = settings.noiseRouter();
			RecordComponent[] parts = NoiseRouter.class.getRecordComponents();
			Object[] values = new Object[parts.length];
			Class<?>[] types = new Class<?>[parts.length];
			for (int i = 0; i < parts.length; i++) {
				types[i] = parts[i].getType();
				values[i] = CLIMATE.contains(parts[i].getName()) ? parts[i].getAccessor().invoke(router) : DensityFunctions.zero();
			}
			Constructor<NoiseRouter> rc = NoiseRouter.class.getDeclaredConstructor(types);
			NoiseRouter reduced = rc.newInstance(values);
			RecordComponent[] sp = NoiseGeneratorSettings.class.getRecordComponents();
			Object[] sv = new Object[sp.length];
			Class<?>[] st = new Class<?>[sp.length];
			for (int i = 0; i < sp.length; i++) {
				st[i] = sp[i].getType();
				sv[i] = sp[i].getType() == NoiseRouter.class ? reduced : sp[i].getAccessor().invoke(settings);
			}
			Constructor<NoiseGeneratorSettings> sc = NoiseGeneratorSettings.class.getDeclaredConstructor(st);
			return sc.newInstance(sv);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("NoiseRouter layout changed", e);
		}
	}

	public Climate.Sampler sampler(long seed) {
		return RandomState.create(climateSettings, noises, seed).sampler();
	}

	public String biome(Climate.Sampler sampler, Sample s) {
		Holder<Biome> b = biomeSource.getNoiseBiome(s.quartX(), s.quartY(), s.quartZ(), sampler);
		return SeedWorld.biomeId(b);
	}

	/** Mismatches allowed: blending near old chunks and edits with /fillbiome should not sink the right seed. */
	public static int tolerance(int samples) {
		return Math.max(1, samples / 12);
	}

	/** Whether {@code seed} reproduces the samples (with {@link #tolerance}). */
	public boolean matches(long seed, List<Sample> samples) {
		Climate.Sampler sampler = sampler(seed);
		int allowed = tolerance(samples.size());
		int bad = 0;
		for (Sample s : samples) {
			if (!biome(sampler, s).equals(s.biome()) && ++bad > allowed) {
				return false;
			}
		}
		return true;
	}

	public void cancel() {
		cancel.set(true);
	}

	public double progress() {
		return done.get() / 65536.0;
	}

	/** All full seeds with these low 48 bits that reproduce the samples. */
	public List<Long> fullSeeds(long structureSeed, List<Sample> samples, int threads) {
		int[] order = SeedHash.upperBitsOrder(structureSeed);
		List<Long> out = java.util.Collections.synchronizedList(new ArrayList<>());
		AtomicInteger next = new AtomicInteger();
		List<Thread> workers = new ArrayList<>();
		long low = structureSeed & ((1L << 48) - 1);
		for (int t = 0; t < Math.max(1, threads); t++) {
			Thread w = new Thread(() -> {
				int i;
				while (!cancel.get() && (i = next.getAndIncrement()) < order.length) {
					long seed = (long) order[i] << 48 | low;
					if (matches(seed, samples)) {
						out.add(seed);
					}
					done.incrementAndGet();
				}
			}, "seedcracker-biomes-" + t);
			w.setDaemon(true);
			w.setPriority(Thread.MIN_PRIORITY);
			w.start();
			workers.add(w);
		}
		for (Thread w : workers) {
			try {
				w.join();
			} catch (InterruptedException e) {
				cancel.set(true);
				Thread.currentThread().interrupt();
			}
		}
		return out;
	}
}
