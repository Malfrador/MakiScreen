package cat.maki.makiscreen;

import com.google.common.collect.EvictingQueue;
import de.erethon.bedrock.chat.MessageUtil;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_19_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.FrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static cat.maki.makiscreen.dither.DitherLookupUtil.COLOR_MAP;
import static cat.maki.makiscreen.dither.DitherLookupUtil.FULL_COLOR_MAP;

public class OpenCVFrameGrabber extends BukkitRunnable implements Listener {

    private final MakiScreen plugin = MakiScreen.getInstance();

    public File videoFile;
    FFmpegFrameGrabber grabber;
    public static BufferedImage currentFrame;
    private int skipCounter = 0;
    public int skippy = 5;

    private final Object lock = new Object();
    private final Queue<byte[][]> frameBuffers = EvictingQueue.create(450);
    private long frameNumber = 0;
    private final int mapSize;

    private final byte[] ditheredFrameData;
    private final int[][] ditherBuffer;
    private final byte[][] cachedMapData;
    private final int frameWidth;
    private byte[] frameData;

    public OpenCVFrameGrabber(File videoFile, int mapSize, int mapWidth) {
        this.videoFile = videoFile;
        this.mapSize = mapSize;
        this.frameWidth = mapWidth * 128;
        this.ditheredFrameData = new byte[mapSize * 128 * 128];
        this.ditherBuffer = new int[2][frameWidth << 2];
        this.cachedMapData = new byte[mapSize][];
    }

    public void prepare() {
        grabber = new FFmpegFrameGrabber(videoFile);
        try {
            grabber.start();
            grabber.setFrameRate(20);
            MessageUtil.log("Video length: " + grabber.getLengthInTime() + " ms");
            MessageUtil.log("Video frame rate: " + grabber.getFrameRate() + " fps");
            MessageUtil.log("Video frame count: " + grabber.getLengthInFrames() + " frames");
            MessageUtil.log("Video frame size: " + grabber.getImageWidth() + "x" + grabber.getImageHeight());

        } catch (FFmpegFrameGrabber.Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void run() {
        //long nanos = System.nanoTime();
        try {
            Frame frame = grabber.grab();
            if (frame == null) {
                MessageUtil.log("Video ended.");
                return;
            }
            if (frame.image == null) {
                return;
            }
            skipCounter++;
            if (skipCounter >= skippy) { // Skip every 5th frame to get to 20 fps
                skipCounter = 0;
                return;
            }
            Java2DFrameConverter c = new Java2DFrameConverter();
            BufferedImage image = c.convert(frame);
            BufferedImage resized = resizeImage(image, 1024, 512, image.getType());
            //MessageUtil.broadcastActionBarMessage("Frame: " + grabber.getFrameNumber() + " | Time: " + (System.nanoTime() - nanos) / 1000 + " microseconds");

            // TODO: Merged into one runnable - needs work still
            // Frame processing
            frameData = ((DataBufferByte) resized.getRaster().getDataBuffer()).getData();
            ditherFrame();

            byte[][] buffers = new byte[mapSize][];

            for (int partId = 0; partId < buffers.length; partId++) {
                buffers[partId] = getMapData(partId, frameWidth);
            }

            // Sending map data
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void preprocess(File file) {
        FFmpegFrameGrabber preGrabber = new FFmpegFrameGrabber(file);
        try {
            preGrabber.start();
        } catch (FFmpegFrameGrabber.Exception e) {
            throw new RuntimeException(e);
        }
        int skipCounter = 0;
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(new File(MakiScreen.getInstance().getDataFolder() + "/processed.mp4"), preGrabber.getImageWidth(), preGrabber.getImageWidth());
        recorder.setVideoCodec(preGrabber.getVideoCodec());
        recorder.setFormat(preGrabber.getFormat());
        recorder.setAudioChannels(preGrabber.getAudioChannels());
        recorder.setAudioCodec(preGrabber.getAudioCodec());
        recorder.setSampleRate(preGrabber.getSampleRate());
        recorder.setAudioBitrate(preGrabber.getAudioBitrate());
        recorder.setFrameRate(20);
        recorder.setVideoBitrate(preGrabber.getVideoBitrate());
        recorder.setVideoCodec(preGrabber.getVideoCodec());
        try {
            recorder.start();
        } catch (FFmpegFrameRecorder.Exception e) {
            throw new RuntimeException(e);
        }
        try {
            while (true) {
                Frame frame = preGrabber.grab();
                if (frame == null) {
                    break;
                }
                MessageUtil.broadcastActionBarMessage("Processing frame " + preGrabber.getFrameNumber() + " of " + preGrabber.getLengthInFrames());
                skipCounter++;
                if (skipCounter >= 5) { // Skip every 5th frame to get to 20 fps
                    skipCounter = 0;
                    continue;
                }
                recorder.record(frame);
            }
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight, int type) throws IOException {
        BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, type);
        Graphics2D graphics2D = resizedImage.createGraphics();
        graphics2D.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
        graphics2D.dispose();
        return resizedImage;
    }

    public Queue<byte[][]> getFrameBuffers() {
        return frameBuffers;
    }

    private void ditherFrame() {
        int width = this.frameWidth;
        int height = this.ditheredFrameData.length / width;
        int widthMinus = width - 1;
        int heightMinus = height - 1;

        //   |  Y
        // X | -> -> -> ->
        //   | <- <- <- <-
        //   | -> -> -> ->
        //   | <- <- <- <-
        for (int y = 0; y < height; y++) {
            boolean hasNextY = y < heightMinus;
            int yIndex = y * width;
            if ((y & 0x1) == 0) { // Forward
                int bufferIndex = 0;
                final int[] buf1 = ditherBuffer[0];
                final int[] buf2 = ditherBuffer[1];

                for (int x = 0; x < width; ++x) {
                    int pos = pos(x, y, width);
                    int blue = (int) frameData[pos++] & 0xff;
                    int green = ((int) frameData[pos++] & 0xff);
                    int red = ((int) frameData[pos] & 0xff);

                    red = Math.max(Math.min(255, red + buf1[bufferIndex++]), 0);
                    green = Math.max(Math.min(255, green + buf1[bufferIndex++]), 0);
                    blue = Math.max(Math.min(255, blue + buf1[bufferIndex++]), 0);

                    final int closest = getBestFullColor(red, green, blue);
                    final int delta_r = red - (closest >> 16 & 0xFF);
                    final int delta_g = green - (closest >> 8 & 0xFF);
                    final int delta_b = blue - (closest & 0xFF);

                    if (x < widthMinus) {
                        buf1[bufferIndex] = delta_r >> 1;
                        buf1[bufferIndex + 1] = delta_g >> 1;
                        buf1[bufferIndex + 2] = delta_b >> 1;
                    }
                    if (hasNextY) {
                        if (x > 0) {
                            buf2[bufferIndex - 6] = delta_r >> 2;
                            buf2[bufferIndex - 5] = delta_g >> 2;
                            buf2[bufferIndex - 4] = delta_b >> 2;
                        }
                        buf2[bufferIndex - 3] = delta_r >> 2;
                        buf2[bufferIndex - 2] = delta_g >> 2;
                        buf2[bufferIndex - 1] = delta_b >> 2;
                    }
                    ditheredFrameData[yIndex + x] = getColor(closest);
                }
            } else { // Backward
                int bufferIndex = width + (width << 1) - 1;
                final int[] buf1 = ditherBuffer[1];
                final int[] buf2 = ditherBuffer[0];
                for (int x = width - 1; x >= 0; --x) {
                    int pos = pos(x, y, width);
                    int blue = (int) frameData[pos++] & 0xff;
                    int green = ((int) frameData[pos++] & 0xff);
                    int red = ((int) frameData[pos] & 0xff);

                    red = Math.max(Math.min(255, red + buf1[bufferIndex--]), 0);
                    green = Math.max(Math.min(255, green + buf1[bufferIndex--]), 0);
                    blue = Math.max(Math.min(255, blue + buf1[bufferIndex--]), 0);

                    int closest = getBestFullColor(red, green, blue);
                    int delta_r = red - (closest >> 16 & 0xFF);
                    int delta_g = green - (closest >> 8 & 0xFF);
                    int delta_b = blue - (closest & 0xFF);

                    if (x > 0) {
                        buf1[bufferIndex] = delta_b >> 1;
                        buf1[bufferIndex - 1] = delta_g >> 1;
                        buf1[bufferIndex - 2] = delta_r >> 1;
                    }
                    if (hasNextY) {
                        if (x < widthMinus) {
                            buf2[bufferIndex + 6] = delta_b >> 2;
                            buf2[bufferIndex + 5] = delta_g >> 2;
                            buf2[bufferIndex + 4] = delta_r >> 2;
                        }
                        buf2[bufferIndex + 3] = delta_b >> 2;
                        buf2[bufferIndex + 2] = delta_g >> 2;
                        buf2[bufferIndex + 1] = delta_r >> 2;
                    }
                    ditheredFrameData[yIndex + x] = getColor(closest);
                }
            }
        }
    }

    private static int pos(int x, int y, int width) {
        return (y * 3 * width) + (x * 3);
    }

    private static byte getColor(int rgb) {
        return COLOR_MAP[(rgb >> 16 & 0xFF) >> 1 << 14 | (rgb >> 8 & 0xFF) >> 1 << 7
                | (rgb & 0xFF) >> 1];
    }

    private static int getBestFullColor(final int red, final int green, final int blue) {
        return FULL_COLOR_MAP[red >> 1 << 14 | green >> 1 << 7 | blue >> 1];
    }

    private byte[] getMapData(int partId, int width) {
        int offset = 0;
        int startX = ((partId % ConfigFile.getMapWidth()) * 128);
        int startY = ((partId / ConfigFile.getMapWidth()) * 128);
        int maxY = startY + 128;
        int maxX = startX + 128;

        boolean modified = false;
        byte[] bytes = this.cachedMapData[partId];
        if (bytes == null) {
            bytes = new byte[128 * 128];
            modified = true;
        }
        for (int y = startY; y < maxY; y++) {
            int yIndex = y * width;
            for (int x = startX; x < maxX; x++) {
                byte newColor = ditheredFrameData[yIndex + x];
                if (modified) {
                    bytes[offset] = newColor;
                } else {
                    if (bytes[offset] != newColor) {
                        bytes[offset] = newColor;
                        modified = true;
                    }
                }
                offset++;
            }
        }

        if (modified) {
            this.cachedMapData[partId] = bytes;
            byte[] result = new byte[bytes.length];
            System.arraycopy(bytes,0, result, 0, bytes.length);
            return result;
        }
        return null;
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
                new MapItemSavedData.MapPatch(0, 0, 128, 128, data));
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
}
