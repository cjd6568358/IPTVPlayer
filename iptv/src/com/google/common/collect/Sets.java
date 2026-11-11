package com.google.common.collect;

public final class Sets {
    public static <E> java.util.Set<E> newIdentityHashSet() {
        return java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<E, Boolean>());
    }
    public static <E> SetView<E> intersection(final java.util.Set<E> a, final java.util.Set<E> b) {
        java.util.Set<E> out = new java.util.LinkedHashSet<E>(a);
        out.retainAll(b);
        return new SetView<E>(out);
    }

    public static class SetView<E> extends java.util.AbstractSet<E> {
        private final java.util.Set<E> data;
        SetView(java.util.Set<E> data) { this.data = data; }
        @Override public java.util.Iterator<E> iterator() { return data.iterator(); }
        @Override public int size() { return data.size(); }
    }

    private Sets() {}
}
