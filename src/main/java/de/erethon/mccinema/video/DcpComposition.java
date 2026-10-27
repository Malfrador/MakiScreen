package de.erethon.mccinema.video;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves an unencrypted, single-picture DCP composition to FFmpeg concat inputs. */
public record DcpComposition(File picture, File sound, SubtitleCatalog subtitles, String cacheKey) {
    public static boolean isDcp(File source) {
        if (source.isDirectory()) {
            return findAssetMap(source.toPath()) != null;
        }
        return source.isFile() && source.getName().toLowerCase(Locale.ROOT).endsWith(".xml")
            && findAssetMap(source.toPath().getParent()) != null;
    }

    public static DcpComposition resolve(File source, File cacheDirectory) throws Exception {
        Path root = (source.isDirectory() ? source.toPath() : source.toPath().getParent()).toRealPath();
        Path assetMapPath = findAssetMap(root);
        if (assetMapPath == null) throw new IOException("DCP has no ASSETMAP");
        Map<String, Path> assets = readAssetMap(root, assetMapPath);
        Path cplPath = selectCpl(source, assets);
        Element cpl = parse(cplPath).getDocumentElement();
        if (!named(cpl, "CompositionPlaylist")) throw new IOException("Selected XML is not a DCP CPL");

        Element reelList = child(cpl, "ReelList");
        if (reelList == null) throw new IOException("DCP CPL has no reels");
        List<Track> pictures = new ArrayList<>();
        List<Track> sounds = new ArrayList<>();
        Map<String, List<SubtitleTrack.Cue>> subtitleCues = new java.util.LinkedHashMap<>();
        double reelOffset = 0;
        for (Element reel : children(reelList, "Reel")) {
            Element assetList = child(reel, "AssetList");
            if (assetList == null) throw new IOException("DCP reel has no assets");
            Element picture = child(assetList, "MainPicture");
            if (picture == null) throw new IOException("DCP reel has no MainPicture");
            pictures.add(readTrack(picture, assets));
            Element sound = child(assetList, "MainSound");
            if (sound != null) {
                Track audio = readTrack(sound, assets);
                if (Math.abs(audio.duration() - pictures.getLast().duration()) > 0.05) {
                    throw new IOException("DCP picture and sound reel durations differ");
                }
                sounds.add(audio);
            }
            else if (!sounds.isEmpty()) throw new IOException("DCP has sound missing from a reel");
            if (sound != null && sounds.size() != pictures.size()) {
                throw new IOException("DCP has sound missing from an earlier reel");
            }
            Element subtitle = child(assetList, "MainSubtitle");
            if (subtitle != null) {
                Track track = readTrack(subtitle, assets);
                String language = child(subtitle, "Language") == null ? "subtitle"
                    : value(subtitle, "Language").toLowerCase(Locale.ROOT);
                subtitleCues.computeIfAbsent(language, ignored -> new ArrayList<>())
                    .addAll(SubtitleTrack.fromDcpReel(track.path(), track.start(),
                        Math.min(track.duration(), pictures.getLast().duration()), reelOffset));
            }
            reelOffset += pictures.getLast().duration();
        }
        if (pictures.isEmpty()) throw new IOException("DCP CPL has no picture reels");
        String fingerprint = fingerprint(cplPath, pictures, sounds);
        Files.createDirectories(cacheDirectory.toPath());
        File pictureInput = writeConcat(cacheDirectory.toPath().resolve(fingerprint + "-picture.ffconcat"), pictures);
        File soundInput = sounds.isEmpty() ? null
            : writeConcat(cacheDirectory.toPath().resolve(fingerprint + "-sound.ffconcat"), sounds);
        Map<String, SubtitleTrack> subtitleTracks = new java.util.LinkedHashMap<>();
        subtitleCues.forEach((name, cues) -> subtitleTracks.put(name, new SubtitleTrack(cues)));
        return new DcpComposition(pictureInput, soundInput,
            new SubtitleCatalog(subtitleTracks), fingerprint);
    }

    private static Path findAssetMap(Path root) {
        if (root == null || !Files.isDirectory(root)) return null;
        for (String name : List.of("ASSETMAP", "ASSETMAP.xml", "AssetMap", "AssetMap.xml")) {
            Path path = root.resolve(name);
            if (Files.isRegularFile(path)) return path;
        }
        return null;
    }

    private static Map<String, Path> readAssetMap(Path root, Path assetMapPath) throws Exception {
        Element map = parse(assetMapPath).getDocumentElement();
        if (!named(map, "AssetMap")) throw new IOException("Invalid DCP ASSETMAP");
        Element list = child(map, "AssetList");
        if (list == null) throw new IOException("Empty DCP ASSETMAP");
        Map<String, Path> assets = new HashMap<>();
        for (Element asset : children(list, "Asset")) {
            String id = value(asset, "Id");
            Element chunks = child(asset, "ChunkList");
            if (chunks == null) continue;
            List<Element> entries = children(chunks, "Chunk");
            if (entries.size() != 1) throw new IOException("Multi-chunk DCP assets are unsupported: " + id);
            String relative = value(entries.getFirst(), "Path").replace('\\', '/');
            Path path = root.resolve(relative).normalize();
            if (!path.startsWith(root)) throw new IOException("DCP asset path leaves package: " + relative);
            if (!Files.isRegularFile(path)) throw new IOException("Missing DCP asset: " + relative);
            path = path.toRealPath();
            if (!path.startsWith(root)) throw new IOException("DCP asset path leaves package: " + relative);
            assets.put(id, path);
        }
        return assets;
    }

    private static Path selectCpl(File source, Map<String, Path> assets) throws Exception {
        if (source.isFile()) return source.toPath().toRealPath();
        List<Path> cpls = new ArrayList<>();
        for (Path path : assets.values()) {
            if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml")) continue;
            if (named(parse(path).getDocumentElement(), "CompositionPlaylist")) cpls.add(path);
        }
        if (cpls.size() != 1) {
            throw new IOException(cpls.isEmpty() ? "DCP has no CPL" :
                "DCP has multiple CPLs; play a specific CPL XML file");
        }
        return cpls.getFirst();
    }

    private static Track readTrack(Element element, Map<String, Path> assets) throws IOException {
        if (child(element, "KeyId") != null) throw new IOException("Encrypted DCP tracks are unsupported");
        String id = value(element, "Id");
        Path path = assets.get(id);
        if (path == null) throw new IOException("DCP track is missing from ASSETMAP: " + id);
        String[] rate = value(element, "EditRate").trim().split("\\s+");
        if (rate.length != 2) throw new IOException("Invalid DCP EditRate");
        try {
            double fps = (double) Long.parseLong(rate[0]) / Long.parseLong(rate[1]);
            long entry = optionalLong(element, "EntryPoint", 0);
            long duration = optionalLong(element, "Duration", optionalLong(element, "IntrinsicDuration", 0));
            if (!Double.isFinite(fps) || fps <= 0 || entry < 0 || duration <= 0) {
                throw new IOException("Invalid DCP track timing");
            }
            return new Track(path, entry / fps, duration / fps);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid DCP track timing", e);
        }
    }

    private static File writeConcat(Path output, List<Track> tracks) throws IOException {
        StringBuilder script = new StringBuilder("ffconcat version 1.0\n");
        for (Track track : tracks) {
            String path = track.path().toAbsolutePath().toString().replace('\\', '/');
            script.append("file '").append(path.replace("'", "'\\''")).append("'\n");
            script.append("inpoint ").append(String.format(Locale.ROOT, "%.9f", track.start())).append('\n');
            script.append("outpoint ").append(String.format(Locale.ROOT, "%.9f", track.start() + track.duration())).append('\n');
            script.append("duration ").append(String.format(Locale.ROOT, "%.9f", track.duration())).append('\n');
        }
        Files.writeString(output, script.toString(), StandardCharsets.UTF_8);
        return output.toFile();
    }

    private static String fingerprint(Path cpl, List<Track> pictures, List<Track> sounds) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> paths = new ArrayList<>();
        paths.add(cpl);
        pictures.forEach(track -> paths.add(track.path()));
        sounds.forEach(track -> paths.add(track.path()));
        for (Path path : paths) {
            String stamp = path.toRealPath() + ":" + Files.size(path) + ":" + Files.getLastModifiedTime(path);
            digest.update(stamp.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    private static Document parse(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static boolean named(Node node, String name) { return name.equals(node.getLocalName()) || name.equals(node.getNodeName()); }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && named(element, name)) return element;
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && named(element, name)) result.add(element);
        }
        return result;
    }

    private static String value(Element parent, String name) throws IOException {
        Element element = child(parent, name);
        if (element == null || element.getTextContent().isBlank()) throw new IOException("Missing DCP " + name);
        return element.getTextContent().trim();
    }

    private static long optionalLong(Element parent, String name, long fallback) throws IOException {
        Element element = child(parent, name);
        if (element == null) return fallback;
        try { return Long.parseLong(element.getTextContent().trim()); }
        catch (NumberFormatException e) { throw new IOException("Invalid DCP " + name, e); }
    }

    private record Track(Path path, double start, double duration) {}
}
