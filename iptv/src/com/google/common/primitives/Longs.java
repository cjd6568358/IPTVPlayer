package com.google.common.primitives;

public final class Longs {
    public static int hashCode(long v) { return (int) (v ^ (v >>> 32)); }
    public static long max(long[] a) {
        long m = a[0];
        for (long v : a) if (v > m) m = v;
        return m;
    }
    private Longs() {}
}
