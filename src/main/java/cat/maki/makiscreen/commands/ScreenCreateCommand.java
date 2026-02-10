package cat.maki.makiscreen.commands;

import cat.maki.makiscreen.MakiScreen;
import cat.maki.makiscreen.screen.AspectRatio;
import cat.maki.makiscreen.screen.MapTile;
import cat.maki.makiscreen.screen.Screen;
import de.erethon.bedrock.command.ECommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ScreenCreateCommand extends ECommand {

    private final MakiScreen plugin = MakiScreen.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public ScreenCreateCommand() {
        setCommand("create");
        setAliases("new", "add");
        setPermission("makiscreen.create");
        setPlayerCommand(true);
        setConsoleCommand(false);
        setMinArgs(1);
        setMaxArgs(99);
        setHelp("/maki create <name> [aspect] [width] [height]");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        Player player = (Player) sender;

        String name = args[1];

        if (plugin.getScreenManager().screenExists(name)) {
            player.sendMessage(MM.deserialize("<red>A screen with that name already exists!"));
            return;
        }

        AspectRatio aspectRatio = AspectRatio.RATIO_16_9;
        int mapWidth = 8;
        int mapHeight = 5;

        if (args.length >= 3) {
            aspectRatio = AspectRatio.fromString(args[2]);
            int[] dims = aspectRatio.calculateMapDimensions(64);
            mapWidth = dims[0];
            mapHeight = dims[1];
        }

        if (args.length >= 5) {
            try {
                mapWidth = Integer.parseInt(args[3]);
                mapHeight = Integer.parseInt(args[4]);

                if (mapWidth < 1 || mapWidth > 64 || mapHeight < 1 || mapHeight > 32) {
                    player.sendMessage(MM.deserialize("<red>Invalid dimensions! Width: 1-64, Height: 1-32"));
                    return;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(MM.deserialize("<red>Invalid dimensions! Use numbers."));
                return;
            }
        }

        // Determine placement direction from player's facing
        BlockFace facing = getCardinalDirection(player);
        Block targetBlock = player.getTargetBlockExact(10);

        if (targetBlock == null) {
            player.sendMessage(MM.deserialize("<red>Look at a wall to place the screen!"));
            return;
        }

        // Create the screen
        Screen screen = plugin.getScreenManager().createScreen(name, mapWidth, mapHeight, aspectRatio);
        screen.setOrigin(targetBlock.getLocation());
        screen.setFacing(facing);

        // Create maps and item frames
        boolean success = createScreenMaps(player, screen, targetBlock, facing);

        if (!success) {
            plugin.getScreenManager().deleteScreen(screen);
            player.sendMessage(MM.deserialize("<red>Failed to create screen! Not enough space."));
            return;
        }

        plugin.getScreenManager().saveScreens();

        int totalMaps = screen.getTotalMaps();
        int pixelWidth = screen.getPixelWidth();
        int pixelHeight = screen.getPixelHeight();

        player.sendMessage(MM.deserialize(
            "<green>Screen '<white>" + name + "</white>' created!" +
            "\n<gray>  Size: <white>" + mapWidth + "x" + mapHeight + "</white> maps (" + totalMaps + " total)" +
            "\n<gray>  Resolution: <white>" + pixelWidth + "x" + pixelHeight + "</white> pixels" +
            "\n<gray>  Aspect Ratio: <white>" + aspectRatio.getDisplayName() + "</white>"
        ));
    }

    private boolean createScreenMaps(Player player, Screen screen, Block startBlock, BlockFace facing) {
        BlockFace rightFace = getRightFace(facing);

        List<ItemStack> maps = new ArrayList<>();
        List<ItemFrame> frames = new ArrayList<>();

        // Create maps first
        for (int y = screen.getMapHeight() - 1; y >= 0; y--) {
            for (int x = 0; x < screen.getMapWidth(); x++) {
                MapView mapView = plugin.getServer().createMap(player.getWorld());
                mapView.setScale(MapView.Scale.CLOSEST);
                mapView.setUnlimitedTracking(true);

                for (MapRenderer renderer : mapView.getRenderers()) {
                    mapView.removeRenderer(renderer);
                }

                ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
                MapMeta meta = (MapMeta) mapItem.getItemMeta();
                meta.setMapView(mapView);
                mapItem.setItemMeta(meta);

                int tileIndex = (screen.getMapHeight() - 1 - y) * screen.getMapWidth() + x;
                MapTile tile = new MapTile(mapView.getId(), x, screen.getMapHeight() - 1 - y, tileIndex);
                screen.addTile(tile);

                maps.add(mapItem);
            }
        }

        // Place item frames
        int mapIndex = 0;
        for (int y = screen.getMapHeight() - 1; y >= 0; y--) {
            for (int x = 0; x < screen.getMapWidth(); x++) {
                Block targetBlock = startBlock
                    .getRelative(rightFace, x)
                    .getRelative(BlockFace.UP, y);

                Location frameLoc = targetBlock.getRelative(facing.getOppositeFace()).getLocation();

                try {
                    ItemFrame frame = player.getWorld().spawn(frameLoc, ItemFrame.class, f -> {
                        f.setFacingDirection(facing);
                        f.setRotation(Rotation.NONE);
                        f.setFixed(true);
                        f.setVisible(false);
                        f.setInvulnerable(true);
                    });

                    frame.setItem(maps.get(mapIndex), false);
                    frames.add(frame);
                } catch (Exception e) {
                    // Cleanup on failure
                    for (ItemFrame f : frames) {
                        f.remove();
                    }
                    return false;
                }

                mapIndex++;
            }
        }

        return true;
    }

    private BlockFace getCardinalDirection(Player player) {
        float yaw = player.getLocation().getYaw();
        yaw = (yaw % 360 + 360) % 360;

        if (yaw >= 315 || yaw < 45) {
            return BlockFace.SOUTH;
        } else if (yaw >= 45 && yaw < 135) {
            return BlockFace.WEST;
        } else if (yaw >= 135 && yaw < 225) {
            return BlockFace.NORTH;
        } else {
            return BlockFace.EAST;
        }
    }

    private BlockFace getRightFace(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.EAST;
            case SOUTH -> BlockFace.WEST;
            case EAST -> BlockFace.SOUTH;
            case WEST -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        // args[0] = "create", args[1] = name, args[2] = aspect, args[3] = width, args[4] = height
        if (args.length == 3) {
            return Arrays.stream(AspectRatio.values())
                .map(AspectRatio::name)
                .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                .toList();
        }
        if (args.length == 4 || args.length == 5) {
            return List.of("<number>");
        }
        return List.of();
    }
}

