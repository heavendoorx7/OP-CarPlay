package com.shilapi.xcertplay;

/** Pure bounded policy for the P7 navigation-image experiment. */
public final class P7ImagePolicy {
    public final int width, height, format, fps, intervalMs;
    public P7ImagePolicy(boolean need, int width, int height, int fps, int format) {
        if (!need) throw new IllegalArgumentException("no_active_request");
        if (width < 64 || height < 64 || width > 1024 || height > 1024)
            throw new IllegalArgumentException("unsupported_dimensions");
        if (fps < 1 || fps > 240) throw new IllegalArgumentException("invalid_fps");
        // JPEG is explicitly Format 2 in the examined P7 receiver. No speculative raw conversion.
        if (format != 2) throw new IllegalArgumentException("unsupported_format");
        this.width = width; this.height = height; this.format = format; this.fps = fps;
        this.intervalMs = (int) Math.ceil(1000.0 / Math.min(5, fps));
    }
    public boolean sameRequest(P7ImagePolicy next) {
        return width == next.width && height == next.height && format == next.format && fps == next.fps;
    }
    public static void checkJpeg(byte[] data) {
        if (data == null || data.length < 4 || data.length > 1048576
            || (data[0] & 255) != 255 || (data[1] & 255) != 216
            || (data[data.length-2] & 255) != 255 || (data[data.length-1] & 255) != 217)
            throw new IllegalArgumentException("invalid_jpeg");
    }
}
