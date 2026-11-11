package com.google.common.collect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class MultimapBuilder {
    private MultimapBuilder() {}

    interface MapFactory {
        <K, V> Map<K, List<V>> create();
    }

    public static <K0 extends Object> MultimapBuilderWithKeys<K0> hashKeys() {
        return new MultimapBuilderWithKeys<K0>(new MapFactory() {
            @Override public <K, V> Map<K, List<V>> create() { return new HashMap<K, List<V>>(); }
        });
    }

    @SuppressWarnings("rawtypes")
    public static <K0 extends Comparable> MultimapBuilderWithKeys<K0> treeKeys() {
        return new MultimapBuilderWithKeys<K0>(new MapFactory() {
            @Override public <K, V> Map<K, List<V>> create() { return (Map<K, List<V>>) (Map<?, ?>) new TreeMap(); }
        });
    }

    public static final class MultimapBuilderWithKeys<K0> {
        private final MapFactory factory;
        MultimapBuilderWithKeys(MapFactory factory) { this.factory = factory; }
        public <V0> ListMultimapBuilder<K0, V0> arrayListValues() {
            return new ListMultimapBuilder<K0, V0>(factory);
        }
    }

    public static class ListMultimapBuilder<K0, V0> {
        private final MapFactory factory;
        ListMultimapBuilder(MapFactory factory) { this.factory = factory; }
        public ListMultimap<K0, V0> build() {
            return new ListMultimapImpl<K0, V0>(factory.<K0, V0>create());
        }
    }

    static class ListMultimapImpl<K, V> implements ListMultimap<K, V> {
        private final Map<K, List<V>> data;
        ListMultimapImpl(Map<K, List<V>> data) { this.data = data; }
        @SuppressWarnings("unchecked")
        private List<V> list(Object key) {
            List<V> l = data.get(key);
            if (l == null) { l = new ArrayList<V>(); data.put((K) key, l); }
            return l;
        }
        @Override public boolean put(K key, V value) { return list(key).add(value); }
        @Override public List<V> get(Object key) { return list(key); }
        @Override public Collection<V> values() {
            List<V> out = new ArrayList<V>();
            for (List<V> l : data.values()) out.addAll(l);
            return out;
        }
        @Override public Collection<?> entries() {
            List<EntryImpl<K, V>> out = new ArrayList<EntryImpl<K, V>>();
            for (Map.Entry<K, List<V>> e : data.entrySet())
                for (V v : e.getValue())
                    out.add(new EntryImpl<K, V>(e.getKey(), v));
            return out;
        }
        @Override public boolean remove(Object key, Object value) {
            List<V> l = data.get(key);
            return l != null && l.remove(value);
        }
    }

    static class EntryImpl<K, V> {
        private final K k; private final V v;
        EntryImpl(K k, V v) { this.k = k; this.v = v; }
        public K getKey() { return k; }
        public V getValue() { return v; }
    }
}
