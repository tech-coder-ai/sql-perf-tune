package com.techcoder.sqlperf.ingestion;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The same 2000-row import against a real Oracle (pooled ids, batched inserts). Runs only when
 * {@code SPT_TEST_ORACLE_URL} is set, e.g. with the container from docs/developer-guide.md:
 * {@code SPT_TEST_ORACLE_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1 SPT_TEST_ORACLE_USER=SPT_APP
 * SPT_TEST_ORACLE_PASSWORD=... mvn test -Dtest=ImportRoundTripOracleIT}
 */
@ActiveProfiles("oracle")
@EnabledIfEnvironmentVariable(named = "SPT_TEST_ORACLE_URL", matches = ".+")
class ImportRoundTripOracleIT extends ImportRoundTripTest {

    @DynamicPropertySource
    static void oracle(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("SPT_TEST_ORACLE_URL"));
        r.add("spring.datasource.username", () -> System.getenv("SPT_TEST_ORACLE_USER"));
        r.add("spring.datasource.password", () -> System.getenv("SPT_TEST_ORACLE_PASSWORD"));
    }

    @Override
    long maxStatements() {
        return 200;
    }
}
