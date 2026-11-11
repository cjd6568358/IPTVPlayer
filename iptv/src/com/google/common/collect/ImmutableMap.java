package com.google.common.collect;

public class ImmutableMap<K, V> extends java.util.AbstractMap<K, V> {
    private final java.util.Map<K, V> data;
    private ImmutableMap(java.util.Map<? extends K, ? extends V> src) {
        this.data = new java.util.LinkedHashMap<K, V>(src);
    }

    public static <K, V> ImmutableMap<K, V> of() { return new ImmutableMap<K, V>(new java.util.LinkedHashMap<K, V>()); }
    public static <K, V> ImmutableMap<K, V> copyOf(java.util.Map<? extends K, ? extends V> m) {
        return new ImmutableMap<K, V>(m);
    }
    public static <K, V> Builder<K, V> builder() { return new Builder<K, V>(); }

    @Override public java.util.Set<Entry<K, V>> entrySet() { return data.entrySet(); }
    @Override public V get(Object key) { return data.get(key); }

    public static final class Builder<K, V> {
        private final java.util.Map<K, V> data = new java.util.LinkedHashMap<K, V>();
        public Builder<K, V> put(K k, V v) { data.put(k, v); return this; }
        public ImmutableMap<K, V> build() { return new ImmutableMap<K, V>(data); }
    }
}
