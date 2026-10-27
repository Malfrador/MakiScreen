package de.erethon.mccinema.video;

public final class VideoFileTypesTest {
    public static void main(String[] args) {
        require(VideoFileTypes.isSupported("film.mov"));
        require(VideoFileTypes.isSupported("FILM.MOV"));
        require(VideoFileTypes.isSupported("film.mp4"));
        require(!VideoFileTypes.isSupported("film.mov.tmp"));
        require(!VideoFileTypes.isSupported(null));
        System.out.println("PASS: video extension recognition");
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Unexpected video extension result");
    }
}
