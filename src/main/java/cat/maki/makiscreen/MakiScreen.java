package cat.maki.makiscreen;

import cat.maki.makiscreen.commands.MakiCommandCache;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import de.erethon.bedrock.plugin.EPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

public final class MakiScreen extends EPlugin implements Listener {

    private final Logger logger = getLogger();
    private static MakiScreen instance;
    private MakiCommandCache commands;

    private Set<ScreenPart> screens = new TreeSet<>(
        Comparator.comparingInt(to -> to.mapId));
    private VideoCapture videoCapture;

    @Override
    public void onEnable() {
        instance = this;
        commands = new MakiCommandCache(this);
        ConfigFile configFile = new ConfigFile(this);
        configFile.run();

        ImageManager manager = ImageManager.getInstance();
        manager.init();

        logger.info("Hi!");
        getServer().getPluginManager().registerEvents(this, this);

        logger.info("Config file loaded \n"+
                "Map Size: " + ConfigFile.getMapSize() +"\n"+
                "Map Width: " + ConfigFile.getMapWidth() +"\n"+
                "Width: " + ConfigFile.getVCWidth() +"\n"+
                "Height: " + ConfigFile.getVCHeight()

        );

        int mapSize = ConfigFile.getMapSize();
        int mapWidth = ConfigFile.getMapWidth();

        videoCapture = new VideoCapture(this,
                ConfigFile.getVCWidth(),
                ConfigFile.getVCHeight()
        );
        videoCapture.start();

        FrameProcessorTask frameProcessorTask = new FrameProcessorTask(mapSize, mapWidth);
        frameProcessorTask.runTaskTimerAsynchronously(this, 0, 1);
        FramePacketSender framePacketSender =
            new FramePacketSender(this, frameProcessorTask.getFrameBuffers());
        framePacketSender.runTaskTimerAsynchronously(this, 0, 1);
    }

    @Override
    public void onDisable() {
        logger.info("Bye!");
        videoCapture.cleanup();
    }

    public Set<ScreenPart> getScreens() {
        return screens;
    }

    public static MakiScreen getInstance() {
        return instance;
    }

}
