package se.miljo.beef;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class CommandDatabase {
	private static final String RESOURCE_PATH = "/data/command-helper/commands.json";
	private static final Gson GSON = new Gson();

	private final Map<String, CommandEntry> entriesByCommand = new HashMap<>();

	private record DatabaseWrapper(List<CommandEntry> commands) {}

	public void load() {
		entriesByCommand.clear();
		try (InputStream stream = CommandDatabase.class.getResourceAsStream(RESOURCE_PATH)) {
			if (stream == null) {
				CommandHelper.LOGGER.warn("Command database resource not found: {}", RESOURCE_PATH);
				return;
			}

			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				DatabaseWrapper wrapper = GSON.fromJson(reader, DatabaseWrapper.class);
				List<CommandEntry> entries = (wrapper != null) ? wrapper.commands() : null;
				if (entries == null || entries.isEmpty()) {
					CommandHelper.LOGGER.warn("Command database is empty.");
					return;
				}

				for (CommandEntry entry : entries) {
					if (entry == null || entry.command == null || entry.command.isBlank()) {
						continue;
					}
					String normalized = normalizeCommand(entry.command);
					entriesByCommand.put(normalized, entry.normalizedCopy());
				}
			}
			CommandHelper.LOGGER.info("Loaded {} command helper entries.", entriesByCommand.size());
		} catch (JsonParseException | IOException ex) {
			CommandHelper.LOGGER.error("Failed to parse command database JSON.", ex);
		}
	}

	public Optional<CommandEntry> findByToken(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(entriesByCommand.get(normalizeCommand(token)));
	}

	public static String normalizeCommand(String raw) {
		String trimmed = raw.trim().toLowerCase(Locale.ROOT);
		if (!trimmed.startsWith("/")) {
			return "/" + trimmed;
		}
		return trimmed;
	}

	public record CommandEntry(String command, String description, List<String> parameters, List<String> examples) {
		public CommandEntry normalizedCopy() {
			return new CommandEntry(
				command == null ? "" : command.trim(),
				description == null ? "" : description.trim(),
				parameters == null ? Collections.emptyList() : new ArrayList<>(parameters),
				examples == null ? Collections.emptyList() : new ArrayList<>(examples)
			);
		}
	}
}
