package se.miljo.beef;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

public final class OverlayRenderer {
	private static final long FADE_DURATION_MS = 160L;
	private static final int TOOLTIP_MAX_LINES = 12;
	private static final int TOOLTIP_PADDING = 6;
	private static final int TOOLTIP_LINE_HEIGHT = 11;

	private static OverlayRenderer INSTANCE;

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

	public OverlayRenderer(CommandDatabase commandDatabase) {
		this.commandDatabase = commandDatabase;
		this.lastFrameMs = System.currentTimeMillis();
		INSTANCE = this;
	}

	public void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		ScreenEvents.AFTER_INIT.register(this::onAfterScreenInit);
	}

	private void onAfterScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
		if (!(screen instanceof ChatScreen chatScreen)) {
			return;
		}
		ScreenEvents.afterExtract(chatScreen).register((s, graphicsExtractor, mouseX, mouseY, tickDelta) ->
			renderTooltipInChat(graphicsExtractor, mouseX, mouseY)
		);
	}

	private void onTick(Minecraft client) {
		if (client.player == null) {
			resetState();
			return;
		}
		if (!(client.screen instanceof ChatScreen)) {
			resetState();
			return;
		}

		String currentToken = getCurrentChatCommandToken(client);
		if (currentToken.isBlank()) {
			resetState();
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
	}

	private void updateFade(boolean show) {
		long now = System.currentTimeMillis();
		float delta = Math.min(1.0F, (float) (now - lastFrameMs) / FADE_DURATION_MS);
		lastFrameMs = now;
		detailsAlpha = show
			? Math.min(1.0F, detailsAlpha + delta)
			: Math.max(0.0F, detailsAlpha - delta);
	}

	private void resetState() {
		detailsAlpha = 0.0F;
		detailsActive = false;
		activeEntry = Optional.empty();
		tooltipPositionSet = false;
		lastKeyDown = false;
	}

	// Called from both the mixin and the afterExtract callback.
	public static void renderTooltipInChat(Object graphics, int mouseX, int mouseY) {
		if (INSTANCE == null || !INSTANCE.detailsActive || INSTANCE.activeEntry.isEmpty()) {
			return;
		}
		if (!INSTANCE.tooltipPositionSet) {
			INSTANCE.frozenMouseX = mouseX;
			INSTANCE.frozenMouseY = mouseY;
			INSTANCE.tooltipPositionSet = true;
		}
		GuiGraphicsExtractor gg = extractGuiGraphicsExtractor(graphics);
		if (gg != null) {
			INSTANCE.renderTooltip(gg);
		}
	}

	private static GuiGraphicsExtractor extractGuiGraphicsExtractor(Object graphics) {
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

	@SuppressWarnings("null")
	private void renderTooltip(GuiGraphicsExtractor gg) {
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
		drawTooltipBox(gg, lines, frozenMouseX, frozenMouseY);
	}

	private void drawTooltipBox(GuiGraphicsExtractor gg, List<String> lines, int cx, int cy) {
		Minecraft mc = Minecraft.getInstance();
		int w = 180;
		for (String line : lines) {
			w = Math.max(w, mc.font.width(line) + TOOLTIP_PADDING * 2);
		}
		int h = TOOLTIP_PADDING * 2 + lines.size() * TOOLTIP_LINE_HEIGHT;

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

		// Solid dark background — no transparency to avoid blur-like darkening.
		gg.fill(left, top, right, bottom, 0xFF0D0D0D);
		// Purple MC-style border
		gg.fill(left,     top - 1,  right,     top,      0xFF5A3E6B);
		gg.fill(left,     bottom,   right,     bottom + 1, 0xFF5A3E6B);
		gg.fill(left - 1, top - 1,  left,      bottom + 1, 0xFF5A3E6B);
		gg.fill(right,    top - 1,  right + 1, bottom + 1, 0xFF5A3E6B);

		if (!loggedDrawMethods) {
			loggedDrawMethods = true;
			StringBuilder sb = new StringBuilder("CH draw methods on ")
				.append(gg.getClass().getSimpleName()).append(": ");
			for (java.lang.reflect.Method m : gg.getClass().getMethods()) {
				String n = m.getName().toLowerCase();
				if (n.contains("draw") || n.contains("text") || n.contains("string") || n.contains("render")) {
					sb.append(m.getName()).append('(').append(m.getParameterCount()).append(") ");
				}
			}
			CommandHelper.LOGGER.info(sb.toString());
		}

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			int color = i == 0 ? 0xFFEED080 : (line.startsWith("  ") ? 0xFFAAAAAA : 0xFFDDDDDD);
			int tx = left + TOOLTIP_PADDING;
			int ty = top + TOOLTIP_PADDING + i * TOOLTIP_LINE_HEIGHT;
			drawTextReflective(gg, mc.font, line, tx, ty, color);
		}
	}

	private static void drawTextReflective(Object g, Object font, String text, int x, int y, int color) {
		for (String name : new String[]{"text", "drawString", "drawText", "renderString", "drawLabel"}) {
			for (java.lang.reflect.Method m : g.getClass().getMethods()) {
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
