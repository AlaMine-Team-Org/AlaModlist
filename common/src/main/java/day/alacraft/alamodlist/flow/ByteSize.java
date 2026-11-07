package day.alacraft.alamodlist.flow;

import java.util.Locale;

/** A size for a person: {@code 640 KB}, {@code 3.1 GB}. The decimal point is always a dot, so a test is the same everywhere. */
public final class ByteSize {

    private ByteSize() {
    }

    public static String human(long bytes) {
        if (bytes < 1024) {
            return Math.max(0, bytes) + " B";
        }
        if (bytes < 1024L * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
