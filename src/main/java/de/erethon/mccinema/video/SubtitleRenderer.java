package de.erethon.mccinema.video;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Draws cached, antialiased text overlays onto decoded video frames. */
final class SubtitleRenderer {
    private SubtitleTrack track;
    private List<SubtitleTrack.Cue> cachedCues = List.of();
    private List<Placement> placements = List.of();
    private int cachedWidth;
    private int cachedHeight;

    synchronized void setTrack(SubtitleTrack track) {
        this.track = track;
        cachedCues = List.of();
        placements = List.of();
    }

    synchronized void render(BufferedImage frame, long timeUs) {
        if (track == null || track.isEmpty()) return;
        List<SubtitleTrack.Cue> active = track.activeAt(timeUs);
        if (active.isEmpty()) return;
        if (!active.equals(cachedCues) || frame.getWidth() != cachedWidth || frame.getHeight() != cachedHeight) {
            cachedCues = active;
            cachedWidth = frame.getWidth();
            cachedHeight = frame.getHeight();
            placements = prepare(active, cachedWidth, cachedHeight);
        }
        Graphics2D graphics = frame.createGraphics();
        try {
            for (Placement placement : placements) {
                float alpha = opacity(placement.cue(), timeUs);
                if (alpha <= 0) continue;
                graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                graphics.drawImage(placement.image(), placement.x(), placement.y(), null);
            }
        } finally {
            graphics.dispose();
        }
    }

    private static float opacity(SubtitleTrack.Cue cue, long timeUs) {
        double alpha = 1;
        if (cue.fadeUpUs() > 0) alpha = Math.min(alpha, (double) (timeUs - cue.startUs()) / cue.fadeUpUs());
        if (cue.fadeDownUs() > 0) alpha = Math.min(alpha, (double) (cue.endUs() - timeUs) / cue.fadeDownUs());
        return (float) Math.clamp(alpha, 0, 1);
    }

    private static List<Placement> prepare(List<SubtitleTrack.Cue> cues, int width, int height) {
        List<Placement> result = new ArrayList<>();
        for (SubtitleTrack.Cue cue : cues) {
            for (SubtitleTrack.Line line : cue.lines()) {
                RenderedLine rendered = drawLine(line, width);
                if (rendered == null) continue;
                BufferedImage image = rendered.image();
                int x = (int) Math.round(horizontalAnchor(line, width) -
                    switch (line.horizontalAlign().toLowerCase()) {
                        case "left" -> 0;
                        case "right" -> image.getWidth();
                        default -> image.getWidth() / 2.0;
                    });
                int baseline = verticalBaseline(line, height);
                int y = baseline - rendered.baseline();
                result.add(new Placement(cue, image, x, y));
            }
        }
        return result;
    }

    private static double horizontalAnchor(SubtitleTrack.Line line, int width) {
        double shift = width * line.horizontalPosition() / 100.0;
        return switch (line.horizontalAlign().toLowerCase()) {
            case "left" -> shift;
            case "right" -> width - shift;
            default -> width / 2.0 + shift;
        };
    }

    private static int verticalBaseline(SubtitleTrack.Line line, int height) {
        double shift = height * line.verticalPosition() / 100.0;
        return (int) Math.round(switch (line.verticalAlign().toLowerCase()) {
            case "top" -> shift;
            case "bottom" -> height - shift;
            default -> height / 2.0 + shift;
        });
    }

    private static RenderedLine drawLine(SubtitleTrack.Line line, int pictureWidth) {
        if (line.runs().isEmpty()) return null;
        double scale = pictureWidth / 2048.0;
        List<PreparedRun> runs = new ArrayList<>();
        int textWidth = 0, maxSize = 0;
        FontRenderContext context = new FontRenderContext(null, true, true);
        for (SubtitleTrack.Run run : line.runs()) {
            SubtitleTrack.Style style = run.style();
            int size = Math.clamp((int) Math.round(style.size() * scale), 12, 180);
            int flags = (style.bold() ? Font.BOLD : 0) | (style.italic() ? Font.ITALIC : 0);
            Font font = style.font().deriveFont(flags, (float) size);
            double aspect = Math.clamp(style.aspectAdjust(), 0.25, 4.0);
            GlyphVector glyphs = font.createGlyphVector(context, run.text());
            if (style.spacing() != 0) {
                double extra = Math.clamp(style.spacing(), -1.0, 4.0) * size;
                for (int i = 1; i <= glyphs.getNumGlyphs(); i++) {
                    Point2D position = glyphs.getGlyphPosition(i);
                    glyphs.setGlyphPosition(i, new Point2D.Double(position.getX() + i * extra,
                        position.getY()));
                }
            }
            int advance = Math.max(1, (int) Math.ceil(glyphs.getLogicalBounds().getWidth() * aspect));
            runs.add(new PreparedRun(glyphs, style, advance, aspect));
            textWidth += advance;
            maxSize = Math.max(maxSize, size);
        }
        if (textWidth <= 0) return null;
        int padding = Math.max(4, maxSize / 8);
        BufferedImage image = new BufferedImage(textWidth + padding * 2,
            maxSize * 2 + padding * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int x = padding, baseline = padding + maxSize;
            for (PreparedRun run : runs) {
                Shape shape = run.glyphs().getOutline();
                AffineTransform transform = new AffineTransform();
                transform.translate(x, baseline);
                transform.scale(run.aspect(), 1);
                shape = transform.createTransformedShape(shape);
                String effect = run.style().effect().toLowerCase();
                Color effectColor = run.style().effectColor();
                float effectPixels = (float) Math.max(1, maxSize * run.style().effectSize());
                if (effect.equals("border")) {
                    graphics.setColor(effectColor);
                    graphics.setStroke(new BasicStroke(effectPixels,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    graphics.draw(shape);
                } else if (effect.equals("shadow")) {
                    graphics.setColor(effectColor);
                    graphics.translate(effectPixels, effectPixels);
                    graphics.fill(shape);
                    graphics.translate(-effectPixels, -effectPixels);
                }
                graphics.setColor(run.style().color());
                graphics.fill(shape);
                if (run.style().underline()) {
                    graphics.setStroke(new BasicStroke(Math.max(1, maxSize / 18f)));
                    graphics.drawLine(x, baseline + 3, x + run.advance(), baseline + 3);
                }
                x += run.advance();
            }
        } finally {
            graphics.dispose();
        }
        return new RenderedLine(image, padding + maxSize);
    }

    private record RenderedLine(BufferedImage image, int baseline) {}
    private record PreparedRun(GlyphVector glyphs, SubtitleTrack.Style style, int advance, double aspect) {}
    private record Placement(SubtitleTrack.Cue cue, BufferedImage image, int x, int y) {}
}
