package com.techcoder.sqlperf.common;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.techcoder.sqlperf.config.SptFunctionContributor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

/**
 * Server-side row grouping for the grids ("group by" from a column header): the distinct values of a column with
 * their row counts under the screen's filters, and the parent group keys as extra filters when the grid opens a
 * group.
 *
 * <p>Group keys travel as {@code gk=column:value} query parameters ({@code gk=column!} for the empty group), in the
 * order of the grid's grouping, so they work in plain GET links.
 */
public final class RowGrouping {

    /** Upper bound on distinct values returned for one grouping level. */
    private static final int MAX_BUCKETS = 5_000;

    private RowGrouping() {
    }

    /** One group: the column value ({@code null} = empty) and how many rows have it. */
    public record Bucket(Object key, long count) {
    }

    /** A groupable column: how to read it from the root, and how to turn a key from the URL back into a value. */
    public record Column<T>(BiFunction<Root<T>, CriteriaBuilder, Expression<?>> expr, Function<String, Object> parse) {

        public static <T> Column<T> text(String attr) {
            return new Column<>((r, cb) -> path(r, attr), s -> s);
        }

        public static <T> Column<T> number(String attr) {
            return new Column<>((r, cb) -> r.get(attr), Long::valueOf);
        }

        public static <T, E extends Enum<E>> Column<T> enumOf(String attr, Class<E> type) {
            return new Column<>((r, cb) -> r.get(attr), s -> Enum.valueOf(type, s));
        }

        /** Calendar day ('yyyy-MM-dd') of a timestamp attribute (or of the first non-null of several). */
        public static <T> Column<T> day(String... attrs) {
            return new Column<>((r, cb) -> {
                Expression<?> ts = r.get(attrs[0]);
                if (attrs.length > 1) {
                    CriteriaBuilder.Coalesce<Object> c = cb.coalesce();
                    for (String a : attrs) {
                        c.value(r.get(a));
                    }
                    ts = c;
                }
                return cb.function(SptFunctionContributor.DATE, String.class, ts);
            }, s -> LocalDate.parse(s).toString());
        }
    }

    /** A parent group the grid has opened. */
    public record Key(String column, String value) {
    }

    /** Parses {@code column:value} / {@code column!}; unknown columns are rejected. */
    public static <T> List<Key> parse(List<String> gk, Map<String, Column<T>> columns) {
        List<Key> out = new ArrayList<>();
        if (gk == null) {
            return out;
        }
        for (String k : gk) {
            int colon = k.indexOf(':');
            boolean empty = colon < 0 && k.endsWith("!");
            String column = empty ? k.substring(0, k.length() - 1) : colon < 0 ? k : k.substring(0, colon);
            if (!columns.containsKey(column)) {
                throw new IllegalArgumentException("Cannot group by " + column);
            }
            out.add(new Key(column, empty ? null : colon < 0 ? "" : k.substring(colon + 1)));
        }
        return out;
    }

    /** The opened parent groups as a filter (value equal, or empty). */
    public static <T> Specification<T> within(List<Key> keys, Map<String, Column<T>> columns) {
        if (keys.isEmpty()) {
            return null;
        }
        return (root, cq, cb) -> cb.and(keys.stream().map(k -> {
            Expression<?> e = columns.get(k.column()).expr().apply(root, cb);
            if (k.value() == null) {
                return cb.isNull(e);
            }
            return cb.equal(e, columns.get(k.column()).parse().apply(k.value()));
        }).toArray(jakarta.persistence.criteria.Predicate[]::new));
    }

    /**
     * Distinct values of {@code column} with counts, under {@code spec}; one page of them.
     *
     * @param byValue sort by the value (the grid sorted the group column) instead of by count, largest first
     */
    public static <T> PageResponse<Bucket> buckets(EntityManager em, Class<T> type, Specification<T> spec,
                                                   Map<String, Column<T>> columns, String column, boolean byValue,
                                                   boolean descending, int page, int size) {
        Column<T> col = columns.get(column);
        if (col == null) {
            throw new IllegalArgumentException("Cannot group by " + column);
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<T> root = cq.from(type);
        Expression<?> e = col.expr().apply(root, cb);
        Expression<Long> count = cb.count(root);
        cq.multiselect(e, count);
        if (spec != null) {
            var p = spec.toPredicate(root, cq, cb);
            if (p != null) {
                cq.where(p);
            }
        }
        cq.groupBy(e);
        List<Bucket> all = em.createQuery(cq).setMaxResults(MAX_BUCKETS).getResultList().stream()
                .map(r -> new Bucket(key(r[0]), (Long) r[1])).toList();
        Comparator<Bucket> byKey = (a, b) -> compareKeys(a.key(), b.key());
        Comparator<Bucket> byCount = Comparator.comparingLong(Bucket::count);
        // equal counts stay in value order whichever way the counts go
        Comparator<Bucket> order = byValue ? (descending ? byKey.reversed() : byKey)
                : (descending ? byCount.reversed() : byCount).thenComparing(byKey);
        List<Bucket> sorted = new ArrayList<>(all);
        sorted.sort(order);
        int from = Math.min(sorted.size(), Math.max(0, page) * Math.max(1, size));
        int to = Math.min(sorted.size(), from + Math.max(1, size));
        int pages = size <= 0 ? 1 : (int) Math.ceil(sorted.size() / (double) size);
        return new PageResponse<>(sorted.subList(from, to), page, size, sorted.size(), pages);
    }

    /** Natural order of the values; the empty group sorts last. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareKeys(Object a, Object b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : 1) : -1;
        }
        if (a instanceof Comparable ca && a.getClass() == b.getClass()) {
            return ca.compareTo(b);
        }
        return a.toString().compareTo(b.toString());
    }

    private static Object key(Object v) {
        return v instanceof Enum<?> en ? en.name() : v;
    }

    /** Nested attribute, e.g. {@code group.sqlEngine}. */
    static Expression<?> path(Root<?> root, String dotted) {
        Path<?> p = root;
        for (String part : dotted.split("\\.")) {
            p = p.get(part);
        }
        return p;
    }
}
