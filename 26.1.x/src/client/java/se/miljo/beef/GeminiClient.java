package se.miljo.beef;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class GeminiClient {
	/** {@code v1} rejects {@code systemInstruction}; {@code v1beta} accepts it. */
	private static final String ENDPOINT =
		"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=";
	private static final int MAX_SYSTEM_CHARS = 120_000;
	private static final int MAX_TURN_CHARS = 16_000;
	private static final int MAX_HISTORY_TURNS = 48;
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(java.time.Duration.ofSeconds(15))
		.build();

	private GeminiClient() {}

	/**
	 * Sends conversation history with a system instruction. Roles map to Gemini user/model.
	 */
	public static CompletableFuture<String> sendChatAsync(
			String systemInstruction,
			List<AIAssistantState.ChatMessage> history,
			String apiKey) {
		if (apiKey == null || apiKey.isBlank()) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("No API key configured."));
		}
		String sys = truncate(systemInstruction, MAX_SYSTEM_CHARS);
		String body = buildRequestBody(sys, history);
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder()
				.uri(URI.create(ENDPOINT + apiKey))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.timeout(java.time.Duration.ofSeconds(120))
				.build();
		} catch (Exception e) {
			CommandHelper.LOGGER.error("AI request build failed: {}", e.getMessage());
			return CompletableFuture.failedFuture(e);
		}

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.thenApply(response -> {
				int status = response.statusCode();
				if (status != 200) {
					String responseBody = response.body();
					CommandHelper.LOGGER.error(
						"AI request failed: HTTP {} — {}",
						status,
						sanitizeBody(responseBody));
					throw new RuntimeException(formatHttpFailure(status, responseBody));
				}
				return parseResponseText(response.body());
			});
	}

	private static String buildRequestBody(String systemInstruction, List<AIAssistantState.ChatMessage> history) {
		JsonObject sys = new JsonObject();
		JsonArray sysParts = new JsonArray();
		JsonObject sysPart = new JsonObject();
		sysPart.addProperty("text", systemInstruction);
		sysParts.add(sysPart);
		sys.add("parts", sysParts);

		JsonArray contents = new JsonArray();
		List<AIAssistantState.ChatMessage> slice = history;
		if (history.size() > MAX_HISTORY_TURNS) {
			slice = history.subList(history.size() - MAX_HISTORY_TURNS, history.size());
		}
		for (AIAssistantState.ChatMessage msg : slice) {
			String role = msg.role() == AIAssistantState.Role.USER ? "user" : "model";
			String text = truncate(msg.text(), MAX_TURN_CHARS);
			if (text.isBlank()) {
				continue;
			}
			JsonObject part = new JsonObject();
			part.addProperty("text", text);
			JsonArray parts = new JsonArray();
			parts.add(part);
			JsonObject content = new JsonObject();
			content.addProperty("role", role);
			content.add("parts", parts);
			contents.add(content);
		}

		JsonObject body = new JsonObject();
		body.add("systemInstruction", sys);
		body.add("contents", contents);
		return GSON.toJson(body);
	}

	private static String truncate(String s, int max) {
		if (s == null) return "";
		return s.length() > max ? s.substring(0, max) : s;
	}

	private static String parseResponseText(String json) {
		try {
			JsonObject root = GSON.fromJson(json, JsonObject.class);
			if (root == null) {
				throw new RuntimeException("200 EMPTY_JSON");
			}
			if (root.has("promptFeedback")) {
				JsonElement pf = root.get("promptFeedback");
				CommandHelper.LOGGER.warn("AI promptFeedback: {}", sanitizeBody(pf.toString()));
			}
			if (!root.has("candidates") || !root.get("candidates").isJsonArray()
					|| root.getAsJsonArray("candidates").isEmpty()) {
				CommandHelper.LOGGER.error("AI response has no candidates: {}", sanitizeBody(json));
				throw new RuntimeException("200 NO_CANDIDATES");
			}
			JsonObject cand = root.getAsJsonArray("candidates").get(0).getAsJsonObject();
			if (cand.has("finishReason")) {
				String fr = cand.get("finishReason").getAsString();
				if (!"STOP".equals(fr) && !"MAX_TOKENS".equals(fr) && !"OTHER".equals(fr)) {
					CommandHelper.LOGGER.warn("AI finishReason: {}", fr);
				}
			}
			return cand
				.getAsJsonObject("content")
				.getAsJsonArray("parts").get(0).getAsJsonObject()
				.get("text").getAsString();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			CommandHelper.LOGGER.error("AI response parse failed: {}", e.getMessage());
			throw new RuntimeException("200 PARSE_ERROR", e);
		}
	}

	private static String sanitizeBody(String body) {
		if (body == null) return "(empty)";
		return body.length() > 200 ? body.substring(0, 200) + "…" : body;
	}

	/**
	 * Message shape for {@link AiErrorSummary}: {@code "429 RESOURCE_EXHAUSTED"}, {@code "400 INVALID_ARGUMENT"}.
	 */
	static String formatHttpFailure(int status, String body) {
		try {
			JsonObject root = GSON.fromJson(body, JsonObject.class);
			if (root != null && root.has("error") && root.get("error").isJsonObject()) {
				JsonObject err = root.getAsJsonObject("error");
				if (err.has("status")) {
					return status + " " + err.get("status").getAsString();
				}
				if (err.has("code")) {
					return status + " code=" + err.get("code").getAsInt();
				}
			}
		} catch (Exception ignored) {
			// fall through
		}
		return status + " HTTP_" + status;
	}
}
