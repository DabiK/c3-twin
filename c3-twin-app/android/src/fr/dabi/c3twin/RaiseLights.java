package fr.dabi.c3twin;

/** Raise 0x7D subtype 1. Low/high mapping verified on this C3's CAN box. */
final class RaiseLights {
    final boolean position, low, high, left, right, fogFront, fogRear;

    RaiseLights(int bits) {
        position = (bits & 0x80) != 0;
        low = (bits & 0x40) != 0;
        high = (bits & 0x20) != 0;
        left = (bits & 0x10) != 0;
        right = (bits & 0x08) != 0;
        fogFront = (bits & 0x04) != 0;
        fogRear = (bits & 0x02) != 0;
    }

    String beam() { return high ? "high" : low ? "low" : position ? "position" : "off"; }
}
