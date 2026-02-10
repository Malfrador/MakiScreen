package cat.maki.makiscreen.resourcepack;

import cat.maki.makiscreen.MakiScreen;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class ResourcePackServer {

    private final MakiScreen plugin;
    private HttpServer server;
    private final Map<String, File> resourcePacks = new ConcurrentHashMap<>();
    private final Map<String, byte[]> packHashes = new ConcurrentHashMap<>();

    private String serverAddress;
    private int port;

    public ResourcePackServer(MakiScreen plugin, String address, int port) {
        this.plugin = plugin;
        this.serverAddress = address;
        this.port = port;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new ResourcePackHandler());
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();

            plugin.getLogger().info("Resource pack server started on " + serverAddress + ":" + port);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to start resource pack server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            plugin.getLogger().info("Resource pack server stopped");
        }
    }

    public void registerResourcePack(String videoId, File packFile) {
        if (!packFile.exists()) {
            plugin.getLogger().warning("Resource pack file does not exist: " + packFile.getName());
            return;
        }

        try {
            byte[] hash = calculateSHA1(packFile);
            resourcePacks.put(videoId, packFile);
            packHashes.put(videoId, hash);

            plugin.getLogger().info("Registered resource pack for video: " + videoId);
            plugin.getLogger().info("  URL: " + getResourcePackUrl(videoId));
            plugin.getLogger().info("  Hash: " + bytesToHex(hash));
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to register resource pack: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void unregisterResourcePack(String videoId) {
        resourcePacks.remove(videoId);
        packHashes.remove(videoId);
        plugin.getLogger().info("Unregistered resource pack for video: " + videoId);
    }

    public String getResourcePackUrl(String videoId) {
        return "http://" + serverAddress + ":" + port + "/" + videoId + ".zip";
    }

    public byte[] getResourcePackHash(String videoId) {
        return packHashes.get(videoId);
    }

    public boolean hasResourcePack(String videoId) {
        return resourcePacks.containsKey(videoId);
    }

    private byte[] calculateSHA1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return digest.digest();
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    private class ResourcePackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            // Extract video ID from path (e.g., "/myvideo.zip" -> "myvideo")
            String videoId = path.substring(1).replace(".zip", "");

            plugin.getLogger().info("Resource pack request for: " + videoId + " from " +
                exchange.getRemoteAddress().getAddress().getHostAddress());

            File packFile = resourcePacks.get(videoId);

            if (packFile == null || !packFile.exists()) {
                plugin.getLogger().warning("Resource pack not found: " + videoId);
                String response = "Resource pack not found";
                exchange.sendResponseHeaders(404, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            try {
                byte[] fileContent = Files.readAllBytes(packFile.toPath());

                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"" + packFile.getName() + "\"");
                exchange.sendResponseHeaders(200, fileContent.length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(fileContent);
                }

                plugin.getLogger().info("Successfully served resource pack: " + videoId +
                    " (" + fileContent.length + " bytes)");
            } catch (IOException e) {
                plugin.getLogger().severe("Error serving resource pack: " + e.getMessage());
                exchange.sendResponseHeaders(500, 0);
                exchange.close();
            }
        }
    }
}

