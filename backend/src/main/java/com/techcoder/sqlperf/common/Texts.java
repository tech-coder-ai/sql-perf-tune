package com.techcoder.sqlperf.common;

public final class Texts {

    private Texts() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    /** Truncates to fit a VARCHAR2(n) column. */
    public static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
