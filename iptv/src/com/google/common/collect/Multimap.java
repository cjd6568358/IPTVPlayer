package com.google.common.collect;

import java.util.Collection;

public interface Multimap<K, V> {
    boolean put(K key, V value);
    Collection<V> get(Object key);
    Collection<V> values();
    Collection<?> entries();
    boolean remove(Object key, Object value);
}
