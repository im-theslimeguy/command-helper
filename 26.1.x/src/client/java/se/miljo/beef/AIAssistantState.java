package se.miljo.beef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AIAssistantState {

	public enum State {
		IDLE,
		API_KEY_ENTRY,
		CONSENT_SHOWN,
		INITIALIZING,
		READY,
		WAITING_RESPONSE;

		public boolean isAiWindowActive() {
			return this == INITIALIZING || this == READY || this == WAITING_RESPONSE;
		}

		public boolean isOverlayActive() {
			return this != IDLE;
		}
	}

	public enum Role { USER, AI }

	public record ChatMessage(Role role, String text) {}

	// -------------------------------------------------------------------------
	// Singleton
	// -------------------------------------------------------------------------
	private static final AIAssistantState INSTANCE = new AIAssistantState();
	public static AIAssistantState get() { return INSTANCE; }

	// -------------------------------------------------------------------------
	// State
	// -------------------------------------------------------------------------
	public volatile State state = State.IDLE;

	// -------------------------------------------------------------------------
	// Chat history
	// -------------------------------------------------------------------------
	private final List<ChatMessage> history = new ArrayList<>();

	// -------------------------------------------------------------------------
	// Input buffers
	// -------------------------------------------------------------------------
	/** Buffer for the AI chat text input. */
	public final StringBuilder inputBuffer = new StringBuilder();

	/** Buffer for the API key typed in the API_KEY_ENTRY overlay. */
	public final StringBuilder keyBuffer = new StringBuilder();

	// -------------------------------------------------------------------------
	// Timing
	// -------------------------------------------------------------------------
	/** System.currentTimeMillis() when the mouse first entered the fake input box. */
	public long hoverStartMs = 0L;
	/** System.currentTimeMillis() of the last sent message (for cooldown). */
	public long lastSentMs = 0L;

	// -------------------------------------------------------------------------
	// Loading animation  (updated by OverlayRenderer.onTick)
	// -------------------------------------------------------------------------
	private static final long LOADING_FRAME_MS = 400L;
	public int loadingFrame = 0;
	public long lastLoadingFrameMs = 0L;

	/** Vertical scroll of the AI message list (px); 0 = newest at bottom. */
	public int aiChatScrollPx = 0;

	private AIAssistantState() {}

	// -------------------------------------------------------------------------
	// Chat history
	// -------------------------------------------------------------------------
	public List<ChatMessage> getHistory() {
		return Collections.unmodifiableList(history);
	}

	public void addMessage(Role role, String text) {
		history.add(new ChatMessage(role, text));
	}

	public void clearHistory() {
		history.clear();
	}

	// -------------------------------------------------------------------------
	// Animation
	// -------------------------------------------------------------------------
	public void tickLoadingFrame() {
		long now = System.currentTimeMillis();
		if (now - lastLoadingFrameMs >= LOADING_FRAME_MS) {
			loadingFrame = (loadingFrame + 1) % 3;
			lastLoadingFrameMs = now;
		}
	}

	public String getLoadingDots() {
		return switch (loadingFrame) {
			case 0 -> ".";
			case 1 -> "..";
			default -> "...";
		};
	}

	// -------------------------------------------------------------------------
	// Cooldown
	// -------------------------------------------------------------------------
	public boolean isCooldownActive() {
		return System.currentTimeMillis() - lastSentMs < 1500L;
	}

	// -------------------------------------------------------------------------
	// Reset
	// -------------------------------------------------------------------------
	public void reset() {
		state = State.IDLE;
		inputBuffer.setLength(0);
		keyBuffer.setLength(0);
		clearHistory();
		hoverStartMs = 0L;
		lastSentMs = 0L;
		loadingFrame = 0;
		lastLoadingFrameMs = 0L;
		aiChatScrollPx = 0;
	}
}
