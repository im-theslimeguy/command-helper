package se.miljo.beef;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Billing / regional limitation notice for Gemini API (EEA, UK, CH). Shown once before AI Settings
 * until acknowledged; can be reopened from API Settings.
 */
public class GeminiGeoWarningScreen extends Screen {

	private static final String LEARN_MORE_URL =
		"https://ai.google.dev/gemini-api/docs/billing";

	private final Screen nextScreen;

	public GeminiGeoWarningScreen(Screen nextScreen) {
		super(Component.literal("Command Helper \u2014 Gemini regional notice"));
		this.nextScreen = nextScreen;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int y = this.height / 2 - 78;
		int lineH = 11;

		String[] body = new String[] {
			"If you are in the European Economic Area, the United Kingdom, or Switzerland",
			"(EU member states plus Norway, Iceland, and Liechtenstein), or your Google Account",
			"is located there, you will need to connect a billing account to your Gemini API",
			"key in Google Cloud Console.",
			"",
			"If your Google Account is under 18, or you cannot connect a billing account,",
			"you may not be able to use Gemini inside the game.",
			"",
			"If you acknowledge below, this screen will not appear automatically again."
		};

		for (String line : body) {
			if (line.isEmpty()) {
				y += lineH / 2;
				continue;
			}
			Component comp = Component.literal(line);
			int w = this.font.width(comp);
			addRenderableOnly(new StringWidget(cx - w / 2, y, w, this.font.lineHeight, comp, this.font));
			y += lineH;
		}

		y += 4;
		String learnPre = "Learn more about how to do that: ";
		String learnLink = "Learn more";
		int preW = this.font.width(learnPre);
		int linkW = this.font.width(learnLink);
		int rowW = preW + linkW;
		int xRow = cx - rowW / 2;
		int h = this.font.lineHeight;
		addRenderableOnly(new StringWidget(xRow, y, preW, h, Component.literal(learnPre), this.font));
		addRenderableWidget(new PlainTextButton(
			xRow + preW, y, linkW, h,
			Component.literal(learnLink).withStyle(ChatFormatting.AQUA),
			btn -> ClientUriHelper.openInBrowser(LEARN_MORE_URL),
			this.font));

		int btnY = y + lineH + 14;
		addRenderableWidget(Button.builder(
				Component.literal("I acknowledge"),
				btn -> onAcknowledge())
			.pos(cx - 80, btnY)
			.size(160, 20)
			.build());
	}

	private void onAcknowledge() {
		AIConfig.setGeoWarningAcknowledged(true);
		if (this.minecraft != null) {
			this.minecraft.setScreen(nextScreen);
		}
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(nextScreen);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
