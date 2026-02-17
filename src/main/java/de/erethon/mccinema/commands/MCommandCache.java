package de.erethon.mccinema.commands;

import de.erethon.bedrock.command.ECommandCache;
import de.erethon.bedrock.plugin.EPlugin;

public class MCommandCache extends ECommandCache {

    public static final String LABEL = "mccinema";

    public MCommandCache(EPlugin plugin) {
        super(LABEL, plugin);
        // Screen management
        addCommand(new ScreenCreateCommand());
        addCommand(new DeleteCommand());
        addCommand(new ListCommand());
        addCommand(new InfoCommand());

        // Playback control
        addCommand(new PlayCommand());
        addCommand(new PauseCommand());
        addCommand(new ResumeCommand());
        addCommand(new StopCommand());
        addCommand(new SeekCommand());

        // Download
        addCommand(new DownloadCommand());

        // Debug
        addCommand(new DebugCommand());

        // Help
        addCommand(new HelpCommand());
    }
}
