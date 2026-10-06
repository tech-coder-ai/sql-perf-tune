package com.techcoder.sqlperf.config;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Registers {@code spt_lower(x)} -> {@code lower(x)} without Hibernate's argument type check, so
 * case-insensitive search works on CLOB columns (Oracle) and with the SQLite community dialect alike.
 * Registered via META-INF/services.
 */
public class SptFunctionContributor implements FunctionContributor {

    public static final String LOWER = "spt_lower";

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        contributions.getFunctionRegistry().registerPattern(LOWER, "lower(?1)",
                contributions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.STRING));
    }
}
