package se.miljo.beef;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Full-screen settings page for managing the Gemini API key.
 * Can be opened from Mod Menu (when available) or directly from the in-game overlay.
 * The key entry field is masked with asterisks.
 */
public class AISettingsScreen extends Screen {

	private static final int FIELD_WIDTH  = 240;
	private static final int FIELD_HEIGHT = 20;
	private static final int BTN_WIDTH    = 110;
	private static final int BTN_HEIGHT   = 20;

	private final Screen parent;
	/**
	 * When {@code true}, setting the screen back to {@code parent} will also
	 * advance the AI state to {@link AIAssistantState.State#CONSENT_SHOWN}.
	 */
	private final boolean returnToConsent;

	/** Holds the real API key characters; the EditBox only ever shows asterisks. */
	private final StringBuilder realKey = new StringBuilder();
	private EditBox keyDisplay;
	private Button saveBtn;
	private long savedFeedbackEndMs = 0L;

	private static final Component SAVE_LABEL  = Component.literal("Save");
	private static final Component SAVED_LABEL = Component.literal("✓ Saved").withStyle(ChatFormatting.GREEN);

	// -------------------------------------------------------------------------
	// Constructors
	// -------------------------------------------------------------------------

	/** For use from Mod Menu (no automatic flow transition). */
	public AISettingsScreen(Screen parent) {
		this(parent, false);
	}

	/** @param returnToConsent if {@code true}, saving the key triggers the consent overlay. */
	public AISettingsScreen(Screen parent, boolean returnToConsent) {
		super(Component.literal("Command Helper \u2014 AI Settings"));
		this.parent       = parent;
		this.returnToConsent = returnToConsent;
	}

	/**
	 * Opens AI Settings; if the user has not acknowledged the regional / billing notice yet,
	 * that screen is shown first (same chain as consent-style flow).
	 */
	public static void open(Minecraft mc, Screen parent, boolean returnToConsent) {
		Screen settings = new AISettingsScreen(parent, returnToConsent);
		if (!AIConfig.isGeoWarningAcknowledged()) {
			mc.setScreen(new GeminiGeoWarningScreen(settings));
		} else {
			mc.setScreen(settings);
		}
	}

	Screen navigationParent() {
		return parent;
	}

	boolean returnToConsentFlow() {
		return returnToConsent;
	}

	// -------------------------------------------------------------------------
	// Screen init
	// -------------------------------------------------------------------------

	@Override
	protected void init() {
		String existing = AIConfig.load();
		if (existing != null) {
			realKey.setLength(0);
			realKey.append(existing);
		}

		int cx = this.width  / 2;
		int cy = this.height / 2;

		keyDisplay = new EditBox(
			this.font,
			cx - FIELD_WIDTH / 2,
			cy - 30,
			FIELD_WIDTH,
			FIELD_HEIGHT,
			Component.literal("API key"));
		keyDisplay.setMaxLength(256);
		keyDisplay.setHint(Component.literal("Enter your Gemini API key here"));
		keyDisplay.setValue("*".repeat(realKey.length()));
		addRenderableWidget(keyDisplay);

		// Save
		saveBtn = Button.builder(SAVE_LABEL, btn -> onSave())
			.pos(cx - BTN_WIDTH - 4, cy + 10)
			.size(BTN_WIDTH, BTN_HEIGHT)
			.build();
		addRenderableWidget(saveBtn);

		// Clear
		addRenderableWidget(Button.builder(
			Component.literal("Clear API Key"),
			btn -> onClear())
			.pos(cx + 4, cy + 10)
			.size(BTN_WIDTH, BTN_HEIGHT)
			.build());

		// Done
		addRenderableWidget(Button.builder(
			Component.literal("Done"),
			btn -> onClose())
			.pos(cx - 50, cy + 38)
			.size(100, BTN_HEIGHT)
			.build());

		int geoW = 158;
		int geoH = BTN_HEIGHT;
		addRenderableWidget(Button.builder(
				Component.literal("Regional / billing notice"),
				btn -> {
					if (this.minecraft != null) {
						this.minecraft.setScreen(new GeminiGeoWarningScreen(
							new AISettingsScreen(navigationParent(), returnToConsentFlow())));
					}
				})
			.pos(this.width - geoW - 4, this.height - geoH - 4)
			.size(geoW, geoH)
			.tooltip(Tooltip.create(Component.literal(
				"Show the Gemini API regional and billing limitations screen (EEA, UK, CH).")))
			.build());
	}

	// -------------------------------------------------------------------------
	// Masked input handling
	// -------------------------------------------------------------------------

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (keyDisplay != null && keyDisplay.isFocused()) {
			char chr = (char) event.codepoint();
			if (realKey.length() < 256) {
				realKey.append(chr);
				keyDisplay.setValue("*".repeat(realKey.length()));
				moveCursorEnd(keyDisplay);
			}
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int keyCode = event.key();
		if (keyDisplay != null && keyDisplay.isFocused()) {
			if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
				if (realKey.length() > 0) {
					realKey.deleteCharAt(realKey.length() - 1);
					keyDisplay.setValue("*".repeat(realKey.length()));
					moveCursorEnd(keyDisplay);
				}
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_V && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
				String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
				if (clip != null && !clip.isBlank()) {
					for (char c : clip.toCharArray()) {
						if (c >= 32 && realKey.length() < 256) {
							realKey.append(c);
						}
					}
					keyDisplay.setValue("*".repeat(realKey.length()));
					moveCursorEnd(keyDisplay);
				}
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				onSave();
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
				onClose();
				return true;
			}
			return true;
		}
		return super.keyPressed(event);
	}

	// -------------------------------------------------------------------------
	// Actions
	// -------------------------------------------------------------------------

	private void onSave() {
		String key = realKey.toString().trim();
		if (!key.isEmpty()) {
			AIConfig.save(key);
			if (saveBtn != null) {
				saveBtn.setMessage(SAVED_LABEL);
			}
			savedFeedbackEndMs = System.currentTimeMillis() + 2000L;
		}
		// Does NOT close the screen — Done button closes it.
	}

	@Override
	public void tick() {
		super.tick();
		if (savedFeedbackEndMs > 0 && System.currentTimeMillis() >= savedFeedbackEndMs) {
			savedFeedbackEndMs = 0;
			if (saveBtn != null) {
				saveBtn.setMessage(SAVE_LABEL);
			}
		}
	}

	private void onClear() {
		AIConfig.clear();
		realKey.setLength(0);
		if (keyDisplay != null) {
			keyDisplay.setValue("");
		}
	}

	@Override
	public void onClose() {
		closeScreen();
	}

	private void closeScreen() {
		if (this.minecraft != null) {
			if (returnToConsent && AIConfig.hasKey()) {
				this.minecraft.setScreen(new ConsentScreen(parent));
			} else {
				this.minecraft.setScreen(parent);
			}
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private static void moveCursorEnd(EditBox box) {
		try {
			box.getClass().getMethod("moveCursorToEnd", boolean.class).invoke(box, false);
		} catch (NoSuchMethodException e) {
			try {
				box.getClass().getMethod("moveCursorToEnd").invoke(box);
			} catch (ReflectiveOperationException ignored) {}
		} catch (ReflectiveOperationException ignored) {}
	}
}
