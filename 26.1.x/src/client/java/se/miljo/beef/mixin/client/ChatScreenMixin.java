package se.miljo.beef.mixin.client;

import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Placeholder mixin — rendering is done via Fabric ScreenEvents.afterExtract
 * instead of injecting into Screen.render/renderWithTooltip to avoid
 * triggering MC's tooltip blur effect.
 */
@Mixin(Screen.class)
public abstract class ChatScreenMixin {
}
