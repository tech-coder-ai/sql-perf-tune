package com.techcoder.sqlperf.common;

/** Engine that executed the logged SQL. Impala is the first supported use case. */
public enum SqlEngine {
    IMPALA, HIVE, ORACLE, SPARK, OTHER
}
