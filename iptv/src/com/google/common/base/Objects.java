package com.google.common.base;

public final class Objects {
    public static boolean equal(Object a, Object b) { return a == b || (a != null && a.equals(b)); }
    public static int hashCode(Object... objects) { return java.util.Arrays.hashCode(objects); }
    private Objects() {}
}
