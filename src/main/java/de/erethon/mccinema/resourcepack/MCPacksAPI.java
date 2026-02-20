package de.erethon.mccinema.resourcepack;

import de.erethon.mccinema.MCCinema;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

public class MCPacksAPI {

    private static final String API_ENDPOINT = "https://mcpacks.dev/api/v1/packs";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 120000; // 120 seconds to read response, it's rather slow
    private static final String BOUNDARY = "----MCCinemaBoundary" + System.currentTimeMillis();

    private final MCCinema plugin;

    public MCPacksAPI(MCCinema plugin) {
        this.plugin = plugin;
    }

    public MCPacksResponse uploadResourcePack(File packFile, Runnable onUploading, Runnable onWaiting) {
        if (!packFile.exists()) {
            plugin.getLogger().severe("Resource pack file does not exist: " + packFile.getName());
            return null;
        }

        try {
            plugin.getLogger().info("Uploading resource pack to mcpacks.dev: " + packFile.getName() +
                " (" + packFile.length() + " bytes)");

            URL url = URI.create(API_ENDPOINT).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);

            byte[] headerPart = (
                "--" + BOUNDARY + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + packFile.getName() + "\"\r\n" +
                "Content-Type: application/zip\r\n\r\n"
            ).getBytes();
            byte[] footerPart = ("\r\n--" + BOUNDARY + "--\r\n").getBytes();
            long contentLength = headerPart.length + packFile.length() + footerPart.length;
            connection.setFixedLengthStreamingMode(contentLength);

            plugin.getLogger().info("Connecting to mcpacks.dev...");

            // Write multipart/form-data request
            try (OutputStream output = connection.getOutputStream()) {
                // Start boundary
                output.write(("--" + BOUNDARY + "\r\n").getBytes());
                output.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" +
                    packFile.getName() + "\"\r\n").getBytes());
                output.write("Content-Type: application/zip\r\n\r\n".getBytes());

                // Write file content in chunks
                if (onUploading != null) onUploading.run();
                plugin.getLogger().info("Uploading file content...");
                long totalBytes = packFile.length();
                long uploadedBytes = 0;
                byte[] buffer = new byte[8192];
                int bytesRead;
                int lastPercent = 0;

                try (java.io.FileInputStream fis = new java.io.FileInputStream(packFile)) {
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                        uploadedBytes += bytesRead;
                        int percent = (int) ((uploadedBytes * 100) / totalBytes);
                        if (percent >= lastPercent + 10) {
                            plugin.getLogger().info("Upload progress: " + percent + "% (" +
                                (uploadedBytes / 1024 / 1024) + " MB / " +
                                (totalBytes / 1024 / 1024) + " MB)");
                            lastPercent = percent;
                        }
                    }
                }

                output.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes());
                output.flush();

                if (onWaiting != null) onWaiting.run();
                plugin.getLogger().info("Upload complete, waiting for server response...");
            }

            int responseCode = connection.getResponseCode();

            // Read response
            InputStream responseStream = (responseCode >= 200 && responseCode < 300)
                ? connection.getInputStream()
                : connection.getErrorStream();

            String responseBody = new String(responseStream.readAllBytes());
            responseStream.close();

            if (responseCode == 201) {
                // Parse successful response
                JSONObject json = JSON.parseObject(responseBody);

                if (!json.getBoolean("success")) {
                    plugin.getLogger().severe("mcpacks.dev returned success=false: " + responseBody);
                    return null;
                }

                JSONObject data = json.getJSONObject("data");
                String uuid = data.getString("uuid");
                String downloadUrl = data.getString("download_url");
                String sha1 = data.getString("sha1");
                long fileSize = data.getLongValue("file_size");

                plugin.getLogger().info("Successfully uploaded resource pack to mcpacks.dev");
                plugin.getLogger().info("  UUID: " + uuid);
                plugin.getLogger().info("  Download URL: " + downloadUrl);
                plugin.getLogger().info("  SHA1: " + sha1);
                plugin.getLogger().info("  File Size: " + fileSize + " bytes");

                return new MCPacksResponse(uuid, downloadUrl, sha1, fileSize);

            } else {
                plugin.getLogger().severe("Failed to upload resource pack to mcpacks.dev");
                plugin.getLogger().severe("  HTTP Status: " + responseCode);
                plugin.getLogger().severe("  Response: " + responseBody);
                return null;
            }

        } catch (IOException e) {
            plugin.getLogger().severe("Error uploading resource pack to mcpacks.dev: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public record MCPacksResponse(String uuid, String downloadUrl, String sha1, long fileSize) {

        // Convert SHA1 hash string to byte array for Bukkit API
        public byte[] getSha1Bytes() {
            byte[] bytes = new byte[sha1.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                int index = i * 2;
                bytes[i] = (byte) Integer.parseInt(sha1.substring(index, index + 2), 16);
            }
            return bytes;
        }
    }
}

