package se.miljo.beef;

import java.awt.Desktop;
import java.net.URI;
import net.minecraft.util.Util;

/**
 * Opens https links in the system browser. Uses Minecraft's platform helper first,
 * then {@link Desktop} as fallback (avoids fragile {@code Class.forName} on {@link Util}).
 */
public final class ClientUriHelper {

	private ClientUriHelper() {}

	public static void openInBrowser(String url) {
		URI uri;
		try {
			uri = URI.create(url);
		} catch (IllegalArgumentException e) {
			CommandHelper.LOGGER.error("CH: Invalid URL: {}", url);
			return;
		}
		try {
			Util.getPlatform().openUri(uri);
			return;
		} catch (Throwable t) {
			CommandHelper.LOGGER.warn("CH: Util.getPlatform().openUri failed for {}: {}", url, t.toString());
		}
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(uri);
				return;
			}
		} catch (Throwable t) {
			CommandHelper.LOGGER.error("CH: Desktop.browse failed for {}", url, t);
		}
	}
}
