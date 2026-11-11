package com.google.common.base;

public final class Splitter {
    private final String sep;
    private Splitter(String sep) { this.sep = sep; }
    public static Splitter on(char c) { return new Splitter(String.valueOf(c)); }
    public static Splitter on(String s) { return new Splitter(s); }
    public java.util.List<String> splitToList(CharSequence seq) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        String s = seq.toString();
        int from = 0, idx;
        while ((idx = s.indexOf(sep, from)) >= 0) {
            out.add(s.substring(from, idx));
            from = idx + sep.length();
        }
        out.add(s.substring(from));
        return out;
    }
}
