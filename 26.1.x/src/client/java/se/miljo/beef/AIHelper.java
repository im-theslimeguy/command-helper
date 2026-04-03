package se.miljo.beef;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class AIHelper {
	private AIHelper() {}

	/**
	 * Sends full conversation to Gemini with system prompt (version, date, command docs, wiki hint).
	 */
	public static CompletableFuture<String> sendConversationAsync(
			CommandDatabase commandDb,
			List<AIAssistantState.ChatMessage> history) {
		String key = AIConfig.load();
		String system = AiSystemPrompt.build(commandDb);
		return GeminiClient.sendChatAsync(system, history, key);
	}
}
