package se.miljo.beef;

import net.fabricmc.api.ClientModInitializer;

public class CommandHelperClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CommandDatabase commandDatabase = new CommandDatabase();
		commandDatabase.load();

		KeyInputHandler.initialize();
		new OverlayRenderer(commandDatabase).initialize();
	}
}