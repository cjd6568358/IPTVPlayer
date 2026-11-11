package com.google.common.collect;

public abstract class ComparisonChain {
    private ComparisonChain() {}

    public static ComparisonChain start() { return ACTIVE; }

    public abstract ComparisonChain compare(int a, int b);
    public abstract ComparisonChain compare(long a, long b);
    public abstract ComparisonChain compare(Object a, Object b, java.util.Comparator<?> cmp);
    public abstract ComparisonChain compareFalseFirst(boolean a, boolean b);
    public abstract ComparisonChain compareTrueFirst(boolean a, boolean b);
    public abstract int result();

    private static final ComparisonChain ACTIVE = new ComparisonChain() {
        @Override public ComparisonChain compare(int a, int b) { return a < b ? LESS : a > b ? GREATER : this; }
        @Override public ComparisonChain compare(long a, long b) { return a < b ? LESS : a > b ? GREATER : this; }
        @SuppressWarnings("unchecked")
        @Override public ComparisonChain compare(Object a, Object b, java.util.Comparator<?> cmp) {
            int r = ((java.util.Comparator<Object>) cmp).compare(a, b);
            return r < 0 ? LESS : r > 0 ? GREATER : this;
        }
        @Override public ComparisonChain compareFalseFirst(boolean a, boolean b) {
            return a == b ? this : a ? GREATER : LESS;
        }
        @Override public ComparisonChain compareTrueFirst(boolean a, boolean b) {
            return a == b ? this : a ? LESS : GREATER;
        }
        @Override public int result() { return 0; }
    };

    private static final ComparisonChain LESS = new ComparisonChain() {
        @Override public ComparisonChain compare(int a, int b) { return this; }
        @Override public ComparisonChain compare(long a, long b) { return this; }
        @Override public ComparisonChain compare(Object a, Object b, java.util.Comparator<?> cmp) { return this; }
        @Override public ComparisonChain compareFalseFirst(boolean a, boolean b) { return this; }
        @Override public ComparisonChain compareTrueFirst(boolean a, boolean b) { return this; }
        @Override public int result() { return -1; }
    };

    private static final ComparisonChain GREATER = new ComparisonChain() {
        @Override public ComparisonChain compare(int a, int b) { return this; }
        @Override public ComparisonChain compare(long a, long b) { return this; }
        @Override public ComparisonChain compare(Object a, Object b, java.util.Comparator<?> cmp) { return this; }
        @Override public ComparisonChain compareFalseFirst(boolean a, boolean b) { return this; }
        @Override public ComparisonChain compareTrueFirst(boolean a, boolean b) { return this; }
        @Override public int result() { return 1; }
    };
}
