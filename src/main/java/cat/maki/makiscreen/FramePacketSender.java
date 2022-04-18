package cat.maki.makiscreen;

import java.net.http.WebSocket.Listener;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_18_R2.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData.MapPatch;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

class FramePacketSender extends BukkitRunnable implements Listener, org.bukkit.event.Listener {
  private long frameNumber = 0;
  private final Queue<byte[][]> frameBuffers;
  private final MakiScreen plugin;

  public FramePacketSender(MakiScreen plugin, Queue<byte[][]> frameBuffers) {
    this.frameBuffers = frameBuffers;
    this.plugin = plugin;
    this.plugin.getServer().getPluginManager().registerEvents(this, plugin);
  }

  @Override
  public void run() {
    byte[][] buffers = frameBuffers.poll();
    if (buffers == null) {
      return;
    }
    List<ClientboundMapItemDataPacket> packets = new ArrayList<>(plugin.getScreens().size());
    for (ScreenPart screenPart : plugin.getScreens()) {
      byte[] buffer = buffers[screenPart.partId];
      if (buffer != null) {
        ClientboundMapItemDataPacket packet = getPacket(screenPart.mapId, buffer);
        if (!screenPart.modified) {
          packets.add(0, packet);
        } else {
          packets.add(packet);
        }
        screenPart.modified = true;
        screenPart.lastFrameBuffer = buffer;
      } else {
        screenPart.modified = false;
      }
    }

    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
      sendToPlayer(onlinePlayer, packets);
    }

    if (frameNumber % 300 == 0) {
      byte[][] peek = frameBuffers.peek();
      if (peek != null) {
        frameBuffers.clear();
        frameBuffers.offer(peek);
      }
    }
    frameNumber++;
  }

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    new BukkitRunnable() {
      @Override
      public void run() {
        List<ClientboundMapItemDataPacket> packets = new ArrayList<>();
        for (ScreenPart screenPart : plugin.getScreens()) {
          if (screenPart.lastFrameBuffer != null) {
            packets.add(getPacket(screenPart.mapId, screenPart.lastFrameBuffer));
          }
        }
        sendToPlayer(event.getPlayer(), packets);
      }
    }.runTaskLater(plugin, 10);
  }

  private void sendToPlayer(Player player, List<ClientboundMapItemDataPacket> packets) {
    final ServerGamePacketListenerImpl connection = ((CraftPlayer) player).getHandle().connection;
    for (ClientboundMapItemDataPacket packet : packets) {
      if (packet != null) {
        connection.send(packet);
      }
    }
  }

  private ClientboundMapItemDataPacket getPacket(int mapId, byte[] data) {
    if (data == null) {
      throw new NullPointerException("data is null");
    }
    return new ClientboundMapItemDataPacket(
        mapId, (byte) 0, false, null,
        new MapPatch(0, 0, 128, 128, data));
  }
}
