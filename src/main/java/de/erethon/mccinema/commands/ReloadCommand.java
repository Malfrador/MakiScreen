package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public class ReloadCommand {

    private final MCCinema plugin = MCCinema.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();


    public void onExecute(CommandSender sender) {
        plugin.reloadConfig();
        sender.sendMessage(MM.deserialize("<green>MCCinema config reloaded."));
    }

}
