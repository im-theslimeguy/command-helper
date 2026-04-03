package se.miljo.beef;

/**
 * Converts Fabric / GLFW mouse coordinates to GUI-scaled pixels when needed.
 */
public final class ClientUiHelper {

	private ClientUiHelper() {}

	/**
	 * Screen- és Fabric egéresemények (pl. {@code allowMouseClick}) már GUI-skalált pixelekben adják a pozíciót —
	 * ugyanabban a térben vannak a widgetek ({@code EditBox#getX()} stb.), ezért csak kerekítünk.
	 */
	public static int[] mouseToGuiScaled(double mouseX, double mouseY) {
		return new int[]{(int) Math.floor(mouseX), (int) Math.floor(mouseY)};
	}
}
