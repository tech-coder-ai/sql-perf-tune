package com.techcoder.sqlperf.common;

import java.util.Locale;

import com.techcoder.sqlperf.config.SptFunctionContributor;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import org.springframework.data.jpa.domain.Specification;

/** Small helpers for building optional filters. */
public final class Specs {

    private Specs() {
    }

    public static <T> Specification<T> eq(String attr, Object value) {
        return value == null ? null : (root, q, cb) -> cb.equal(root.get(attr), value);
    }

    /** Case-insensitive "contains" match. Works on VARCHAR2 and CLOB in Oracle and on TEXT in SQLite. */
    public static <T> Specification<T> like(String attr, String value) {
        if (Texts.isBlank(value)) {
            return null;
        }
        String pattern = containsPattern(value);
        return (root, q, cb) -> cb.like(lower(cb, root.get(attr)), pattern, '\\');
    }

    /** Case-insensitive pattern for {@link #lower}; escapes LIKE wildcards with backslash. */
    public static String containsPattern(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    /** lower() that also accepts CLOB columns, see {@link SptFunctionContributor}. */
    public static Expression<String> lower(CriteriaBuilder cb, Expression<?> expr) {
        return cb.function(SptFunctionContributor.LOWER, String.class, expr);
    }

    public static <T, Y extends Comparable<? super Y>> Specification<T> gte(String attr, Y value) {
        return value == null ? null : (root, q, cb) -> cb.greaterThanOrEqualTo(root.get(attr), value);
    }

    public static <T, Y extends Comparable<? super Y>> Specification<T> lte(String attr, Y value) {
        return value == null ? null : (root, q, cb) -> cb.lessThanOrEqualTo(root.get(attr), value);
    }

    @SafeVarargs
    public static <T> Specification<T> all(Specification<T>... specs) {
        Specification<T> out = Specification.unrestricted();
        for (Specification<T> s : specs) {
            if (s != null) {
                out = out.and(s);
            }
        }
        return out;
    }
}
