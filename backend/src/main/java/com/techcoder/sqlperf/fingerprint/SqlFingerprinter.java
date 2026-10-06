package com.techcoder.sqlperf.fingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.techcoder.sqlperf.config.SptProperties;
import com.techcoder.sqlperf.fingerprint.SqlTokenizer.Token;
import com.techcoder.sqlperf.fingerprint.SqlTokenizer.Type;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Produces the grouping key for a SQL statement.
 *
 * <p>Normalization, in order:
 * <ol>
 *   <li>comments removed, whitespace collapsed, keywords / identifiers lower-cased</li>
 *   <li>every WHERE clause (at any nesting depth) removed - "the same SQL with different filters"</li>
 *   <li>remaining literals replaced by {@code ?}; {@code IN (?, ?, ...)} collapsed to {@code IN (?)}</li>
 *   <li>trailing semicolons removed</li>
 * </ol>
 * The fingerprint is the SHA-256 (hex) of the normalized text.
 */
@Component
public class SqlFingerprinter {

    /** Keywords that end a WHERE clause when found at the WHERE's own nesting depth. */
    private static final Set<String> WHERE_TERMINATORS = Set.of(
            "group", "order", "having", "limit", "offset", "union", "intersect", "except", "minus",
            "window", "qualify", "fetch", "sort", "distribute", "cluster", "connect", "start", "model");

    /** Keywords followed by a space before "(" when cleansing (everything else is treated as a function call). */
    private static final Set<String> SPACED_KEYWORDS = Set.of("as", "in", "from", "join", "on", "and", "or", "not",
            "exists", "values", "using", "over", "where", "select", "with", "then", "else", "when", "union", "all",
            "by", "having", "into", "table");

    private final boolean stripWhere;
    private final boolean maskLiterals;

    @Autowired
    public SqlFingerprinter(SptProperties props) {
        this(props.fingerprint().stripWhereClause(), props.fingerprint().maskLiterals());
    }

    public SqlFingerprinter(boolean stripWhere, boolean maskLiterals) {
        this.stripWhere = stripWhere;
        this.maskLiterals = maskLiterals;
    }

    public record Result(String fingerprint, String normalizedSql) {
    }

    public Result fingerprint(String sql) {
        String normalized = normalize(sql);
        return new Result(sha256(normalized), normalized);
    }

    public String normalize(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        List<Token> tokens = SqlTokenizer.tokenize(sql);
        if (stripWhere) {
            tokens = removeWhereClauses(tokens);
        }
        if (maskLiterals) {
            tokens = maskLiterals(tokens);
        }
        while (!tokens.isEmpty() && tokens.getLast().isSymbol(";")) {
            tokens.removeLast();
        }
        return render(tokens);
    }

    /**
     * "Cleansed" SQL: comments removed, whitespace collapsed and trailing semicolons dropped, but literals,
     * filters and identifier case kept - i.e. still runnable.
     */
    public String cleanse(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        List<Token> tokens = new ArrayList<>(SqlTokenizer.tokenize(sql));
        while (!tokens.isEmpty() && tokens.getLast().isSymbol(";")) {
            tokens.removeLast();
        }
        StringBuilder sb = new StringBuilder();
        Token prev = null;
        for (Token t : tokens) {
            boolean tight = prev != null && (t.isSymbol(",") || t.isSymbol(")") || t.isSymbol(".") || prev.isSymbol("(")
                    || prev.isSymbol(".") || (t.isSymbol("(") && prev.type() == Type.WORD
                    && !SPACED_KEYWORDS.contains(prev.text().toLowerCase(Locale.ROOT))));
            if (prev != null && !tight) {
                sb.append(' ');
            }
            sb.append(t.text());
            prev = t;
        }
        return sb.toString();
    }

    private static List<Token> removeWhereClauses(List<Token> in) {
        List<Token> out = new ArrayList<>(in.size());
        int depth = 0;
        int i = 0;
        while (i < in.size()) {
            Token t = in.get(i);
            if (t.isWord("where")) {
                int whereDepth = depth;
                int j = i + 1;
                int d = depth;
                while (j < in.size()) {
                    Token u = in.get(j);
                    if (u.isSymbol("(")) {
                        d++;
                    } else if (u.isSymbol(")")) {
                        if (d == whereDepth) {
                            break;
                        }
                        d--;
                    } else if (d == whereDepth && (u.isSymbol(";")
                            || (u.type() == Type.WORD && WHERE_TERMINATORS.contains(u.text().toLowerCase(Locale.ROOT))))) {
                        break;
                    }
                    j++;
                }
                i = j;
                continue;
            }
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                depth--;
            }
            out.add(t);
            i++;
        }
        return out;
    }

    private static List<Token> maskLiterals(List<Token> in) {
        List<Token> out = new ArrayList<>(in.size());
        Token placeholder = new Token(Type.SYMBOL, "?");
        for (int i = 0; i < in.size(); i++) {
            Token t = in.get(i);
            boolean literal = t.type() == Type.STRING || t.type() == Type.NUMBER;
            // keep "-5" and "5" identical: drop a unary minus right before a literal
            if (literal && !out.isEmpty() && out.getLast().isSymbol("-") && out.size() >= 2
                    && isOperatorLike(out.get(out.size() - 2))) {
                out.removeLast();
            }
            out.add(literal ? placeholder : t);
        }
        return collapseInLists(out);
    }

    private static boolean isOperatorLike(Token t) {
        return t.type() == Type.SYMBOL && !t.text().equals(")") && !t.text().equals("?");
    }

    /** {@code in ( ? , ? , ? )} -> {@code in ( ? )} so list length does not split groups. */
    private static List<Token> collapseInLists(List<Token> in) {
        List<Token> out = new ArrayList<>(in.size());
        int i = 0;
        while (i < in.size()) {
            Token t = in.get(i);
            out.add(t);
            if (t.isWord("in") && i + 1 < in.size() && in.get(i + 1).isSymbol("(")) {
                int j = i + 2;
                boolean onlyPlaceholders = true;
                while (j < in.size() && !in.get(j).isSymbol(")")) {
                    Token u = in.get(j);
                    if (!(u.isSymbol("?") || u.isSymbol(","))) {
                        onlyPlaceholders = false;
                        break;
                    }
                    j++;
                }
                if (onlyPlaceholders && j < in.size() && j > i + 2) {
                    out.add(in.get(i + 1));
                    out.add(new Token(Type.SYMBOL, "?"));
                    out.add(in.get(j));
                    i = j + 1;
                    continue;
                }
            }
            i++;
        }
        return out;
    }

    private static String render(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Token t : tokens) {
            String text = switch (t.type()) {
                case WORD -> t.text().toLowerCase(Locale.ROOT);
                case QUOTED_IDENT -> t.text().toLowerCase(Locale.ROOT);
                default -> t.text();
            };
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
