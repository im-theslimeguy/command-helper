package se.miljo.beef.mixin.client;

import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import se.miljo.beef.AIAssistantState;

/**
 * Pauses singleplayer while the AI chat panel is open (not the command help tooltip).
 * AI görgetés: Fabric {@code allowMouseScroll} az OverlayRenderer-ben (a fókuszált EditBox előtt fut).
 */
@Mixin(ChatScreen.class)
public class AiChatScreenMixin {

	@Inject(method = "isPauseScreen", at = @At("HEAD"), cancellable = true)
	private void commandHelperPauseWhenAiOpen(CallbackInfoReturnable<Boolean> cir) {
		if (AIAssistantState.get().state.isAiWindowActive()) {
			cir.setReturnValue(true);
		}
	}
}
