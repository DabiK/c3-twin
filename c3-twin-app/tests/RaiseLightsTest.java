package fr.dabi.c3twin;

/** Replay of the C3's real lamp transitions captured on 2026-11-04. */
public final class RaiseLightsTest {
    public static void main(String[] args) {
        check(new RaiseLights(0x00).beam().equals("off"), "off");
        RaiseLights position = new RaiseLights(0x80);
        check(position.position && !position.low && !position.high, "position only");
        RaiseLights low = new RaiseLights(0xC0);
        check(low.low && !low.high && low.beam().equals("low"), "dipped headlights");
        RaiseLights high = new RaiseLights(0xA0);
        check(high.high && !high.low && high.beam().equals("high"), "main beam only");
        check(new RaiseLights(0xE0).low && new RaiseLights(0xE0).high, "both beams");
        for (int bits : new int[] {0x88, 0x80, 0x88, 0x80}) {
            RaiseLights phase = new RaiseLights(bits);
            check(phase.right == (bits == 0x88) && !phase.left, "right flash phase");
        }
        for (int bits : new int[] {0xD0, 0xC0, 0xD0, 0xC0}) {
            RaiseLights phase = new RaiseLights(bits);
            check(phase.left == (bits == 0xD0) && !phase.right && phase.low, "left flash phase");
        }
        check(new RaiseLights(0x98).left && new RaiseLights(0x98).right, "both indicators");
        check(new RaiseLights(0x86).fogFront && new RaiseLights(0x86).fogRear, "fog masks");
        System.out.println("Raise lighting replay passed");
    }
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
}
