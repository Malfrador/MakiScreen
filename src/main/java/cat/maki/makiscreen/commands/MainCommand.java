package cat.maki.makiscreen.commands;

import cat.maki.makiscreen.MakiScreen;
import de.erethon.bedrock.chat.MessageUtil;
import de.erethon.bedrock.command.ECommand;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;

public class MainCommand extends ECommand {

    public MainCommand() {
        setCommand("main");
        setPermission("maki.main");
        setPlayerCommand(true);
        setMaxArgs(2);
    }

    @Override
    public void onExecute(String[] strings, CommandSender commandSender) {
        MakiScreen plugin = MakiScreen.getInstance();
        /*if (strings.length > 2) {
            BukkitRunnable asyncPreprocess = new BukkitRunnable() {
                @Override
                public void run() {
                    MessageUtil.sendMessage(commandSender, "Processing...");
                    plugin.getGrabber().preprocess(new File(plugin.getDataFolder() + "/preprocess.mp4"));
                    MessageUtil.sendMessage(commandSender, "Done. Loading...");
                    plugin.getGrabber().videoFile = new File(plugin.getDataFolder() + "/processed.mp4");
                    plugin.getGrabber().prepare();
                    plugin.getGrabber().runTaskTimerAsynchronously(MakiScreen.getInstance(), 0, 1);
                }
            };
            asyncPreprocess.runTaskAsynchronously(plugin);
        } else {*/
            plugin.getGrabber().skippy = Integer.parseInt(strings[1]);
            plugin.getGrabber().prepare();
            plugin.getGrabber().runTaskTimerAsynchronously(MakiScreen.getInstance(), 0, 1);
        //}

    }


}
