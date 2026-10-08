package com.techcoder.sqlperf.config;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Portable HQL functions: {@code spt_lower(x)} (lower() without Hibernate's argument type check, so
 * case-insensitive search works on CLOB columns and with the SQLite community dialect) and {@code spt_date(ts)}.
 * Registered via META-INF/services.
 */
public class SptFunctionContributor implements FunctionContributor {

    public static final String LOWER = "spt_lower";
    /** Calendar day of a timestamp as 'yyyy-MM-dd' text (portable; the SQLite dialect's day() is off by one). */
    public static final String DATE = "spt_date";

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        var string = contributions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.STRING);
        contributions.getFunctionRegistry().registerPattern(LOWER, "lower(?1)", string);
        boolean sqlite = contributions.getDialect().getClass().getSimpleName().startsWith("SQLite");
        // SQLite stores timestamps as 'yyyy-MM-dd HH:mm:ss.SSS' text
        contributions.getFunctionRegistry().registerPattern(DATE,
                sqlite ? "substr(?1, 1, 10)" : "to_char(?1, 'YYYY-MM-DD')", string);
    }
}
