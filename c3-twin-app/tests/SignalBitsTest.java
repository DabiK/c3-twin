package fr.dabi.c3twin;
public final class SignalBitsTest {
    public static void main(String[] args) {
        byte[] idle = {0, (byte)128}, active = {8, 0};
        int[] bits = SignalBits.changed(idle, active);
        check(bits.length == 2 && bits[0] == 3 && bits[1] == 15, "normal and inverted candidates");
        check(!SignalBits.read(idle, 3, false) && SignalBits.read(active, 3, false), "active high");
        check(!SignalBits.read(idle, 15, true) && SignalBits.read(active, 15, true), "active low");
        check(SignalBits.changed(idle, new byte[]{0}).length == 0, "reject layout size change");
        check(SignalBits.changed(null, active).length == 0, "missing source");
        try { SignalBits.read(idle, 16, false); throw new AssertionError("out of bounds"); } catch (IllegalArgumentException expected) { }
        for (int bit = 0; bit < 32; bit++) {
            byte[] frame = new byte[4]; frame[bit / 8] = (byte)(1 << (bit % 8));
            check(SignalBits.changed(new byte[4], frame)[0] == bit, "all bit positions");
        }
        System.out.println("Signal calibration tests passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
