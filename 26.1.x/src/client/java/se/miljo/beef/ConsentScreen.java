package se.miljo.beef;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Full-screen consent dialog for the AI assistant.
 * Link phrases use vanilla {@link PlainTextButton} so hit-testing matches rendering.
 */
public class ConsentScreen extends Screen {

    private static final String TOS_URL = "https://policies.google.com/terms";
    private static final String PP_URL  = "https://policies.google.com/privacy";

    private final Screen parent;

    public ConsentScreen(Screen parent) {
        super(Component.literal("AI Assistant \u2014 Consent"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx   = this.width / 2;
        int midY = this.height / 2;

        int lineH  = 11;
        int startY = midY - 50;

        addLine(Component.literal("By using this assistant, your input will be sent to"),
                cx, startY);
        addLine(Component.literal("Google\u2019s AI service (Gemini) to generate responses."),
                cx, startY + lineH);
        addLine(Component.literal("Do you agree to this data being processed according to"),
                cx, startY + lineH * 2);

        String pre   = "Google\u2019s ";
        String tos   = "Terms of Service";
        String mid   = " and ";
        String pp    = "Privacy Policy";
        String post  = "?";

        int y4 = startY + lineH * 3;
        int preW  = this.font.width(pre);
        int tosW  = this.font.width(tos);
        int midW  = this.font.width(mid);
        int ppW   = this.font.width(pp);
        int postW = this.font.width(post);
        int fullW = preW + tosW + midW + ppW + postW;
        int x4 = cx - fullW / 2;
        int h = this.font.lineHeight;

        addRenderableOnly(new StringWidget(
                x4, y4, preW, h, Component.literal(pre), this.font));

        addRenderableWidget(new PlainTextButton(
                x4 + preW, y4, tosW, h,
                Component.literal(tos).withStyle(ChatFormatting.AQUA),
                btn -> ClientUriHelper.openInBrowser(TOS_URL),
                this.font));

        addRenderableOnly(new StringWidget(
                x4 + preW + tosW, y4, midW, h, Component.literal(mid), this.font));

        addRenderableWidget(new PlainTextButton(
                x4 + preW + tosW + midW, y4, ppW, h,
                Component.literal(pp).withStyle(ChatFormatting.AQUA),
                btn -> ClientUriHelper.openInBrowser(PP_URL),
                this.font));

        addRenderableOnly(new StringWidget(
                x4 + preW + tosW + midW + ppW, y4, postW, h, Component.literal(post), this.font));

        int btnY = midY + 10;
        addRenderableWidget(Button.builder(
                Component.literal("I Agree"),
                btn -> onAgree())
            .pos(cx - 84, btnY)
            .size(80, 20)
            .build());

        addRenderableWidget(Button.builder(
                Component.literal("I Disagree"),
                btn -> onDisagree())
            .pos(cx + 4, btnY)
            .size(80, 20)
            .build());
    }

    private void onAgree() {
        AIAssistantState.get().state = AIAssistantState.State.INITIALIZING;
        this.minecraft.setScreen(parent);
    }

    private void onDisagree() {
        AIAssistantState.get().state = AIAssistantState.State.IDLE;
        this.minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        onDisagree();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void addLine(Component text, int cx, int y) {
        int w = this.font.width(text);
        addRenderableOnly(new StringWidget(
                cx - w / 2, y, w, this.font.lineHeight, text, this.font));
    }
}
