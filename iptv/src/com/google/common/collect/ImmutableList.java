package com.google.common.collect;

public class ImmutableList<E> extends java.util.AbstractList<E> {
    private final java.util.List<E> data;
    private ImmutableList(java.util.List<E> src) { this.data = new java.util.ArrayList<E>(src); }

    public static <E> ImmutableList<E> of() { return new ImmutableList<E>(new java.util.ArrayList<E>()); }
    public static <E> ImmutableList<E> of(E a) {
        java.util.List<E> l = new java.util.ArrayList<E>(); l.add(a); return new ImmutableList<E>(l);
    }
    public static <E> ImmutableList<E> of(E a, E b) {
        java.util.List<E> l = new java.util.ArrayList<E>(); l.add(a); l.add(b); return new ImmutableList<E>(l);
    }
    public static <E> ImmutableList<E> of(E a, E b, E c, E d, E e) {
        java.util.List<E> l = new java.util.ArrayList<E>();
        l.add(a); l.add(b); l.add(c); l.add(d); l.add(e);
        return new ImmutableList<E>(l);
    }
    @SuppressWarnings("unchecked")
    public static <E> ImmutableList<E> copyOf(java.util.Collection<? extends E> c) {
        return new ImmutableList<E>((java.util.List<E>) new java.util.ArrayList<E>(c));
    }
    @SuppressWarnings("unchecked")
    public static <E> ImmutableList<E> copyOf(E[] a) {
        java.util.List<E> l = new java.util.ArrayList<E>();
        for (E v : a) l.add(v);
        return new ImmutableList<E>(l);
    }
    public static <E> Builder<E> builder() { return new Builder<E>(); }

    @Override public E get(int i) { return data.get(i); }
    @Override public int size() { return data.size(); }

    public static final class Builder<E> {
        private final java.util.List<E> data = new java.util.ArrayList<E>();
        public Builder<E> add(E v) { data.add(v); return this; }
        public Builder<E> addAll(java.lang.Iterable<? extends E> c) {
            for (E v : c) data.add(v);
            return this;
        }
        public ImmutableList<E> build() { return new ImmutableList<E>(data); }
    }
}
