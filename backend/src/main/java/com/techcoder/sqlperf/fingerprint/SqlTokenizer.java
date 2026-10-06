package com.techcoder.sqlperf.fingerprint;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, dialect-tolerant SQL lexer (Impala / Hive / Oracle). It only needs to be good enough to
 * recognise comments, literals, identifiers and parentheses for fingerprinting - it is not a parser.
 */
final class SqlTokenizer {

    enum Type { WORD, QUOTED_IDENT, STRING, NUMBER, SYMBOL }

    record Token(Type type, String text) {
        boolean isWord(String w) {
            return type == Type.WORD && text.equalsIgnoreCase(w);
        }

        boolean isSymbol(String s) {
            return type == Type.SYMBOL && text.equals(s);
        }
    }

    private SqlTokenizer() {
    }

    static List<Token> tokenize(String sql) {
        List<Token> out = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '\'' || c == '"' || c == '`') {
                int end = scanQuoted(sql, i, c);
                Type t = c == '\'' ? Type.STRING : Type.QUOTED_IDENT;
                // Impala/Hive accept "..." as string literals; treat them as strings so they get masked.
                if (c == '"') {
                    t = Type.STRING;
                }
                out.add(new Token(t, sql.substring(i, end)));
                i = end;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)))) {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    i++;
                }
                out.add(new Token(Type.NUMBER, sql.substring(start, i)));
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
                    i++;
                }
                out.add(new Token(Type.WORD, sql.substring(start, i)));
            } else {
                // Multi-char operators are kept together so "a>=b" and "a >= b" normalize identically.
                String two = i + 1 < n ? sql.substring(i, i + 2) : "";
                if (two.equals(">=") || two.equals("<=") || two.equals("<>") || two.equals("!=") || two.equals("||")
                        || two.equals("::")) {
                    out.add(new Token(Type.SYMBOL, two));
                    i += 2;
                } else {
                    out.add(new Token(Type.SYMBOL, String.valueOf(c)));
                    i++;
                }
            }
        }
        return out;
    }

    private static int scanQuoted(String sql, int start, char quote) {
        int i = start + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\\' && quote != '`') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }
}
