package com.google.common.collect;

public final class Iterables {
    public static <T> T getFirst(Iterable<? extends T> it, T def) {
        java.util.Iterator<? extends T> i = it.iterator();
        return i.hasNext() ? i.next() : def;
    }
    public static <T> T getLast(Iterable<? extends T> it) {
        T last = null;
        for (T v : it) last = v;
        return last;
    }
    private Iterables() {}
}
