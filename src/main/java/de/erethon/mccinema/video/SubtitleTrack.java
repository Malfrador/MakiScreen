package de.erethon.mccinema.video;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Color;
import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text subtitle timing and styling, independent of the picture decoder. */
public final class SubtitleTrack {
    private static final byte[] XML_OPEN = "<?xml".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SMPTE_END = "</SubtitleReel>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INTEROP_END = "</DCSubtitle>".getBytes(StandardCharsets.UTF_8);
    private static final Pattern SRT_TIME = Pattern.compile(
        "(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*" +
            "(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})");
    private static final int MAX_MXF_BYTES = 64 * 1024 * 1024;

    public record Style(Font font, int size, Color color, Color effectColor,
                        String effect, boolean bold, boolean italic, boolean underline,
                        double aspectAdjust, double spacing, double effectSize) {
        static Style defaults() {
            return new Style(new Font(Font.SANS_SERIF, Font.PLAIN, 42), 42,
                Color.WHITE, Color.BLACK, "shadow", false, false, false, 1.0, 0, 0.01);
        }
    }

    public record Run(String text, Style style) {}
    public record Line(List<Run> runs, String horizontalAlign, double horizontalPosition,
                       String verticalAlign, double verticalPosition) {}
    public record Cue(long startUs, long endUs, long fadeUpUs, long fadeDownUs, List<Line> lines) {}

    private final List<Cue> cues;
    private final long[] latestEnd;

    public SubtitleTrack(List<Cue> cues) {
        this.cues = cues.stream().filter(cue -> cue.endUs() > cue.startUs() && !cue.lines().isEmpty())
            .sorted(Comparator.comparingLong(Cue::startUs)).toList();
        latestEnd = new long[this.cues.size()];
        long end = Long.MIN_VALUE;
        for (int i = 0; i < latestEnd.length; i++) {
            end = Math.max(end, this.cues.get(i).endUs());
            latestEnd[i] = end;
        }
    }

    public boolean isEmpty() { return cues.isEmpty(); }
    public int size() { return cues.size(); }

    public List<Cue> activeAt(long timeUs) {
        int low = 0, high = cues.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (cues.get(middle).startUs() <= timeUs) low = middle + 1;
            else high = middle;
        }
        List<Cue> active = new ArrayList<>();
        for (int i = low - 1; i >= 0 && latestEnd[i] > timeUs; i--) {
            Cue cue = cues.get(i);
            if (cue.endUs() > timeUs) active.addFirst(cue);
        }
        return active;
    }

    public static SubtitleTrack fromSrt(File file) throws IOException {
        String source = Files.readString(file.toPath(), StandardCharsets.UTF_8)
            .replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        List<Cue> cues = new ArrayList<>();
        for (String block : source.split("\\n\\s*\\n")) {
            Matcher timing = SRT_TIME.matcher(block);
            if (!timing.find()) continue;
            long start = srtMicros(timing, 1), end = srtMicros(timing, 5);
            String body = block.substring(timing.end()).strip();
            List<Line> lines = new ArrayList<>();
            for (String rawLine : body.split("\\n")) {
                String clean = rawLine.replaceAll("<[^>]*>", "")
                    .replaceAll("\\{\\\\[^}]*\\}", "").strip();
                if (clean.isEmpty()) continue;
                boolean bold = rawLine.toLowerCase(Locale.ROOT).contains("<b>");
                boolean italic = rawLine.toLowerCase(Locale.ROOT).contains("<i>");
                boolean underline = rawLine.toLowerCase(Locale.ROOT).contains("<u>");
                Style base = Style.defaults();
                Style style = new Style(base.font(), base.size(), base.color(), base.effectColor(),
                    base.effect(), bold, italic, underline, base.aspectAdjust(),
                    base.spacing(), base.effectSize());
                lines.add(new Line(List.of(new Run(clean, style)), "center", 0,
                    "bottom", 5 + (body.split("\\n").length - 1 - lines.size()) * 3));
            }
            if (!lines.isEmpty()) cues.add(new Cue(start, end, 0, 0, List.copyOf(lines)));
        }
        return new SubtitleTrack(cues);
    }

    private static long srtMicros(Matcher matcher, int first) {
        long hours = Long.parseLong(matcher.group(first));
        long minutes = Long.parseLong(matcher.group(first + 1));
        long seconds = Long.parseLong(matcher.group(first + 2));
        long millis = Long.parseLong(matcher.group(first + 3));
        return ((hours * 60 + minutes) * 60 + seconds) * 1_000_000 + millis * 1000;
    }

    /** Read one CPL subtitle reel; its track in/out and composition offset are in seconds. */
    static List<Cue> fromDcpReel(Path path, double entry, double duration, double reelOffset) throws Exception {
        if (Files.size(path) > MAX_MXF_BYTES) throw new IOException("DCP subtitle asset is too large");
        byte[] contents = Files.readAllBytes(path);
        int xmlStart = indexOf(contents, XML_OPEN, 0);
        if (xmlStart < 0) throw new IOException("DCP subtitle asset has no text XML");
        byte[] endTag = indexOf(contents, SMPTE_END, xmlStart) >= 0 ? SMPTE_END : INTEROP_END;
        int xmlEnd = indexOf(contents, endTag, xmlStart);
        if (xmlEnd < 0) throw new IOException("DCP subtitle XML is incomplete");
        byte[] xml = java.util.Arrays.copyOfRange(contents, xmlStart, xmlEnd + endTag.length);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        Element root = document.getDocumentElement();
        if (!named(root, "SubtitleReel") && !named(root, "DCSubtitle")) {
            throw new IOException("Unsupported DCP subtitle XML root");
        }
        Font embedded = findEmbeddedFont(contents, xmlEnd + endTag.length);
        int timeCodeRate = integer(childText(root, "TimeCodeRate", "24"), 24);
        String startCode = childText(root, "StartTime", named(root, "SubtitleReel") ? "01:00:00:00" : "00:00:00:00");
        long timelineStart = dcpTimeUs(startCode, timeCodeRate);
        Element list = child(root, "SubtitleList");
        if (list == null) throw new IOException("DCP subtitle reel has no SubtitleList");
        List<Cue> cues = new ArrayList<>();
        collectDcpCues(list, Style.defaults(), embedded, timeCodeRate, timelineStart,
            entry, duration, reelOffset, cues);
        return cues;
    }

    private static void collectDcpCues(Element parent, Style inherited, Font embedded, int rate,
                                       long timelineStart, double entry, double duration,
                                       double offset, List<Cue> output) {
        Style style = named(parent, "Font") ? style(parent, inherited, embedded) : inherited;
        for (Element child : elements(parent)) {
            if (named(child, "Font")) {
                collectDcpCues(child, style, embedded, rate, timelineStart, entry, duration, offset, output);
            } else if (named(child, "Subtitle")) {
                long localStart = dcpTimeUs(child.getAttribute("TimeIn"), rate) - timelineStart;
                long localEnd = dcpTimeUs(child.getAttribute("TimeOut"), rate) - timelineStart;
                long clipStart = (long) Math.round(entry * 1_000_000);
                long clipEnd = (long) Math.round((entry + duration) * 1_000_000);
                if (localEnd <= clipStart || localStart >= clipEnd) continue;
                List<Line> lines = new ArrayList<>();
                collectLines(child, style, embedded, lines);
                long shift = (long) Math.round(offset * 1_000_000) - clipStart;
                output.add(new Cue(Math.max(localStart, clipStart) + shift,
                    Math.min(localEnd, clipEnd) + shift,
                    dcpTimeUs(attr(child, "FadeUpTime", "00:00:00:02"), rate),
                    dcpTimeUs(attr(child, "FadeDownTime", "00:00:00:02"), rate), List.copyOf(lines)));
            }
        }
    }

    private static void collectLines(Element parent, Style inherited, Font embedded, List<Line> lines) {
        Style style = named(parent, "Font") ? style(parent, inherited, embedded) : inherited;
        for (Element child : elements(parent)) {
            if (named(child, "Text")) {
                List<Run> runs = new ArrayList<>();
                collectRuns(child, style, embedded, runs);
                if (!runs.isEmpty()) {
                    lines.add(new Line(List.copyOf(runs), attr(child, "Halign", "center"),
                        decimal(child.getAttribute("Hposition"), 0),
                        attr(child, "Valign", "center"),
                        decimal(child.getAttribute("Vposition"), 0)));
                }
            } else if (named(child, "Font")) {
                collectLines(child, style, embedded, lines);
            }
            // Image elements are bitmap subtitles and are deliberately skipped.
        }
    }

    private static void collectRuns(Node node, Style inherited, Font embedded, List<Run> runs) {
        Style style = node instanceof Element element && named(element, "Font")
            ? style(element, inherited, embedded) : inherited;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                String text = child.getNodeValue().replace("\r", "").replace("\n", "").replace("\t", "");
                if (!text.isBlank()) runs.add(new Run(text, style));
            } else if (child instanceof Element element && named(element, "Font")) {
                collectRuns(element, style, embedded, runs);
            }
        }
    }

    private static Style style(Element element, Style base, Font embedded) {
        Font font = element.hasAttribute("ID") && embedded != null ? embedded : base.font();
        String weight = attr(element, "Weight", base.bold() ? "bold" : "normal");
        String italic = attr(element, "Italic", base.italic() ? "yes" : "no");
        return new Style(font, (int) Math.round(decimal(attr(element, "Size", "" + base.size()), base.size())),
            color(attr(element, "Color", ""), base.color()),
            color(attr(element, "EffectColor", ""), base.effectColor()),
            attr(element, "Effect", base.effect()),
            weight.equalsIgnoreCase("bold"), !italic.equalsIgnoreCase("no"),
            attr(element, "Underline", base.underline() ? "yes" : "no").equalsIgnoreCase("yes"),
            decimal(attr(element, "AspectAdjust", "" + base.aspectAdjust()), base.aspectAdjust()),
            decimal(attr(element, "Spacing", "" + base.spacing()), base.spacing()),
            decimal(attr(element, "EffectSize", "" + base.effectSize()), base.effectSize()));
    }

    private static Font findEmbeddedFont(byte[] mxf, int from) {
        for (int i = from; i + 24 < mxf.length; i++) {
            if (mxf[i] != 6 || mxf[i + 1] != 14 || mxf[i + 2] != 43 || mxf[i + 3] != 52) continue;
            int lengthByte = mxf[i + 16] & 255;
            int bytes = lengthByte < 128 ? 0 : lengthByte & 127;
            if (bytes > 4 || i + 17 + bytes >= mxf.length) continue;
            long length = bytes == 0 ? lengthByte : 0;
            for (int j = 0; j < bytes; j++) length = length << 8 | mxf[i + 17 + j] & 255;
            int start = i + 17 + bytes;
            if (length < 1024 || length > 16 * 1024 * 1024 || start + length > mxf.length) continue;
            boolean truetype = mxf[start] == 0 && mxf[start + 1] == 1 && mxf[start + 2] == 0 && mxf[start + 3] == 0;
            boolean opentype = mxf[start] == 'O' && mxf[start + 1] == 'T' && mxf[start + 2] == 'T' && mxf[start + 3] == 'O';
            if (!truetype && !opentype) continue;
            try {
                return Font.createFont(Font.TRUETYPE_FONT,
                    new ByteArrayInputStream(mxf, start, (int) length));
            } catch (Exception ignored) {
                // Fall back to the installed sans-serif font.
            }
        }
        return null;
    }

    private static long dcpTimeUs(String code, int rate) {
        if (code == null || code.isBlank()) return 0;
        String[] parts = code.split(":");
        if (parts.length != 4 || rate <= 0) return 0;
        try {
            long seconds = (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 60
                + Long.parseLong(parts[2]);
            return seconds * 1_000_000 + Math.round(Long.parseLong(parts[3]) * 1_000_000.0 / rate);
        } catch (NumberFormatException ignored) { return 0; }
    }

    private static int indexOf(byte[] source, byte[] needle, int start) {
        outer: for (int i = start; i <= source.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (source[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static boolean named(Node node, String name) {
        return name.equals(node.getLocalName()) || name.equals(node.getNodeName());
    }

    private static Element child(Element parent, String name) {
        for (Element element : elements(parent)) if (named(element, name)) return element;
        return null;
    }

    private static List<Element> elements(Node parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) result.add(element);
        }
        return result;
    }

    private static String childText(Element parent, String name, String fallback) {
        Element element = child(parent, name);
        return element == null ? fallback : element.getTextContent().strip();
    }

    private static String attr(Element element, String name, String fallback) {
        return element.hasAttribute(name) ? element.getAttribute(name) : fallback;
    }

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static double decimal(String value, double fallback) {
        try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static Color color(String argb, Color fallback) {
        if (!argb.matches("[0-9A-Fa-f]{8}")) return fallback;
        try { return new Color((int) Long.parseLong(argb, 16), true); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
