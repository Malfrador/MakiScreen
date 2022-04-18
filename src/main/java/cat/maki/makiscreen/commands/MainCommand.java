package cat.maki.makiscreen.commands;

import cat.maki.makiscreen.MakiScreen;
import de.erethon.bedrock.command.ECommand;
import de.erethon.bedrock.misc.InfoUtil;
import org.bukkit.command.CommandSender;

public class MainCommand extends ECommand {

    public MainCommand() {
        setCommand("main");
        setPermission("maki.main");
    }

    @Override
    public void onExecute(String[] strings, CommandSender commandSender) {
        InfoUtil.sendListedHelp(commandSender, MakiScreen.getInstance().getCommandCache());
    }


}
