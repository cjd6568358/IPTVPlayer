package com.google.common.collect;

public interface ListMultimap<K, V> extends Multimap<K, V> {
    java.util.List<V> get(Object key);
}
