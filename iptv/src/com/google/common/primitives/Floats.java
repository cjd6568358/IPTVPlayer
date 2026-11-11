package com.google.common.primitives;

public final class Floats {
    public static int hashCode(float v) { return Float.valueOf(v).hashCode(); }
    private Floats() {}
}
