package com.techcoder.sqlperf.ingestion;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.log.QueryLog;

/**
 * One source row as text values keyed by {@link LogColumn}; converts to {@link QueryLog}.
 * Conversion errors raise {@link IllegalArgumentException} with a user readable message.
 */
public final class RawLogRecord {

    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSSSSS][.SSSSSS][.SSS][.SS][.S]"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss[.SSS]"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm[:ss]"),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm[:ss]"),
            DateTimeFormatter.ofPattern("dd-MMM-yy hh.mm.ss[.SSSSSSSSS][.SSSSSS] a"));

    private final Map<LogColumn, String> values = new EnumMap<>(LogColumn.class);
    private final Map<LogColumn, LocalDateTime> dates = new EnumMap<>(LogColumn.class);
    private final long rowNumber;

    public RawLogRecord(long rowNumber) {
        this.rowNumber = rowNumber;
    }

    public long rowNumber() {
        return rowNumber;
    }

    public void put(LogColumn col, String value) {
        values.put(col, Texts.trimToNull(value));
    }

    /** Typed timestamp from Excel / JDBC sources (bypasses string parsing). */
    public void putDate(LogColumn col, LocalDateTime value) {
        dates.put(col, value);
    }

    public boolean isEmpty() {
        return values.values().stream().allMatch(v -> v == null) && dates.isEmpty();
    }

    public QueryLog toQueryLog() {
        QueryLog q = new QueryLog();
        q.setSeqId(parseLong(LogColumn.SEQ_ID));
        q.setExecutedQuery(values.get(LogColumn.EXECUTED_QUERY));
        q.setUserQuery(values.get(LogColumn.USER_QUERY));
        q.setErrorCode(Texts.truncate(values.get(LogColumn.ERROR_CODE), 100));
        q.setErrorCategory(Texts.truncate(values.get(LogColumn.ERROR_CATEGORY), 200));
        q.setErrorMessage(values.get(LogColumn.ERROR_MESSAGE));
        q.setUserId(Texts.truncate(values.get(LogColumn.USER_ID), 200));
        q.setStartTime(date(LogColumn.START_TIME));
        q.setEndTime(date(LogColumn.END_TIME));
        Double duration = parseDouble(LogColumn.DURATION_MINUTES);
        if (duration == null && q.getStartTime() != null && q.getEndTime() != null) {
            duration = Duration.between(q.getStartTime(), q.getEndTime()).toMillis() / 60_000d;
        }
        q.setDurationMinutes(duration);
        if (Texts.isBlank(q.getExecutedQuery()) && Texts.isBlank(q.getUserQuery())) {
            throw new IllegalArgumentException("executed_query and user_query are both empty");
        }
        return q;
    }

    private LocalDateTime date(LogColumn col) {
        if (dates.containsKey(col)) {
            return dates.get(col);
        }
        String v = values.get(col);
        return v == null ? null : parseDate(col, v);
    }

    static LocalDateTime parseDate(LogColumn col, String v) {
        for (DateTimeFormatter f : FORMATS) {
            try {
                return LocalDateTime.parse(v, f);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        try {
            return OffsetDateTime.parse(v).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(v).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            throw new IllegalArgumentException(col.name().toLowerCase() + ": unrecognised date '" + v + "'");
        }
    }

    private Long parseLong(LogColumn col) {
        String v = values.get(col);
        if (v == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(v).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException(col.name().toLowerCase() + ": not an integer '" + v + "'");
        }
    }

    private Double parseDouble(LogColumn col) {
        String v = values.get(col);
        if (v == null) {
            return null;
        }
        try {
            return Double.valueOf(v.replace(",", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(col.name().toLowerCase() + ": not a number '" + v + "'");
        }
    }
}
