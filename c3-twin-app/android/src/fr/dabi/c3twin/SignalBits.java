package fr.dabi.c3twin;

/** Pure bit calibration, independent of any guessed vehicle layout. */
final class SignalBits {
    static boolean read(byte[] bytes, int bit, boolean inverted) {
        if (bytes == null || bit < 0 || bit >= bytes.length * 8) throw new IllegalArgumentException("Signal absent");
        return (((bytes[bit / 8] & 255) & (1 << (bit % 8))) != 0) != inverted;
    }
    static int[] changed(byte[] before, byte[] after) {
        if (before == null || after == null || before.length != after.length) return new int[0];
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        for (int bit = 0; bit < before.length * 8; bit++)
            if (read(before, bit, false) != read(after, bit, false)) out.add(bit);
        int[] result = new int[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i);
        return result;
    }
}
