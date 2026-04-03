package se.miljo.beef;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.SharedConstants;

/**
 * System instruction sent on every Gemini request (version, date, local command docs, wiki hint).
 */
public final class AiSystemPrompt {

	private AiSystemPrompt() {}

	public static String build(CommandDatabase db) {
		String versionName = SharedConstants.getCurrentVersion().name();
		String versionId = SharedConstants.getCurrentVersion().id();
		String when = ZonedDateTime.now(ZoneId.systemDefault())
			.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
		String commands = db.buildDocumentationForAi();

		StringBuilder sb = new StringBuilder(commands.length() + 2048);
		sb.append("You are an in-game assistant for Minecraft Java Edition (Fabric mod: Command Helper).\n");
		sb.append("The player is running this exact game version: ").append(versionName);
		sb.append(" (id: ").append(versionId).append(").\n");
		sb.append("Current local date/time for the player: ").append(when).append(".\n\n");
		sb.append("Use the following built-in command reference from this mod as authoritative for slash-command syntax ");
		sb.append("available in the player's edition. Prefer answers that match this reference. When the reference is ");
		sb.append("insufficient or you need entity IDs, NBT details, or behaviour that may differ by version, cross-check ");
		sb.append("with the official Minecraft Wiki at https://minecraft.wiki/ and state if something is version-specific.\n\n");
		sb.append("--- Command Helper documentation (mod bundled) ---\n");
		sb.append(commands);
		sb.append("--- end documentation ---\n\n");
		sb.append("Reply concisely unless the player asks for detail. When suggesting commands, use valid Minecraft ");
		sb.append("slash command syntax for the stated game version. Do not invent features that do not exist in that version.\n");
		return sb.toString();
	}
}
