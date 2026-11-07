package day.alacraft.alamodlist.hash;

/**
 * The 32-bit file fingerprint CurseForge computes: MurmurHash2 over the file with the bytes 9, 10, 13 and 32
 * (tab, line feeds and the space) left out, seeded with {@code 1 ^ length}, where the length is that of the
 * remaining bytes.
 *
 * <p>The seed depends on the length of the <em>whole</em> input, so a streaming caller needs it in advance: read
 * the file once to {@linkplain #normalisedLength count} it, then again to feed {@link #update}.
 */
public final class Murmur2Cf {

    private static final int M = 0x5bd1e995;
    private static final int R = 24;

    private int h;
    private int k;
    private int shift;

    /** @param normalisedLength the number of bytes of the input that are not skipped (see the class description) */
    public Murmur2Cf(long normalisedLength) {
        this.h = 1 ^ (int) normalisedLength;
    }

    /** Whether the fingerprint ignores this byte. */
    public static boolean skipped(byte b) {
        return b == 9 || b == 10 || b == 13 || b == 32;
    }

    /** The number of bytes in the range the fingerprint does not skip: the length its seed is made of. */
    public static long normalisedLength(byte[] bytes, int offset, int length) {
        long n = 0;
        for (int i = offset; i < offset + length; i++) {
            if (!skipped(bytes[i])) {
                n++;
            }
        }
        return n;
    }

    /** Feeds the next bytes of the input. */
    public void update(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            byte b = bytes[i];
            if (skipped(b)) {
                continue;
            }
            k |= (b & 0xff) << shift;
            shift += 8;
            if (shift == 32) {
                k *= M;
                k ^= k >>> R;
                k *= M;
                h *= M;
                h ^= k;
                k = 0;
                shift = 0;
            }
        }
    }

    /** The fingerprint as an unsigned 32-bit number. */
    public long finish() {
        int result = h;
        if (shift > 0) {
            result ^= k;
            result *= M;
        }
        result ^= result >>> 13;
        result *= M;
        result ^= result >>> 15;
        return Integer.toUnsignedLong(result);
    }
}
