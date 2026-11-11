package com.google.common.primitives;

public final class Ints {
    public static int checkedCast(long v) {
        int r = (int) v;
        if (r != v) throw new IllegalArgumentException("Out of range: " + v);
        return r;
    }
    public static int[] toArray(java.util.Collection<? extends Number> c) {
        int[] out = new int[c.size()];
        int i = 0;
        for (Number n : c) out[i++] = n.intValue();
        return out;
    }
    private Ints() {}
}
