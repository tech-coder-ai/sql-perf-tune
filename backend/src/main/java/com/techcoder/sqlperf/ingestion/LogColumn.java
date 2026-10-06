package com.techcoder.sqlperf.ingestion;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Standard input-log columns. Header matching ignores case, spaces, dashes and underscores and accepts
 * common aliases (e.g. {@code useris}, {@code user}, {@code sql}).
 */
public enum LogColumn {
    SEQ_ID("seqid", "seq", "sequenceid", "sequence"),
    EXECUTED_QUERY("executedquery", "query", "sql", "sqltext", "statement"),
    USER_QUERY("userquery", "originalquery"),
    ERROR_CODE("errorcode"),
    ERROR_CATEGORY("errorcategory"),
    ERROR_MESSAGE("errormessage", "error"),
    USER_ID("userid", "useris", "user", "username", "userids"),
    START_TIME("starttime", "start", "startedat"),
    END_TIME("endtime", "end", "endedat"),
    DURATION_MINUTES("durationminutes", "duration", "durationmins");

    private static final Map<String, LogColumn> BY_ALIAS = new HashMap<>();

    static {
        for (LogColumn c : values()) {
            for (String a : c.aliases) {
                BY_ALIAS.put(a, c);
            }
        }
    }

    private final String[] aliases;

    LogColumn(String... aliases) {
        this.aliases = aliases;
    }

    public static Optional<LogColumn> fromHeader(String header) {
        if (header == null) {
            return Optional.empty();
        }
        String key = header.replace("﻿", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return Optional.ofNullable(BY_ALIAS.get(key));
    }
}
