package com.google.common.collect;

public class ImmutableSet<E> extends java.util.AbstractSet<E> {
    private final java.util.Set<E> data;
    private ImmutableSet(java.util.Set<? extends E> src) { this.data = new java.util.LinkedHashSet<E>(src); }

    public static <E> ImmutableSet<E> of(E a, E b) {
        java.util.Set<E> s = new java.util.LinkedHashSet<E>(); s.add(a); s.add(b); return new ImmutableSet<E>(s);
    }
    public static <E> ImmutableSet<E> of(E a, E b, E c) {
        java.util.Set<E> s = new java.util.LinkedHashSet<E>(); s.add(a); s.add(b); s.add(c);
        return new ImmutableSet<E>(s);
    }
    public static <E> ImmutableSet<E> copyOf(java.util.Collection<? extends E> c) {
        return new ImmutableSet<E>(new java.util.LinkedHashSet<E>(c));
    }
    public static <E> ImmutableSet<E> copyOf(E[] a) {
        java.util.Set<E> s = new java.util.LinkedHashSet<E>();
        for (E v : a) s.add(v);
        return new ImmutableSet<E>(s);
    }
    @Override public java.util.Iterator<E> iterator() {
        final java.util.Iterator<E> it = data.iterator();
        return new UnmodifiableIterator<E>() {
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public E next() { return it.next(); }
        };
    }
    @Override public int size() { return data.size(); }
}
