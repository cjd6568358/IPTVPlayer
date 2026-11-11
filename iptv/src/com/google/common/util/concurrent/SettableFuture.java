package com.google.common.util.concurrent;

public final class SettableFuture<V> implements ListenableFuture<V> {
    private V value;
    private Throwable error;
    private boolean done;

    public static <V> SettableFuture<V> create() { return new SettableFuture<V>(); }

    public synchronized boolean set(V v) {
        if (done) return false;
        value = v; done = true; notifyAll(); return true;
    }

    public synchronized boolean setException(Throwable t) {
        if (done) return false;
        error = t; done = true; notifyAll(); return true;
    }

    public synchronized V get() throws InterruptedException, java.util.concurrent.ExecutionException {
        while (!done) wait();
        if (error != null) throw new java.util.concurrent.ExecutionException(error);
        return value;
    }

    public V get(long timeout, java.util.concurrent.TimeUnit unit)
            throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        synchronized (this) {
            while (!done) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new java.util.concurrent.TimeoutException();
                wait(left);
            }
        }
        return get();
    }

    public synchronized boolean cancel(boolean mayInterrupt) { return false; }
    public synchronized boolean isCancelled() { return false; }
    public synchronized boolean isDone() { return done; }
}
