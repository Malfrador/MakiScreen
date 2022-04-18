package cat.maki.makiscreen.commands;

import de.erethon.bedrock.command.ECommandCache;
import de.erethon.bedrock.plugin.EPlugin;

public class MakiCommandCache extends ECommandCache {

    public static final String LABEL = "maki";

    public MakiCommandCache(EPlugin plugin) {
        super(LABEL, plugin);
        addCommand(new MainCommand());
        addCommand(new CreateCommand());
    }
}
