package se.miljo.beef;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class AIConfig {
	private static final String CONFIG_FILE = "commandhelper.json";
	private static final String KEY_FIELD = "api_key";
	private static final String GEO_WARNING_ACK_FIELD = "geo_gemini_warning_acknowledged";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private AIConfig() {}

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
	}

	private static JsonObject loadJsonOrEmpty() {
		Path path = configPath();
		if (!Files.exists(path)) {
			return new JsonObject();
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			JsonObject obj = GSON.fromJson(reader, JsonObject.class);
			return obj != null ? obj : new JsonObject();
		} catch (IOException | JsonParseException e) {
			CommandHelper.LOGGER.error("Failed to load AI config: {}", e.getMessage());
			return new JsonObject();
		}
	}

	private static void writeJson(JsonObject obj) {
		try (Writer writer = Files.newBufferedWriter(configPath(), StandardCharsets.UTF_8)) {
			GSON.toJson(obj, writer);
		} catch (IOException e) {
			CommandHelper.LOGGER.error("Failed to save AI config: {}", e.getMessage());
		}
	}

	/** Returns the stored API key, or {@code null} if not set or blank. Never logs the key. */
	public static String load() {
		JsonObject obj = loadJsonOrEmpty();
		if (!obj.has(KEY_FIELD)) {
			return null;
		}
		String key = obj.get(KEY_FIELD).getAsString();
		return (key == null || key.isBlank()) ? null : key;
	}

	/** Persists the API key; preserves other fields (e.g. geo warning flag). Never logs the key value. */
	public static void save(String apiKey) {
		JsonObject obj = loadJsonOrEmpty();
		if (apiKey == null || apiKey.isBlank()) {
			obj.addProperty(KEY_FIELD, "");
		} else {
			obj.addProperty(KEY_FIELD, apiKey);
		}
		writeJson(obj);
	}

	/** Clears the stored API key; preserves other fields. */
	public static void clear() {
		JsonObject obj = loadJsonOrEmpty();
		obj.addProperty(KEY_FIELD, "");
		writeJson(obj);
	}

	/** Returns true if a non-blank API key is stored. */
	public static boolean hasKey() {
		return load() != null;
	}

	/** User acknowledged the Gemini regional / billing notice (won't auto-show before API Settings). */
	public static boolean isGeoWarningAcknowledged() {
		JsonObject obj = loadJsonOrEmpty();
		return obj.has(GEO_WARNING_ACK_FIELD) && obj.get(GEO_WARNING_ACK_FIELD).getAsBoolean();
	}

	public static void setGeoWarningAcknowledged(boolean acknowledged) {
		JsonObject obj = loadJsonOrEmpty();
		obj.addProperty(GEO_WARNING_ACK_FIELD, acknowledged);
		writeJson(obj);
	}
}
