package de.erethon.mccinema.video;

public record VideoTransform(double zoom, double focusX, double focusY) {

    public static final double DEFAULT_ZOOM = 1.0;
    public static final VideoTransform DEFAULT = new VideoTransform(DEFAULT_ZOOM, 0.5, 0.5);

    public VideoTransform {
        zoom = Double.isFinite(zoom) && zoom > 0.0 ? zoom : DEFAULT_ZOOM;
        focusX = finiteFocus(focusX);
        focusY = finiteFocus(focusY);
    }

    public VideoTransform scaleZoom(double factor) {
        double scaledZoom = zoom * factor;
        return Double.isFinite(scaledZoom) && scaledZoom > 0.0
            ? new VideoTransform(scaledZoom, focusX, focusY)
            : this;
    }

    public VideoTransform panBy(double x, double y) {
        return new VideoTransform(zoom, focusX + x, focusY + y);
    }

    public boolean isDefault() {
        return Math.abs(zoom - DEFAULT_ZOOM) < 0.0001 &&
            Math.abs(focusX - 0.5) < 0.0001 && Math.abs(focusY - 0.5) < 0.0001;
    }

    private static double finiteFocus(double value) {
        return Double.isFinite(value) ? value : 0.5;
    }
}
