package se.miljo.beef.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import se.miljo.beef.OverlayRenderer;

/**
 * Intercepts character input in the same way Fabric API's KeyboardHandlerMixin
 * intercepts keyPress: by wrapping the Screen.charTyped invokevirtual inside
 * KeyboardHandler.charTyped, so no refMap or Screen override is needed.
 */
@Mixin(KeyboardHandler.class)
public abstract class ChatScreenMixin {

	@WrapOperation(
		method = "charTyped",
		at = @At(value = "INVOKE",
				target = "Lnet/minecraft/client/gui/screens/Screen;charTyped(Lnet/minecraft/client/input/CharacterEvent;)Z")
	)
	private boolean interceptCharTyped(Screen screen, CharacterEvent event, Operation<Boolean> operation) {
		if (screen instanceof ChatScreen && OverlayRenderer.onCharTypedHook(screen, event)) {
			return true;
		}
		return operation.call(screen, event);
	}
}
