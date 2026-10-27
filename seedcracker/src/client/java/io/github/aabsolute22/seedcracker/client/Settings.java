package io.github.aabsolute22.seedcracker.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** User preferences, shared by all worlds. */
public final class Settings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public boolean hud = true;
	/** Line and beacon to the current target. */
	public boolean lines = true;
	/** Markers and thin lines for tracked structure types. */
	public boolean markers = true;
	/** Slime chunk outlines around the player. */
	public boolean slimeChunks = false;
	/** Search radius in blocks for the locator and markers. */
	public int radius = 3000;
	/** Tracked structure kinds (ids), shown with markers and lines in the world. */
	public Set<String> tracked = new LinkedHashSet<>();
	/** How many of the nearest structures of each tracked kind get a line. */
	public int linesPerKind = 2;
	/** Cracking threads; 0 means all cores but one. */
	public int threads = 0;
	/** The solver asks before starting a search expected to take longer than this. */
	public int maxCrackMinutes = 20;
	/** Structures within this distance of the player are marked as visited automatically. */
	public int visitDistance = 24;
	public boolean hideVisited = false;

	public int threadCount() {
		return threads > 0 ? threads : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
	}

	private static Path file() {
		return SeedCrackerClient.configDir().resolve("settings.json");
	}

	public static Settings load() {
		try {
			Path file = file();
			if (Files.exists(file)) {
				Settings s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Settings.class);
				if (s != null) {
					if (s.tracked == null) {
						s.tracked = new LinkedHashSet<>();
					}
					return s;
				}
			}
		} catch (Exception e) {
			SeedCrackerClient.LOG.warn("Could not read settings", e);
		}
		return new Settings();
	}

	public void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			SeedCrackerClient.LOG.warn("Could not save settings", e);
		}
	}
}
