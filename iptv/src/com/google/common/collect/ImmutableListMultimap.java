package com.google.common.collect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ImmutableListMultimap<K, V> implements ListMultimap<K, V> {
    private final Map<K, ImmutableList<V>> data = new LinkedHashMap<K, ImmutableList<V>>();

    private ImmutableListMultimap() {}

    public static <K, V> Builder<K, V> builder() { return new Builder<K, V>(); }

    @Override public ImmutableList<V> get(Object key) {
        ImmutableList<V> l = data.get(key);
        return l != null ? l : ImmutableList.<V>of();
    }
    @Override public boolean put(K key, V value) { throw new UnsupportedOperationException(); }
    @Override public Collection<V> values() {
        List<V> out = new ArrayList<V>();
        for (ImmutableList<V> l : data.values()) out.addAll(l);
        return out;
    }
    @Override public Collection<?> entries() {
        List<com.google.common.collect.MultimapBuilder.EntryImpl<K, V>> out =
                new ArrayList<com.google.common.collect.MultimapBuilder.EntryImpl<K, V>>();
        for (Map.Entry<K, ImmutableList<V>> e : data.entrySet())
            for (V v : e.getValue())
                out.add(new com.google.common.collect.MultimapBuilder.EntryImpl<K, V>(e.getKey(), v));
        return out;
    }
    @Override public boolean remove(Object key, Object value) { throw new UnsupportedOperationException(); }

    public static final class Builder<K, V> {
        private final Map<K, List<V>> acc = new LinkedHashMap<K, List<V>>();
        public Builder<K, V> putAll(K key, V[] values) {
            List<V> l = acc.get(key);
            if (l == null) { l = new ArrayList<V>(); acc.put(key, l); }
            for (V v : values) l.add(v);
            return this;
        }
        public ImmutableListMultimap<K, V> build() {
            ImmutableListMultimap<K, V> m = new ImmutableListMultimap<K, V>();
            for (Map.Entry<K, List<V>> e : acc.entrySet())
                m.data.put(e.getKey(), ImmutableList.<V>copyOf(e.getValue()));
            return m;
        }
    }
}
