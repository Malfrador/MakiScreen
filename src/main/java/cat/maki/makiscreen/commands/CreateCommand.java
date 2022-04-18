package cat.maki.makiscreen.commands;

import cat.maki.makiscreen.ConfigFile;
import cat.maki.makiscreen.ImageManager;
import cat.maki.makiscreen.MakiScreen;
import cat.maki.makiscreen.ScreenPart;
import de.erethon.bedrock.command.ECommand;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

public class CreateCommand extends ECommand {

    MakiScreen plugin = MakiScreen.getInstance();

    public CreateCommand() {
        setCommand("create");
        setDefaultHelp();
        setPermission("maki.create");
        setConsoleCommand(false);
        setPlayerCommand(true);
    }

    @Override
    public void onExecute(String[] strings, CommandSender commandSender) {
        Player player = (Player) commandSender;
        for (int i = 0; i< ConfigFile.getMapSize(); i++) {
            MapView mapView = plugin.getServer().createMap(player.getWorld());
            mapView.setScale(MapView.Scale.CLOSEST);
            mapView.setUnlimitedTracking(true);
            for (MapRenderer renderer : mapView.getRenderers()) {
                mapView.removeRenderer(renderer);
            }

            ItemStack itemStack = new ItemStack(Material.FILLED_MAP);

            MapMeta mapMeta = (MapMeta) itemStack.getItemMeta();
            mapMeta.setMapView(mapView);

            itemStack.setItemMeta(mapMeta);
            player.getInventory().addItem(itemStack);
            plugin.getScreens().add(new ScreenPart(mapView.getId(), i));
            ImageManager manager = ImageManager.getInstance();
            manager.saveImage(mapView.getId(), i);
        }
    }
}
