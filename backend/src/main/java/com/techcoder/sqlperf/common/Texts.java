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

    /**
     * Truncates to fit a VARCHAR2(n) column. Oracle counts VARCHAR2 lengths in <b>bytes</b> by default, so the
     * limit is applied to the UTF-8 length: a value with accented or non-Latin characters can be longer in bytes
     * than in characters (SQLite does not enforce lengths at all, so this only shows up on Oracle).
     */
    public static String truncate(String s, int max) {
        if (s == null || utf8Length(s) <= max) {
            return s;
        }
        int budget = max - 3;
        int bytes = 0;
        int end = 0;
        while (end < s.length()) {
            int cp = s.codePointAt(end);
            int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + len > budget) {
                break;
            }
            bytes += len;
            end += Character.charCount(cp);
        }
        return s.substring(0, end) + "...";
    }

    /** Length of the string in UTF-8 bytes. */
    public static int utf8Length(String s) {
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            bytes += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            i += Character.charCount(cp);
        }
        return bytes;
    }
}
