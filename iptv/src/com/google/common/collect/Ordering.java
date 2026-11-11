package com.google.common.collect;

public class Ordering<T> implements java.util.Comparator<T> {
    private final java.util.Comparator<T> cmp;
    private Ordering(java.util.Comparator<T> cmp) { this.cmp = cmp; }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> Ordering<T> natural() {
        return new Ordering<T>((java.util.Comparator<T>) new java.util.Comparator<Comparable>() {
            @Override public int compare(Comparable a, Comparable b) { return a.compareTo(b); }
        });
    }
    public static <T> Ordering<T> from(java.util.Comparator<T> cmp) { return new Ordering<T>(cmp); }
    @Override public int compare(T a, T b) { return cmp.compare(a, b); }
    public Ordering<T> reverse() { return new Ordering<T>(java.util.Collections.reverseOrder(cmp)); }
}
