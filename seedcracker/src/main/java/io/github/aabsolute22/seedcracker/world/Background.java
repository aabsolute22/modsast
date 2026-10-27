package io.github.aabsolute22.seedcracker.world;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One low-priority thread for world model work (loading data, locating structures, biome maps). */
public final class Background {
	public static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "seedcracker-world");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY + 1);
		return t;
	});

	private Background() {
	}
}
