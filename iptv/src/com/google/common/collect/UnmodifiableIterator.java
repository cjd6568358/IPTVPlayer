package com.google.common.collect;

public abstract class UnmodifiableIterator<E> implements java.util.Iterator<E> {
    @Override public final void remove() { throw new UnsupportedOperationException(); }
}
