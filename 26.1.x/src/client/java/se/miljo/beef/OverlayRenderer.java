package se.miljo.beef;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

public final class OverlayRenderer {

	private record BubbleSeg(AIAssistantState.Role role, int bw, int bh, List<FormattedCharSequence> lines) {}
	private record BubbleLayout(
			int index,
			AIAssistantState.Role role,
			String rawText,
			int x,
			int y,
			int w,
			int h,
			List<FormattedCharSequence> lines) {}

	// -------------------------------------------------------------------------
	// Constants — original tooltip
	// -------------------------------------------------------------------------
	private static final long FADE_DURATION_MS = 160L;
	private static final int TOOLTIP_MAX_LINES = 12;
	private static final int TOOLTIP_PADDING = 6;
	private static final int TOOLTIP_LINE_HEIGHT = 11;

	// -------------------------------------------------------------------------
	// Constants — AI overlay
	// -------------------------------------------------------------------------
	private static final int WIN_MAX_WIDTH = 340;
	private static final int WIN_TOP_OFFSET = 20;
	private static final int WIN_BOTTOM_MARGIN = 72;   // room for vanilla chat + AI input strip
	private static final int MIN_AI_WIN_HEIGHT = 152;
	private static final int INPUT_BOX_H = 20;
	private static final int BUBBLE_PAD = 5;
	private static final int WIN_SIDE_PAD = 8;
	private static final int LINE_H = 11;
	private static final long HOVER_HINT_MS = 3000L;
	private static final long ERROR_DISPLAY_MS = 3000L;
	private static final long CURSOR_BLINK_MS = 530L;
	private static final int MAX_INPUT_CHARS = 4000;
	// Fake-button colours (vanilla-ish)
	private static final int BTN_BG       = 0xFF373737;
	private static final int BTN_BG_HOVER = 0xFF5B5B5B;
	private static final int BTN_BORDER   = 0xFFAAAAAA;
	private static final int BTN_TXT      = 0xFFFFFFFF;
	// Link colour
	private static final int LINK_COLOR   = 0xFF55AAFF;
	// Overlay colours
	private static final int OVERLAY_DIM  = 0xCC000000;
	private static final int PANEL_BG     = 0xEE0D0D0D;
	private static final int PANEL_BORDER = 0xFF5A3E6B;

	// -------------------------------------------------------------------------
	// Singleton
	// -------------------------------------------------------------------------
	private static OverlayRenderer INSTANCE;

	// -------------------------------------------------------------------------
	// Original tooltip state
	// -------------------------------------------------------------------------
	private final CommandDatabase commandDatabase;
	private long lastFrameMs;
	private float detailsAlpha;
	private Optional<CommandDatabase.CommandEntry> activeEntry = Optional.empty();
	private boolean detailsActive;
	private boolean lastKeyDown;
	private boolean tooltipPositionSet;
	private int frozenMouseX;
	private int frozenMouseY;

	private static String lastLoggedToken = "";
	private static boolean lastLoggedKeyDown;
	private static boolean loggedDrawMethods;
	private static boolean loggedRenderCalled;
	private static int debugIdleCount = 0;
	private static int debugTickActiveCount = 0;

	// -------------------------------------------------------------------------
	// AI overlay state (frame data, updated each render)
	// -------------------------------------------------------------------------
	/** Hit rect for the custom-drawn "Ask Assistant" button inside the help tooltip. */
	private int askBtnX, askBtnY, askBtnW, askBtnH;

	/**
	 * Tracks screen instances for which we already registered events.
	 * Uses WeakHashMap so screen instances can be GC'd once Minecraft discards them.
	 * We re-add the button every AFTER_INIT (since init() clears all widgets),
	 * but only register Fabric events once per screen instance to avoid duplicates.
	 */
	private final java.util.Set<Screen> eventsRegisteredFor =
		java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	/** Rects of fake consent buttons, updated every frame. */
	private int agreeX, agreeY, agreeW, agreeH;
	private int disagreeX, disagreeY, disagreeW, disagreeH;
	/** Rect of the "Open Settings" button in API_KEY_ENTRY overlay. */
	private int openSettingsX, openSettingsY, openSettingsW, openSettingsH;
	/** Rect of the ToS/PP links in consent overlay. */
	private int tosX, tosY, tosW, tosH;
	private int ppX, ppY, ppW, ppH;
	/** Rect of the fake AI chat input box. */
	private int inputBoxX, inputBoxY, inputBoxW;

	/** Cursor blink state. */
	private boolean cursorVisible = true;
	private long lastCursorToggleMs = 0L;

	/** Thread-safe pending results from async Gemini calls. */
	private volatile String pendingResponse = null;
	private volatile String pendingError = null;
	/** When {@code true}, {@link #triggerError} clears the whole AI session; when {@code false}, stay in chat. */
	private volatile boolean pendingErrorFatal = false;
	private volatile Boolean pendingInit = null; // true = ready, false = failed

	/** Vanilla {@link EditBox} registered on {@link ChatScreen} for real focus, typing, and selection. */
	private EditBox aiChatInput;
	/** Inline selectable textbox shown in-place of one AI bubble. */
	private MultiLineEditBox aiBubbleTextBox;
	private int aiBubbleTextBoxIndex = -1;
	private int aiBubbleTextBoxX, aiBubbleTextBoxY, aiBubbleTextBoxW, aiBubbleTextBoxH;
	/** AI chat panel bounds (GUI px) for click-through vs consume. */
	private int aiWinX, aiWinY, aiWinW, aiWinH;
	private boolean aiChatPendingFocus;

	/** Error overlay data. */
	private String errorMessage = null;
	private long errorShownMs = 0L;

	/** Whether initialization was already started for the current INITIALIZING phase. */
	private boolean initStarted = false;

	/** Message list viewport (for wheel scrolling); updated each frame while AI chat is open. */
	private int aiMsgAreaTop, aiMsgAreaBottom, aiMsgAreaX, aiMsgAreaX2;
	private int aiChatScrollMax;

	/**
	 * Called by ChatScreenMixin when a character is typed in the chat screen.
	 * Returns {@code true} if the character was consumed by the AI overlay
	 * (so the mixin can cancel the event and prevent the chat EditBox from seeing it).
	 */
	public static boolean onCharTypedHook(Screen screen, CharacterEvent event) {
		if (INSTANCE == null) return false;
		AIAssistantState st = AIAssistantState.get();
		if (!st.state.isAiWindowActive()) return false;
		if (screen instanceof ChatScreen cs
				&& st.state == AIAssistantState.State.READY
				&& (cs.getFocused() == INSTANCE.aiChatInput || cs.getFocused() == INSTANCE.aiBubbleTextBox)) {
			return false;
		}
		return true;
	}

	/**
	 * Mouse wheel over the AI message viewport; returns {@code true} if consumed.
	 */
	public static boolean consumeAiChatScroll(double mouseX, double mouseY, double horizontalDelta, double verticalDelta) {
		if (INSTANCE == null) return false;
		AIAssistantState st = AIAssistantState.get();
		if (!st.state.isAiWindowActive() || st.state == AIAssistantState.State.INITIALIZING) {
			return false;
		}
		int[] p = ClientUiHelper.mouseToGuiScaled(mouseX, mouseY);
		int mx = p[0];
		int my = p[1];
		if (INSTANCE.aiBubbleTextBox != null
				&& inRect(mx, my, INSTANCE.aiBubbleTextBoxX, INSTANCE.aiBubbleTextBoxY,
					INSTANCE.aiBubbleTextBoxW, INSTANCE.aiBubbleTextBoxH)) {
			return false;
		}
		int mw = INSTANCE.aiMsgAreaX2 - INSTANCE.aiMsgAreaX;
		int mh = INSTANCE.aiMsgAreaBottom - INSTANCE.aiMsgAreaTop;
		if (mw <= 0 || mh <= 0 || !inRect(mx, my, INSTANCE.aiMsgAreaX, INSTANCE.aiMsgAreaTop, mw, mh)) {
			return false;
		}
		// MC / GLFW: verticalAmount > 0 ≈ „scroll up” — Windows-jellegű listanézet (nem természetes görgetés).
		int step = (int) (verticalDelta * 18);
		st.aiChatScrollPx = Mth.clamp(st.aiChatScrollPx + step, 0, Math.max(0, INSTANCE.aiChatScrollMax));
		return true;
	}

	// -------------------------------------------------------------------------
	// Constructor
	// -------------------------------------------------------------------------
	public OverlayRenderer(CommandDatabase commandDatabase) {
		this.commandDatabase = commandDatabase;
		this.lastFrameMs = System.currentTimeMillis();
		INSTANCE = this;
	}

	// -------------------------------------------------------------------------
	// Initialization
	// -------------------------------------------------------------------------
	public void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		// When a screen is about to re-init, remove it so events get re-registered in AFTER_INIT.
		ScreenEvents.BEFORE_INIT.register((client, screen, w, h) -> eventsRegisteredFor.remove(screen));
		ScreenEvents.AFTER_INIT.register(this::onAfterScreenInit);
	}

	// -------------------------------------------------------------------------
	// Screen init — add API Settings button + register events
	// -------------------------------------------------------------------------
	private void onAfterScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
		if (!(screen instanceof ChatScreen chatScreen)) {
			return;
		}

		// "API Settings" native button — always present, bottom-right above chat input
		int bW = 90;
		int bH = 20;
		int bX = chatScreen.width - bW - 2;
		int bY = chatScreen.height - bH - 26;   // above the vanilla chat EditBox

		Button apiBtn = Button.builder(
				Component.literal("API Settings"),
				btn -> AISettingsScreen.open(Minecraft.getInstance(),
					Minecraft.getInstance().screen, false))
			.pos(bX, bY)
			.size(bW, bH)
			.build();

		addButtonToScreen(chatScreen, apiBtn);

		// Register Fabric events only once per screen instance
		if (eventsRegisteredFor.contains(chatScreen)) {
			return;
		}
		eventsRegisteredFor.add(chatScreen);

		CommandHelper.LOGGER.info("CH: Fabric events regisztrálva: afterExtract + mouse + key ({})", chatScreen.hashCode());

		ScreenEvents.afterExtract(chatScreen).register((s, graphicsExtractor, mouseX, mouseY, tickDelta) ->
			renderTooltipInChat(graphicsExtractor, mouseX, mouseY, tickDelta)
		);

		ScreenMouseEvents.allowMouseClick(chatScreen).register((s, event) ->
			handleMouseClick(event)
		);

		ScreenMouseEvents.allowMouseScroll(chatScreen).register((s, mx, my, h, v) ->
			!consumeAiChatScroll(mx, my, h, v)
		);

		ScreenKeyboardEvents.allowKeyPress(chatScreen).register((s, event) ->
			handleKeyPress(event)
		);
	}

	// -------------------------------------------------------------------------
	// Tick
	// -------------------------------------------------------------------------
	private void onTick(Minecraft client) {
		AIAssistantState st = AIAssistantState.get();

		// Clear error after timeout
		if (errorMessage != null && System.currentTimeMillis() - errorShownMs > ERROR_DISPLAY_MS) {
			errorMessage = null;
		}

		// Flush pending async results onto the main thread
		if (pendingResponse != null) {
			String resp = pendingResponse;
			pendingResponse = null;
			st.addMessage(AIAssistantState.Role.AI, resp);
			st.state = AIAssistantState.State.READY;
			st.aiChatScrollPx = 0;
			aiChatPendingFocus = true;
		}
		if (pendingError != null) {
			String err = pendingError;
			boolean fatal = pendingErrorFatal;
			pendingError = null;
			pendingErrorFatal = false;
			triggerError(err, fatal);
		}
		if (pendingInit != null) {
			boolean ok = pendingInit;
			pendingInit = null;
			if (ok) {
				st.state = AIAssistantState.State.READY;
				aiChatPendingFocus = true;
			} else {
				triggerError("AI initialization failed (unknown). Check logs.", true);
			}
		}

		// Kick off initialization the first time we enter INITIALIZING
		if (st.state == AIAssistantState.State.INITIALIZING) {
			if (!initStarted) {
				initStarted = true;
				startInitialization();
			}
		} else {
			initStarted = false;
		}

		if (!st.state.isAiWindowActive() && aiChatInput != null) {
			removeAiChatInput();
		}
		if (!st.state.isAiWindowActive() && aiBubbleTextBox != null) {
			removeAiBubbleTextBox();
		}

		if (aiChatPendingFocus
				&& st.state == AIAssistantState.State.READY
				&& aiChatInput != null
				&& client.screen instanceof ChatScreen) {
			focusAiChatInput();
			aiChatPendingFocus = false;
		}

		// Tick loading animation
		if (st.state.isAiWindowActive()) {
			st.tickLoadingFrame();
		}

		// Cursor blink
		long now = System.currentTimeMillis();
		if (now - lastCursorToggleMs > CURSOR_BLINK_MS) {
			cursorVisible = !cursorVisible;
			lastCursorToggleMs = now;
		}

		// ---- Original tooltip tick logic ----
		if (st.state != AIAssistantState.State.IDLE) {
			// While AI overlay is open, keep the window "active" so it renders
			detailsActive = true;
			return;
		}

		if (client.player == null) {
			resetTooltipState();
			return;
		}
		if (!(client.screen instanceof ChatScreen)) {
			resetTooltipState();
			return;
		}

		String currentToken = getCurrentChatCommandToken(client);
		if (currentToken.isBlank()) {
			resetTooltipState();
			return;
		}

		activeEntry = commandDatabase.findByToken(currentToken);
		boolean keyDown = KeyInputHandler.isDetailKeyPressed();

		if (!currentToken.equals(lastLoggedToken) || keyDown != lastLoggedKeyDown) {
			lastLoggedToken = currentToken;
			lastLoggedKeyDown = keyDown;
			CommandHelper.LOGGER.info("CH: token='{}' entry={} keyDown={}", currentToken, activeEntry.isPresent(), keyDown);
		}

		if (keyDown && !lastKeyDown) {
			tooltipPositionSet = false;
		}
		lastKeyDown = keyDown;

		updateFade(keyDown);
		detailsActive = detailsAlpha > 0.01F && activeEntry.isPresent();
		if (!detailsActive) {
			tooltipPositionSet = false;
		}
		if (detailsActive && debugTickActiveCount < 3) {
			debugTickActiveCount++;
			CommandHelper.LOGGER.info("CH: onTick detailsActive=TRUE #{}, alpha={}", debugTickActiveCount, detailsAlpha);
		}
	}

	private void startInitialization() {
		AIHelper.sendConversationAsync(commandDatabase,
				List.of(new AIAssistantState.ChatMessage(AIAssistantState.Role.USER,
					"Reply with exactly this single word: OK")))
			.thenAccept(r -> pendingInit = Boolean.TRUE)
			.exceptionally(ex -> {
				String sum = AiErrorSummary.fromThrowable(ex);
				CommandHelper.LOGGER.error("AI initialization failed: {}", sum, ex);
				pendingError = "AI initialization failed (" + sum + "). Check logs.";
				pendingErrorFatal = true;
				return null;
			});
	}

	private void triggerError(String message, boolean fatal) {
		if (fatal) {
			AIAssistantState.get().reset();
			removeAiChatInput();
			removeAiBubbleTextBox();
		} else {
			AIAssistantState.get().state = AIAssistantState.State.READY;
		}
		errorMessage = message;
		errorShownMs = System.currentTimeMillis();
	}

	private void updateFade(boolean show) {
		long now = System.currentTimeMillis();
		float delta = Math.min(1.0F, (float) (now - lastFrameMs) / FADE_DURATION_MS);
		lastFrameMs = now;
		detailsAlpha = show
			? Math.min(1.0F, detailsAlpha + delta)
			: Math.max(0.0F, detailsAlpha - delta);
	}

	private void resetTooltipState() {
		detailsAlpha = 0.0F;
		detailsActive = false;
		activeEntry = Optional.empty();
		tooltipPositionSet = false;
		lastKeyDown = false;
		askBtnW = 0;
	}

	// -------------------------------------------------------------------------
	// Button click
	// -------------------------------------------------------------------------
	private void onAskAssistantClicked() {
		AIAssistantState st = AIAssistantState.get();
		if (st.state != AIAssistantState.State.IDLE) return;
		if (!AIConfig.hasKey()) return; // button should be disabled, but guard anyway

		Minecraft.getInstance().setScreen(new ConsentScreen(Minecraft.getInstance().screen));
	}

	// -------------------------------------------------------------------------
	// Mouse click handler (from ScreenMouseEvents)
	// -------------------------------------------------------------------------
	private boolean handleMouseClick(MouseButtonEvent event) {
		if (event.button() != 0) return true;
		int[] p = ClientUiHelper.mouseToGuiScaled(event.x(), event.y());
		int mx = p[0];
		int my = p[1];
		AIAssistantState st = AIAssistantState.get();
		if (st.state == AIAssistantState.State.IDLE
				&& detailsActive
				&& askBtnW > 0
				&& AIConfig.hasKey()
				&& inRect(mx, my, askBtnX, askBtnY, askBtnW, askBtnH)) {
			onAskAssistantClicked();
			return false;
		}
		if (st.state.isAiWindowActive() && st.state != AIAssistantState.State.INITIALIZING) {
			if (inRect(mx, my, aiWinX, aiWinY, aiWinW, aiWinH)) {
				int pad = 3;
				if (aiBubbleTextBox != null
						&& inRect(mx, my, aiBubbleTextBoxX, aiBubbleTextBoxY, aiBubbleTextBoxW, aiBubbleTextBoxH)) {
					focusAiBubbleTextBox();
					return true;
				}
				// Kattintás az input doboz környékén: fókusz be.
				if (inRect(mx, my, inputBoxX - pad, inputBoxY - pad,
						inputBoxW + pad * 2, INPUT_BOX_H + pad * 2)) {
					removeAiBubbleTextBox();
					focusAiChatInput();
					return true;
				}
				// Kattintás egy AI buborékra: inline kijelölhető textbox ugyanott.
				if (openAiBubbleTextBoxIfHit(mx, my)) {
					return false;
				}
				// Kattintás az AI ablakon belül, de se input, se buborék: fókusz ki, és a click ne essen át.
				if (aiChatInput != null) {
					aiChatInput.setFocused(false);
				}
				removeAiBubbleTextBox();
				return false;
			}
			// AI ablak nyitva van, de a saját rectjén kívül kattintottunk:
			// engedjük tovább a clicket a ChatScreen-nek (pl. másik widgetre).
			return true;
		}
		return true;
	}

	private boolean openAiBubbleTextBoxIfHit(int mx, int my) {
		AIAssistantState st = AIAssistantState.get();
		if (st.getHistory().isEmpty()) return false;

		Minecraft mc = Minecraft.getInstance();
		List<BubbleLayout> layout = buildBubbleLayout(mc.font, st, aiWinX, aiWinY, aiWinW, aiWinH);
		for (BubbleLayout bubble : layout) {
			if (inRect(mx, my, bubble.x(), bubble.y(), bubble.w(), bubble.h())) {
				if (bubble.role() == AIAssistantState.Role.AI) {
					ensureAiBubbleTextBox(mc, bubble);
					return true;
				}
				return false;
			}
		}
		return false;
	}

	// -------------------------------------------------------------------------
	// Keyboard handler
	// -------------------------------------------------------------------------
	private boolean handleKeyPress(KeyEvent event) {
		AIAssistantState st = AIAssistantState.get();
		if (!st.state.isAiWindowActive()) return true;
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			if (aiBubbleTextBox != null) {
				removeAiBubbleTextBox();
				focusAiChatInput();
				return false;
			}
			st.reset();
			removeAiChatInput();
			removeAiBubbleTextBox();
			return false;
		}
		if (aiBubbleTextBox != null && aiBubbleTextBox.isFocused()) {
			return true;
		}
		EditBox box = aiChatInput;
		if (box != null && box.isFocused() && st.state == AIAssistantState.State.READY) {
			if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
				sendAiMessage();
				return false;
			}
			return true;
		}
		return false;
	}

	// -------------------------------------------------------------------------
	// Send message to AI
	// -------------------------------------------------------------------------
	private void sendAiMessage() {
		AIAssistantState st = AIAssistantState.get();
		if (st.state != AIAssistantState.State.READY) return;
		if (aiChatInput == null) return;
		String text = aiChatInput.getValue().trim();
		if (text.isEmpty()) return;
		if (st.isCooldownActive()) return;

		st.addMessage(AIAssistantState.Role.USER, text);
		st.aiChatScrollPx = 0;
		aiChatInput.setValue("");
		removeAiBubbleTextBox();
		st.lastSentMs = System.currentTimeMillis();
		st.state = AIAssistantState.State.WAITING_RESPONSE;

		AIHelper.sendConversationAsync(commandDatabase, st.getHistory())
			.thenAccept(response -> pendingResponse = response)
			.exceptionally(ex -> {
				String sum = AiErrorSummary.fromThrowable(ex);
				CommandHelper.LOGGER.error("AI request failed: {}", sum, ex);
				pendingError = "AI chat request failed (" + sum + "). Check logs.";
				pendingErrorFatal = false;
				return null;
			});
	}

	private void removeAiChatInput() {
		aiChatPendingFocus = false;
		if (aiChatInput != null && Minecraft.getInstance().screen instanceof ChatScreen cs) {
			try {
				Screens.getWidgets(cs).remove(aiChatInput);
			} catch (Exception ignored) {
				// Widget list may have been cleared on resize.
			}
		}
		aiChatInput = null;
	}

	private void removeAiBubbleTextBox() {
		aiBubbleTextBoxIndex = -1;
		aiBubbleTextBoxX = 0;
		aiBubbleTextBoxY = 0;
		aiBubbleTextBoxW = 0;
		aiBubbleTextBoxH = 0;
		if (aiBubbleTextBox != null && Minecraft.getInstance().screen instanceof ChatScreen cs) {
			try {
				Screens.getWidgets(cs).remove(aiBubbleTextBox);
			} catch (Exception ignored) {
				// Widget list may have been cleared on resize.
			}
		}
		aiBubbleTextBox = null;
	}

	private void attachAiChatInput(ChatScreen cs) {
		if (aiChatInput == null) return;
		try {
			var widgets = Screens.getWidgets(cs);
			if (!widgets.contains(aiChatInput)) {
				widgets.add(aiChatInput);
			}
		} catch (Exception e) {
			CommandHelper.LOGGER.warn("CH: could not attach AI EditBox: {}", e.getMessage());
		}
	}

	private void attachAiBubbleTextBox(ChatScreen cs) {
		if (aiBubbleTextBox == null) return;
		try {
			var widgets = Screens.getWidgets(cs);
			if (!widgets.contains(aiBubbleTextBox)) {
				widgets.add(aiBubbleTextBox);
			}
		} catch (Exception e) {
			CommandHelper.LOGGER.warn("CH: could not attach AI bubble textbox: {}", e.getMessage());
		}
	}

	private void focusAiChatInput() {
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.screen instanceof ChatScreen cs) || aiChatInput == null) {
			return;
		}
		try {
			for (var w : Screens.getWidgets(cs)) {
				if (w instanceof EditBox eb && eb != aiChatInput) {
					eb.setFocused(false);
				}
			}
		} catch (Exception ignored) {
			// Fall through — still try to focus AI box.
		}
		aiChatInput.setFocused(true);
		cs.setFocused(aiChatInput);
	}

	private void focusAiBubbleTextBox() {
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.screen instanceof ChatScreen cs) || aiBubbleTextBox == null) {
			return;
		}
		try {
			if (aiChatInput != null) {
				aiChatInput.setFocused(false);
			}
		} catch (Exception ignored) {
			// Keep trying to focus the bubble textbox.
		}
		aiBubbleTextBox.setFocused(true);
		cs.setFocused(aiBubbleTextBox);
	}

	private void ensureAiChatInput(Minecraft mc, int x, int y, int w, int h, AIAssistantState st) {
		if (aiChatInput == null) {
			aiChatInput = new EditBox(mc.font, x, y, w, h, Component.literal("AI message"));
			aiChatInput.setMaxLength(MAX_INPUT_CHARS);
			aiChatInput.setHint(Component.literal("Ask here"));
			aiChatInput.setBordered(true);
			if (st.inputBuffer.length() > 0) {
				aiChatInput.setValue(st.inputBuffer.toString());
				st.inputBuffer.setLength(0);
			}
		}
		aiChatInput.setX(x);
		aiChatInput.setY(y);
		aiChatInput.setWidth(w);
		aiChatInput.setHeight(h);
		aiChatInput.setEditable(st.state == AIAssistantState.State.READY);
		if (mc.screen instanceof ChatScreen cs) {
			attachAiChatInput(cs);
		}
	}

	private void ensureAiBubbleTextBox(Minecraft mc, BubbleLayout bubble) {
		String plainText = MarkdownLite.toComponent(bubble.rawText()).getString();
		if (aiBubbleTextBox == null || aiBubbleTextBoxIndex != bubble.index()) {
			removeAiBubbleTextBox();
			aiBubbleTextBox = MultiLineEditBox.builder()
				.setX(bubble.x())
				.setY(bubble.y())
				.setPlaceholder(Component.literal("AI answer"))
				.setTextColor(0xFFEEEEEE)
				.setTextShadow(false)
				.setCursorColor(0xFFFFFFFF)
				.setShowBackground(true)
				.setShowDecorations(true)
				.build(mc.font, bubble.w(), bubble.h(), Component.literal("AI answer"));
			aiBubbleTextBox.setCharacterLimit(MAX_INPUT_CHARS * 8);
			aiBubbleTextBox.setLineLimit(10_000);
			aiBubbleTextBox.setValue(plainText);
			aiBubbleTextBoxIndex = bubble.index();
			if (mc.screen instanceof ChatScreen cs) {
				attachAiBubbleTextBox(cs);
			}
			focusAiBubbleTextBox();
		}
		aiBubbleTextBoxX = bubble.x();
		aiBubbleTextBoxY = bubble.y();
		aiBubbleTextBoxW = bubble.w();
		aiBubbleTextBoxH = bubble.h();
		aiBubbleTextBox.setX(bubble.x());
		aiBubbleTextBox.setY(bubble.y());
		aiBubbleTextBox.setWidth(bubble.w());
		aiBubbleTextBox.setHeight(bubble.h());
		if (mc.screen instanceof ChatScreen cs) {
			attachAiBubbleTextBox(cs);
		}
	}

	// =========================================================================
	// Main render entry point
	// =========================================================================
	public static void renderTooltipInChat(Object graphics, int mouseX, int mouseY, float tickDelta) {
		if (INSTANCE == null) return;

		GuiGraphicsExtractor gg = extractGuiGraphicsExtractor(graphics);

		if (!loggedRenderCalled) {
			loggedRenderCalled = true;
			CommandHelper.LOGGER.info("CH: renderTooltipInChat első hívás — graphics={} gg={}",
				graphics == null ? "null" : graphics.getClass().getName(),
				gg == null ? "null (HIBA!)" : gg.getClass().getName());
		}

		if (gg == null) return;

		AIAssistantState st = AIAssistantState.get();

		// Error alert takes priority
		if (INSTANCE.errorMessage != null) {
			if (gg != null) INSTANCE.renderError(gg);
			return;
		}

		switch (st.state) {
			case IDLE -> {
				if (debugIdleCount < 3) {
					debugIdleCount++;
					CommandHelper.LOGGER.info("CH: IDLE #{} — detailsActive={} entry={} alpha={}",
						debugIdleCount, INSTANCE.detailsActive, INSTANCE.activeEntry.isPresent(), INSTANCE.detailsAlpha);
				}
				if (!INSTANCE.detailsActive || INSTANCE.activeEntry.isEmpty()) {
					return;
				}
				if (!INSTANCE.tooltipPositionSet) {
					INSTANCE.frozenMouseX = mouseX;
					INSTANCE.frozenMouseY = mouseY;
					INSTANCE.tooltipPositionSet = true;
				}
				if (gg != null) INSTANCE.renderTooltip(gg, mouseX, mouseY);
			}
			// API_KEY_ENTRY and CONSENT_SHOWN are handled by dedicated MC Screens.
			case INITIALIZING, READY, WAITING_RESPONSE -> {
				if (gg != null) INSTANCE.renderAiChatWindow(gg, mouseX, mouseY, tickDelta);
			}
			default -> { /* nothing to draw */ }
		}
	}

	// =========================================================================
	// Disabled button tooltip
	// =========================================================================
	private void renderDisabledButtonTooltip(GuiGraphicsExtractor gg, int mx, int my) {
		// Button is always enabled; no tooltip needed
	}

	// =========================================================================
	// API_KEY_ENTRY overlay — inline key entry (no Mod Menu required)
	// =========================================================================
	private void renderApiKeyEntryOverlay(GuiGraphicsExtractor gg, int mx, int my) {
		AIAssistantState st = AIAssistantState.get();
		Minecraft mc = Minecraft.getInstance();
		int sw = mc.getWindow().getGuiScaledWidth();
		int sh = mc.getWindow().getGuiScaledHeight();

		gg.fill(0, 0, sw, sh, OVERLAY_DIM);

		int panelW = Math.min(320, sw - 40);
		int panelH = 115;
		int panelX = (sw - panelW) / 2;
		int panelY = (sh - panelH) / 2;

		drawPanel(gg, panelX, panelY, panelW, panelH);

		int tx = panelX + WIN_SIDE_PAD;
		int ly = panelY + WIN_SIDE_PAD;

		drawTextReflective(gg, mc.font, "Enter your Gemini API key:", tx, ly, 0xFFFFCC44);
		ly += LINE_H + 3;

		// Link line
		String linkPre  = "Get a free key at ";
		String linkText = "aistudio.google.com/api-keys";
		drawTextReflective(gg, mc.font, linkPre, tx, ly, 0xFFAAAAAA);
		int linkX = tx + mc.font.width(linkPre);
		tosX = linkX; tosY = ly; tosW = mc.font.width(linkText); tosH = mc.font.lineHeight;
		drawTextReflective(gg, mc.font, linkText, linkX, ly, LINK_COLOR);
		gg.fill(linkX, ly + mc.font.lineHeight, linkX + tosW, ly + mc.font.lineHeight + 1, LINK_COLOR);
		ly += LINE_H + 6;

		// Fake key input box
		int ibW = panelW - WIN_SIDE_PAD * 2;
		int ibH = INPUT_BOX_H;
		int ibX = tx;
		int ibY = ly;

		// Store rect for click/key focus detection (reuse inputBoxX/Y)
		inputBoxX = ibX;
		inputBoxY = ibY;
		inputBoxW = ibW;

		gg.fill(ibX, ibY, ibX + ibW, ibY + ibH, 0xFF1A1A1A);
		gg.fill(ibX, ibY, ibX + ibW, ibY + 1, 0xFF888888);
		gg.fill(ibX, ibY + ibH - 1, ibX + ibW, ibY + ibH, 0xFF888888);
		gg.fill(ibX, ibY, ibX + 1, ibY + ibH, 0xFF888888);
		gg.fill(ibX + ibW - 1, ibY, ibX + ibW, ibY + ibH, 0xFF888888);

		// Display asterisks for key
		String masked = "*".repeat(st.keyBuffer.length());
		String clipped = clipToWidth(masked, ibW - 8, mc);
		int keyTxtY = ibY + (ibH - mc.font.lineHeight) / 2;
		String placeholder = "Paste your API key here";
		if (masked.isEmpty()) {
			drawTextReflective(gg, mc.font, placeholder, ibX + 4, keyTxtY, 0xFF666666);
		} else {
			drawTextReflective(gg, mc.font, clipped, ibX + 4, keyTxtY, 0xFFFFFFFF);
			// cursor
			if (cursorVisible) {
				int cx = ibX + 4 + mc.font.width(clipped);
				drawTextReflective(gg, mc.font, "|", cx, keyTxtY, 0xFFFFFFFF);
			}
		}
		ly += ibH + 6;

		// Confirm button
		int btnW = 90;
		int btnH = 18;
		int btnX = panelX + (panelW - btnW * 2 - 8) / 2;
		int btnY = ly;
		openSettingsX = btnX;
		openSettingsY = btnY;
		openSettingsW = btnW;
		openSettingsH = btnH;
		drawFakeButton(gg, btnX, btnY, btnW, btnH, "Confirm", mx, my);

		// Cancel button
		int cancelX = btnX + btnW + 8;
		disagreeX = cancelX; disagreeY = btnY; disagreeW = btnW; disagreeH = btnH;
		drawFakeButton(gg, cancelX, btnY, btnW, btnH, "Cancel", mx, my);
	}

	// =========================================================================
	// CONSENT_SHOWN overlay
	// =========================================================================
	private void renderConsentOverlay(GuiGraphicsExtractor gg, int mx, int my) {
		Minecraft mc = Minecraft.getInstance();
		int sw = mc.getWindow().getGuiScaledWidth();
		int sh = mc.getWindow().getGuiScaledHeight();

		gg.fill(0, 0, sw, sh, OVERLAY_DIM);

		int panelW = Math.min(310, sw - 40);
		int panelX = (sw - panelW) / 2;
		int panelY = sh / 2 - 75;
		int panelH = 150;

		drawPanel(gg, panelX, panelY, panelW, panelH);

		int tx = panelX + WIN_SIDE_PAD;
		int maxTxtW = panelW - WIN_SIDE_PAD * 2;
		int ly = panelY + WIN_SIDE_PAD;

		// First paragraph
		String p1 = "By using this assistant, your input will be sent to";
		String p2 = "Google's AI service (Gemini) to generate responses.";
		String p3 = "Do you agree to this data being processed according to";
		drawTextReflective(gg, mc.font, p1, tx, ly, 0xFFCCCCCC); ly += LINE_H + 1;
		drawTextReflective(gg, mc.font, p2, tx, ly, 0xFFCCCCCC); ly += LINE_H + 1;
		drawTextReflective(gg, mc.font, p3, tx, ly, 0xFFCCCCCC); ly += LINE_H + 1;

		// Links line: "Google's [Terms of Service] and [Privacy Policy]?"
		String pre  = "Google's ";
		String tos  = "Terms of Service";
		String mid  = " and ";
		String pp   = "Privacy Policy";
		String post = "?";

		int lx = tx;
		drawTextReflective(gg, mc.font, pre, lx, ly, 0xFFCCCCCC);
		lx += mc.font.width(pre);

		tosX = lx; tosY = ly; tosW = mc.font.width(tos); tosH = mc.font.lineHeight;
		drawTextReflective(gg, mc.font, tos, lx, ly, LINK_COLOR);
		// Draw underline
		gg.fill(lx, ly + mc.font.lineHeight, lx + tosW, ly + mc.font.lineHeight + 1, LINK_COLOR);
		lx += tosW;

		drawTextReflective(gg, mc.font, mid, lx, ly, 0xFFCCCCCC);
		lx += mc.font.width(mid);

		ppX = lx; ppY = ly; ppW = mc.font.width(pp); ppH = mc.font.lineHeight;
		drawTextReflective(gg, mc.font, pp, lx, ly, LINK_COLOR);
		gg.fill(lx, ly + mc.font.lineHeight, lx + ppW, ly + mc.font.lineHeight + 1, LINK_COLOR);
		lx += ppW;
		drawTextReflective(gg, mc.font, post, lx, ly, 0xFFCCCCCC);
		ly += LINE_H + 8;

		// Buttons
		int btnW = 80;
		int btnH = 18;
		int gap   = 10;
		int totalBtnW = btnW * 2 + gap;
		int btnBaseX = (sw - totalBtnW) / 2;

		agreeX = btnBaseX;         agreeY = ly; agreeW = btnW; agreeH = btnH;
		disagreeX = btnBaseX + btnW + gap; disagreeY = ly; disagreeW = btnW; disagreeH = btnH;

		drawFakeButton(gg, agreeX,    agreeY,    agreeW,    agreeH,    "I agree",    mx, my);
		drawFakeButton(gg, disagreeX, disagreeY, disagreeW, disagreeH, "I disagree", mx, my);
	}

	// =========================================================================
	// AI chat window (INITIALIZING / READY / WAITING_RESPONSE)
	// =========================================================================
	private void renderAiChatWindow(GuiGraphicsExtractor gg, int mx, int my, float tickDelta) {
		AIAssistantState st = AIAssistantState.get();
		Minecraft mc = Minecraft.getInstance();
		int sw = mc.getWindow().getGuiScaledWidth();
		int sh = mc.getWindow().getGuiScaledHeight();

		int ww   = Math.min(WIN_MAX_WIDTH, sw - 40);
		int winX = (sw - ww) / 2;
		int winY = WIN_TOP_OFFSET;
		int winH = Math.max(MIN_AI_WIN_HEIGHT, sh - WIN_TOP_OFFSET - WIN_BOTTOM_MARGIN);
		if (winY + winH > sh - 6) {
			winH = Math.max(MIN_AI_WIN_HEIGHT / 2, sh - winY - 6);
		}
		int winX2 = winX + ww;
		int winY2 = winY + winH;

		aiWinX = winX;
		aiWinY = winY;
		aiWinW = ww;
		aiWinH = winH;

		int pad = WIN_SIDE_PAD;
		int ibX = winX + pad;
		int ibY = winY2 - INPUT_BOX_H - pad;
		int ibW = ww - pad * 2;

		if (st.state == AIAssistantState.State.INITIALIZING) {
			removeAiChatInput();
			removeAiBubbleTextBox();
			drawPanel(gg, winX, winY, ww, winH);
			renderInitializing(gg, winX, winY, ww, winH, mc, st);
		} else {
			List<BubbleLayout> layout = buildBubbleLayout(mc.font, st, winX, winY, ww, winH);
			BubbleLayout selectedBubble = null;
			for (BubbleLayout bubble : layout) {
				if (bubble.index() == aiBubbleTextBoxIndex) {
					selectedBubble = bubble;
					break;
				}
			}
			drawAiPanelWithHole(
				gg, winX, winY, ww, winH,
				ibX, ibY, ibW, INPUT_BOX_H,
				selectedBubble == null ? -1 : selectedBubble.x(),
				selectedBubble == null ? -1 : selectedBubble.y(),
				selectedBubble == null ? 0 : selectedBubble.w(),
				selectedBubble == null ? 0 : selectedBubble.h()
			);
			renderChatContent(gg, mx, my, tickDelta, winX, winY, ww, winH, winX2, winY2, mc, st);
		}
	}

	// ---- Initializing sub-screen ----
	private void renderInitializing(GuiGraphicsExtractor gg, int wx, int wy,
			int ww, int wh, Minecraft mc, AIAssistantState st) {
		int cy = wy + wh / 2;
		String dots = st.getLoadingDots();
		String text = "Initializing assistant";
		int dotsW = mc.font.width(dots);
		int textW = mc.font.width(text);
		drawTextReflective(gg, mc.font, dots, wx + (ww - dotsW) / 2, cy - mc.font.lineHeight - 4, 0xFFAAAAAA);
		drawTextReflective(gg, mc.font, text, wx + (ww - textW) / 2, cy, 0xFFFFFFFF);
	}

	// ---- Chat content sub-screen ----
	private void renderChatContent(GuiGraphicsExtractor gg, int mx, int my, float tickDelta,
			int wx, int wy, int ww, int wh, int wx2, int wy2,
			Minecraft mc, AIAssistantState st) {

		int maxBubbleW = (int) (ww * 0.70) - BUBBLE_PAD * 2;
		int pad = WIN_SIDE_PAD;
		Font font = mc.font;

		int ibX = wx + pad;
		int ibY = wy2 - INPUT_BOX_H - pad;
		int ibW = ww - pad * 2;

		inputBoxX = ibX;
		inputBoxY = ibY;
		inputBoxW = ibW;

		ensureAiChatInput(mc, ibX, ibY, ibW, INPUT_BOX_H, st);

		int msgAreaTop = wy + pad;
		int loadingStripH = st.state == AIAssistantState.State.WAITING_RESPONSE ? (font.lineHeight + 4) : 0;
		int msgAreaBottom = ibY - pad - loadingStripH;

		aiMsgAreaX = wx;
		aiMsgAreaX2 = wx2;
		aiMsgAreaTop = msgAreaTop;
		aiMsgAreaBottom = msgAreaBottom;

		int dotsAreaY = ibY - font.lineHeight - 4;
		if (st.state == AIAssistantState.State.WAITING_RESPONSE) {
			drawTextReflective(gg, font, st.getLoadingDots(), wx + pad, dotsAreaY, 0xFFAAAAAA);
		}

		List<BubbleLayout> layout = buildBubbleLayout(font, st, wx, wy, ww, wh);
		if (aiBubbleTextBoxIndex >= 0) {
			BubbleLayout selectedBubble = null;
			for (BubbleLayout bubble : layout) {
				if (bubble.index() == aiBubbleTextBoxIndex && bubble.role() == AIAssistantState.Role.AI) {
					selectedBubble = bubble;
					break;
				}
			}
			if (selectedBubble != null) {
				ensureAiBubbleTextBox(mc, selectedBubble);
			} else {
				removeAiBubbleTextBox();
			}
		} else {
			removeAiBubbleTextBox();
		}

		int totalH = 0;
		for (int i = 0; i < layout.size(); i++) {
			totalH += layout.get(i).h();
			if (i < layout.size() - 1) {
				totalH += 3;
			}
		}

		int viewportH = Math.max(0, msgAreaBottom - msgAreaTop);
		aiChatScrollMax = Math.max(0, totalH - viewportH);
		st.aiChatScrollPx = Mth.clamp(st.aiChatScrollPx, 0, aiChatScrollMax);

		gg.enableScissor(wx, msgAreaTop, wx2, msgAreaBottom);
		for (BubbleLayout bubble : layout) {
			if (bubble.index() == aiBubbleTextBoxIndex && bubble.role() == AIAssistantState.Role.AI) {
				continue;
			}
			if (bubble.role() == AIAssistantState.Role.USER) {
				gg.fill(bubble.x(), bubble.y(), bubble.x() + bubble.w(), bubble.y() + bubble.h(), 0xFF1A3A5C);
				for (int j = 0; j < bubble.lines().size(); j++) {
					gg.text(font, bubble.lines().get(j), bubble.x() + BUBBLE_PAD,
						bubble.y() + BUBBLE_PAD + j * (LINE_H + 1), 0xFFDDEEFF);
				}
			} else {
				gg.fill(bubble.x(), bubble.y(), bubble.x() + bubble.w(), bubble.y() + bubble.h(), 0xFF2A2A2A);
				for (int j = 0; j < bubble.lines().size(); j++) {
					gg.text(font, bubble.lines().get(j), bubble.x() + BUBBLE_PAD,
						bubble.y() + BUBBLE_PAD + j * (LINE_H + 1), 0xFFEEEEEE);
				}
			}
		}
		gg.disableScissor();

		String inputStr = aiChatInput != null ? aiChatInput.getValue() : "";
		if (inputStr.length() > MAX_INPUT_CHARS - 50) {
			String counter = inputStr.length() + "/" + MAX_INPUT_CHARS;
			int cw = font.width(counter);
			int textY = ibY + (INPUT_BOX_H - font.lineHeight) / 2;
			drawTextReflective(gg, font, counter, ibX + ibW - cw - 4, textY, 0xFFFF5555);
		}
	}

	private List<BubbleLayout> buildBubbleLayout(Font font, AIAssistantState st, int wx, int wy, int ww, int wh) {
		int pad = WIN_SIDE_PAD;
		int maxBubbleW = (int) (ww * 0.70) - BUBBLE_PAD * 2;
		int wy2 = wy + wh;
		int ibY = wy2 - INPUT_BOX_H - pad;
		int loadingStripH = st.state == AIAssistantState.State.WAITING_RESPONSE ? (font.lineHeight + 4) : 0;
		int msgAreaTop = wy + pad;
		int msgAreaBottom = ibY - pad - loadingStripH;
		final int gap = 3;

		List<AIAssistantState.ChatMessage> history = st.getHistory();
		List<BubbleSeg> segs = new ArrayList<>();
		for (AIAssistantState.ChatMessage msg : history) {
			Component styled = MarkdownLite.toComponent(msg.text());
			List<FormattedCharSequence> lines = font.split(styled, maxBubbleW);
			if (lines.isEmpty()) {
				lines = List.of(FormattedCharSequence.EMPTY);
			}
			int tw = 0;
			for (FormattedCharSequence line : lines) {
				tw = Math.max(tw, font.width(line));
			}
			int bw = tw + BUBBLE_PAD * 2;
			int bh = lines.size() * (LINE_H + 1) + BUBBLE_PAD * 2;
			segs.add(new BubbleSeg(msg.role(), bw, bh, lines));
		}

		int totalH = 0;
		for (int i = 0; i < segs.size(); i++) {
			totalH += segs.get(i).bh();
			if (i < segs.size() - 1) {
				totalH += gap;
			}
		}

		int viewportH = Math.max(0, msgAreaBottom - msgAreaTop);
		int scrollMax = Math.max(0, totalH - viewportH);
		int clampedScroll = Mth.clamp(st.aiChatScrollPx, 0, scrollMax);
		int startY = msgAreaBottom - totalH + clampedScroll;

		List<BubbleLayout> out = new ArrayList<>();
		int y = startY;
		for (int i = 0; i < segs.size(); i++) {
			BubbleSeg seg = segs.get(i);
			int bx = seg.role() == AIAssistantState.Role.USER
				? wx + ww - pad - seg.bw()
				: wx + pad;
			out.add(new BubbleLayout(i, history.get(i).role(), history.get(i).text(), bx, y, seg.bw(), seg.bh(), seg.lines()));
			y += seg.bh() + gap;
		}
		return out;
	}

	// =========================================================================
	// Error overlay
	// =========================================================================
	private void renderError(GuiGraphicsExtractor gg) {
		if (errorMessage == null) return;
		Minecraft mc = Minecraft.getInstance();
		int sw = mc.getWindow().getGuiScaledWidth();
		int sh = mc.getWindow().getGuiScaledHeight();

		int pad = 8;
		int maxW = Math.min(300, sw - 40);
		List<String> lines = wrapText(errorMessage, maxW - pad * 2, mc);
		int pw = 0;
		for (String l : lines) pw = Math.max(pw, mc.font.width(l));
		pw += pad * 2;
		int ph = lines.size() * (LINE_H + 1) + pad * 2;

		int px = (sw - pw) / 2;
		int py = sh / 2 - ph / 2;

		gg.fill(px, py, px + pw, py + ph, 0xEE550000);
		gg.fill(px, py,           px + pw, py + 1,      0xFFFF4444);
		gg.fill(px, py + ph - 1,  px + pw, py + ph,     0xFFFF4444);
		gg.fill(px, py,     px + 1, py + ph, 0xFFFF4444);
		gg.fill(px + pw - 1, py, px + pw, py + ph, 0xFFFF4444);

		for (int i = 0; i < lines.size(); i++) {
			int lw = mc.font.width(lines.get(i));
			drawTextReflective(gg, mc.font, lines.get(i), px + (pw - lw) / 2, py + pad + i * (LINE_H + 1), 0xFFFF9999);
		}
	}

	// =========================================================================
	// Original tooltip rendering (unchanged)
	// =========================================================================
	@SuppressWarnings("null")
	private void renderTooltip(GuiGraphicsExtractor gg, int mx, int my) {
		CommandDatabase.CommandEntry entry = activeEntry.get();
		String command = Objects.requireNonNullElse(entry.command(), "-");
		String desc = entry.description();
		if (desc == null || desc.isBlank()) {
			desc = "No description available.";
		}

		List<String> lines = new ArrayList<>();
		lines.add(command);
		lines.add(desc);
		if (!entry.parameters().isEmpty()) {
			lines.add("Parameters:");
			for (String p : entry.parameters()) {
				lines.add("  " + p);
			}
		}
		if (!entry.examples().isEmpty()) {
			lines.add("Examples:");
			for (String ex : entry.examples()) {
				lines.add("  " + ex);
			}
		}
		if (lines.size() > TOOLTIP_MAX_LINES) {
			lines = lines.subList(0, TOOLTIP_MAX_LINES);
		}
		drawTooltipBox(gg, lines, frozenMouseX, frozenMouseY, mx, my);
	}

	private void drawTooltipBox(GuiGraphicsExtractor gg, List<String> lines, int cx, int cy, int mx, int my) {
		Minecraft mc = Minecraft.getInstance();
		int w = 180;
		for (String line : lines) {
			w = Math.max(w, mc.font.width(line) + TOOLTIP_PADDING * 2);
		}

		// Extra height for the custom "Ask Assistant" button at the bottom
		int abtnW = 110;
		int abtnH = 20;
		int abtnTopGap = 6;
		w = Math.max(w, abtnW + TOOLTIP_PADDING * 2 + 4);
		int h = TOOLTIP_PADDING * 2 + lines.size() * TOOLTIP_LINE_HEIGHT + abtnTopGap + abtnH + TOOLTIP_PADDING;

		int sw = mc.getWindow().getGuiScaledWidth();
		int sh = mc.getWindow().getGuiScaledHeight();

		int left = cx + 14;
		int top  = cy - h - 4;
		if (left + w > sw - 4) left = cx - w - 4;
		if (left < 4)          left = 4;
		if (top < 4)           top  = cy + 18;
		if (top + h > sh - 4)  top  = sh - h - 4;

		int right  = left + w;
		int bottom = top  + h;

		if (!loggedDrawMethods) {
			loggedDrawMethods = true;
			StringBuilder sb = new StringBuilder("CH draw methods on ")
				.append(gg.getClass().getSimpleName()).append(": ");
			for (Method m : gg.getClass().getMethods()) {
				String n = m.getName().toLowerCase();
				if (n.contains("draw") || n.contains("text") || n.contains("string") || n.contains("render")) {
					sb.append(m.getName()).append('(').append(m.getParameterCount()).append(") ");
				}
			}
			CommandHelper.LOGGER.info(sb.toString());
		}

		// Calculate Ask Assistant button position BEFORE filling background
		int textBottom = top + TOOLTIP_PADDING + lines.size() * TOOLTIP_LINE_HEIGHT;
		int abtnX = left + (w - abtnW) / 2;
		int abtnY = textBottom + abtnTopGap;
		askBtnX = abtnX; askBtnY = abtnY; askBtnW = abtnW; askBtnH = abtnH;

		gg.fill(left, top, right, bottom, 0xFF0D0D0D);

		// Border around the whole tooltip (including button slot)
		gg.fill(left,     top - 1,  right,     top,          0xFF5A3E6B);
		gg.fill(left,     bottom,   right,     bottom + 1,   0xFF5A3E6B);
		gg.fill(left - 1, top - 1,  left,      bottom + 1,   0xFF5A3E6B);
		gg.fill(right,    top - 1,  right + 1, bottom + 1,   0xFF5A3E6B);

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			int color = i == 0 ? 0xFFEED080 : (line.startsWith("  ") ? 0xFFAAAAAA : 0xFFDDDDDD);
			int tx = left + TOOLTIP_PADDING;
			int ty = top + TOOLTIP_PADDING + i * TOOLTIP_LINE_HEIGHT;
			drawTextReflective(gg, mc.font, line, tx, ty, color);
		}

		// Vékony elválasztó vonal
		gg.fill(left + TOOLTIP_PADDING, textBottom + 2, right - TOOLTIP_PADDING, textBottom + 3, 0xFF3A3A3A);

		boolean hasKey = AIConfig.hasKey();
		if (hasKey) {
			drawFakeButton(gg, abtnX, abtnY, abtnW, abtnH, "Ask Assistant", mx, my);
		} else {
			drawDisabledButton(gg, abtnX, abtnY, abtnW, abtnH, "Ask Assistant");
		}
	}

	// =========================================================================
	// Helpers — drawing
	// =========================================================================
	private void drawPanel(GuiGraphicsExtractor gg, int x, int y, int w, int h) {
		gg.fill(x, y, x + w, y + h, PANEL_BG);
		gg.fill(x,         y,         x + w,     y + 1,         PANEL_BORDER);
		gg.fill(x,         y + h - 1, x + w,     y + h,         PANEL_BORDER);
		gg.fill(x,         y,         x + 1,     y + h,         PANEL_BORDER);
		gg.fill(x + w - 1, y,         x + w,     y + h,         PANEL_BORDER);
	}

	/** Panel background with clear rectangles for the input box and an optional inline AI bubble editor. */
	private void drawAiPanelWithHole(GuiGraphicsExtractor gg, int wx, int wy, int ww, int wh,
			int hole1X, int hole1Y, int hole1W, int hole1H,
			int hole2X, int hole2Y, int hole2W, int hole2H) {
		int wx2 = wx + ww;
		int wy2 = wy + wh;

		int topX = hole1X;
		int topY = hole1Y;
		int topW = hole1W;
		int topH = hole1H;
		int bottomX = hole2X;
		int bottomY = hole2Y;
		int bottomW = hole2W;
		int bottomH = hole2H;
		if (bottomH <= 0 || (topH > 0 && topY > bottomY)) {
			topX = hole2X;
			topY = hole2Y;
			topW = hole2W;
			topH = hole2H;
			bottomX = hole1X;
			bottomY = hole1Y;
			bottomW = hole1W;
			bottomH = hole1H;
		}

		int cursorY = wy;
		if (topH > 0) {
			if (topY > cursorY) {
				gg.fill(wx, cursorY, wx2, topY, PANEL_BG);
			}
			int topY2 = topY + topH;
			gg.fill(wx, topY, topX, topY2, PANEL_BG);
			gg.fill(topX + topW, topY, wx2, topY2, PANEL_BG);
			cursorY = topY2;
		}
		if (bottomH > 0) {
			if (bottomY > cursorY) {
				gg.fill(wx, cursorY, wx2, bottomY, PANEL_BG);
			}
			int bottomY2 = bottomY + bottomH;
			gg.fill(wx, bottomY, bottomX, bottomY2, PANEL_BG);
			gg.fill(bottomX + bottomW, bottomY, wx2, bottomY2, PANEL_BG);
			cursorY = bottomY2;
		}
		if (cursorY < wy2) {
			gg.fill(wx, cursorY, wx2, wy2, PANEL_BG);
		}

		gg.fill(wx,      wy,      wx2,     wy + 1, PANEL_BORDER);
		gg.fill(wx,      wy2 - 1, wx2,     wy2,    PANEL_BORDER);
		gg.fill(wx,      wy,      wx + 1,  wy2,    PANEL_BORDER);
		gg.fill(wx2 - 1, wy,      wx2,     wy2,    PANEL_BORDER);
	}

	private void drawFakeButton(GuiGraphicsExtractor gg, int x, int y, int w, int h,
			String label, int mx, int my) {
		boolean hover = inRect(mx, my, x, y, w, h);
		int bg = hover ? BTN_BG_HOVER : BTN_BG;
		gg.fill(x, y, x + w, y + h, bg);
		gg.fill(x, y,         x + w, y + 1,     BTN_BORDER);
		gg.fill(x, y + h - 1, x + w, y + h,     BTN_BORDER);
		gg.fill(x, y,     x + 1, y + h,     BTN_BORDER);
		gg.fill(x + w - 1, y, x + w, y + h, BTN_BORDER);

		Minecraft mc = Minecraft.getInstance();
		int tw = mc.font.width(label);
		int tx = x + (w - tw) / 2;
		int ty = y + (h - mc.font.lineHeight) / 2;
		drawTextReflective(gg, mc.font, label, tx, ty, BTN_TXT);
	}

	/** Grayed-out, non-interactive version of the fake button. */
	private void drawDisabledButton(GuiGraphicsExtractor gg, int x, int y, int w, int h, String label) {
		gg.fill(x, y, x + w, y + h, 0xFF222222);
		gg.fill(x, y,         x + w, y + 1,     0xFF555555);
		gg.fill(x, y + h - 1, x + w, y + h,     0xFF555555);
		gg.fill(x, y,     x + 1, y + h,     0xFF555555);
		gg.fill(x + w - 1, y, x + w, y + h, 0xFF555555);

		Minecraft mc = Minecraft.getInstance();
		int tw = mc.font.width(label);
		int tx = x + (w - tw) / 2;
		int ty = y + (h - mc.font.lineHeight) / 2;
		drawTextReflective(gg, mc.font, label, tx, ty, 0xFF666666);
	}

	// =========================================================================
	// Helpers — text and layout
	// =========================================================================
	private static List<String> wrapText(String text, int maxWidth, Minecraft mc) {
		List<String> result = new ArrayList<>();
		if (text == null || text.isEmpty()) return result;
		for (String para : text.split("\n", -1)) {
			String[] words = para.split(" ", -1);
			StringBuilder line = new StringBuilder();
			for (String word : words) {
				String candidate = line.isEmpty() ? word : line + " " + word;
				if (mc.font.width(candidate) <= maxWidth) {
					if (!line.isEmpty()) line.append(' ');
					line.append(word);
				} else {
					if (!line.isEmpty()) result.add(line.toString());
					line.setLength(0);
					line.append(word);
				}
			}
			if (!line.isEmpty()) result.add(line.toString());
		}
		return result;
	}

	/** Clips text so it fits within {@code maxWidth} pixels (appends … if clipped). */
	private static String clipToWidth(String text, int maxWidth, Minecraft mc) {
		if (mc.font.width(text) <= maxWidth) return text;
		String ellipsis = "…";
		int ew = mc.font.width(ellipsis);
		StringBuilder sb = new StringBuilder();
		for (char c : text.toCharArray()) {
			if (mc.font.width(sb.toString() + c + ellipsis) > maxWidth) break;
			sb.append(c);
		}
		return sb + ellipsis;
	}

	private static boolean inRect(int px, int py, int rx, int ry, int rw, int rh) {
		return px >= rx && px < rx + rw && py >= ry && py < ry + rh;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void addButtonToScreen(Screen screen, Button button) {
		try {
			Screens.getWidgets(screen).add(button);
		} catch (Exception e) {
			CommandHelper.LOGGER.warn("CH: Could not add button to screen: {}", e.getMessage());
		}
	}

	// =========================================================================
	// Helpers — GuiGraphicsExtractor extraction (existing, unchanged)
	// =========================================================================
	static GuiGraphicsExtractor extractGuiGraphicsExtractor(Object graphics) {
		if (graphics instanceof GuiGraphicsExtractor gg) {
			return gg;
		}
		for (String name : new String[]{"extract", "GuiGraphicsExtractor", "getGuiGraphicsExtractor"}) {
			try {
				Method m = graphics.getClass().getMethod(name);
				Object result = m.invoke(graphics);
				if (result instanceof GuiGraphicsExtractor gg) {
					return gg;
				}
			} catch (ReflectiveOperationException ignored) {
				// Try next method.
			}
		}
		return null;
	}

	// =========================================================================
	// Helpers — reflective text drawing (existing, unchanged)
	// =========================================================================
	static void drawTextReflective(Object g, Object font, String text, int x, int y, int color) {
		for (String name : new String[]{"text", "drawString", "drawText", "renderString", "drawLabel"}) {
			for (Method m : g.getClass().getMethods()) {
				if (!m.getName().equals(name)) continue;
				Class<?>[] p = m.getParameterTypes();
				try {
					if (p.length == 6 && p[1] == String.class) {
						m.invoke(g, font, text, x, y, color, false);
						return;
					}
					if (p.length == 5 && p[1] == String.class) {
						m.invoke(g, font, text, x, y, color);
						return;
					}
					if (p.length == 4 && p[0] == String.class) {
						m.invoke(g, text, x, y, color);
						return;
					}
				} catch (ReflectiveOperationException ignored) {
					// Try next overload.
				}
			}
		}
	}

	// =========================================================================
	// Helpers — chat input extraction (existing, unchanged)
	// =========================================================================
	private String getCurrentChatCommandToken(Minecraft client) {
		if (!(client.screen instanceof ChatScreen chatScreen)) {
			return "";
		}
		String text = extractChatInput(chatScreen);
		if (text == null || text.isBlank() || !text.startsWith("/")) {
			return "";
		}
		String[] parts = text.trim().split("\\s+");
		return parts.length == 0 ? "" : parts[0];
	}

	private String extractChatInput(ChatScreen chatScreen) {
		try {
			for (Field field : chatScreen.getClass().getDeclaredFields()) {
				field.setAccessible(true);
				Object value = field.get(chatScreen);
				if (value instanceof EditBox widget) {
					String text = widget.getValue();
					if (text != null) {
						return text;
					}
				}
			}
			for (Method method : chatScreen.getClass().getMethods()) {
				if (method.getParameterCount() != 0) {
					continue;
				}
				if (method.getReturnType() == String.class) {
					Object value = method.invoke(chatScreen);
					if (value instanceof String text && text.startsWith("/")) {
						return text;
					}
				}
			}
		} catch (ReflectiveOperationException ignored) {
			// Fall through to empty string.
		}
		return "";
	}
}
