package io.github.aabsolute22.endcityfinder.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** User preferences, shared by all worlds. */
public final class Settings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public boolean worldMarkers = true;
	public boolean hud = true;
	public boolean arrow = true;
	/** Only cities with a ship count as the "nearest" target. */
	public boolean shipsOnly = false;
	/** Radius around the player in which cities are predicted and shown. */
	public int radius = 5000;
	/** Distance below which a city is drawn at its real position with its outline. */
	public int nearDistance = 192;
	/** A city counts as visited once the player gets this close to its base. */
	public int visitDistance = 40;

	private static Path file() {
		return EndCityFinderClient.configDir().resolve("settings.json");
	}

	public static Settings load() {
		try {
			Path file = file();
			if (Files.exists(file)) {
				Settings s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Settings.class);
				if (s != null) {
					return s;
				}
			}
		} catch (Exception e) {
			EndCityFinderClient.LOG.warn("Could not read settings", e);
		}
		return new Settings();
	}

	public void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			EndCityFinderClient.LOG.warn("Could not save settings", e);
		}
	}
}
