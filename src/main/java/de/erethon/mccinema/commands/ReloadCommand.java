package de.erethon.mccinema.commands;

import de.erethon.bedrock.command.ECommand;
import de.erethon.mccinema.MCCinema;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.List;

public class ReloadCommand extends ECommand {

    private final MCCinema plugin = MCCinema.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public ReloadCommand() {
        setCommand("reload");
        setPermission("mccinema.reload");
        setPlayerCommand(true);
        setConsoleCommand(true);
        setMinArgs(-1);
        setMaxArgs(-1);
        setHelp("/mcc reload");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        plugin.reloadConfig();
        sender.sendMessage(MM.deserialize("<green>MCCinema config reloaded."));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
