package com.techcoder.sqlperf.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlFingerprinterTest {

    private final SqlFingerprinter fp = new SqlFingerprinter(true, true);

    @Test
    void sameSqlWithDifferentFiltersGroupsTogether() {
        String a = "SELECT a, b FROM db.t WHERE x = 1 AND y = 'abc' GROUP BY a, b";
        String b = "select a,b from db.t where x=99 and z in (1,2,3)   group by a, b;";
        assertThat(fp.fingerprint(a).fingerprint()).isEqualTo(fp.fingerprint(b).fingerprint());
        assertThat(fp.normalize(a)).isEqualTo("select a , b from db . t group by a , b");
    }

    @Test
    void differentSelectListsDoNotGroup() {
        assertThat(fp.fingerprint("select a from t where x = 1").fingerprint())
                .isNotEqualTo(fp.fingerprint("select b from t where x = 1").fingerprint());
    }

    @Test
    void stripsNestedWhereButKeepsOuterClauses() {
        String sql = "WITH r AS (SELECT * FROM l WHERE d > '2026-01-01') "
                + "SELECT r.g, SUM(r.a) FROM r JOIN g ON r.g = g.g WHERE r.a > 5 GROUP BY r.g ORDER BY 2 DESC LIMIT 10";
        assertThat(fp.normalize(sql))
                .isEqualTo("with r as ( select * from l ) select r . g , sum ( r . a ) from r join g on r . g = g . g "
                        + "group by r . g order by ? desc limit ?");
    }

    @Test
    void subqueryInWhereIsRemovedWithItsParent() {
        String sql = "select * from t where id in (select id from u where flag = 'Y') order by id";
        assertThat(fp.normalize(sql)).isEqualTo("select * from t order by id");
    }

    @Test
    void ignoresCommentsCaseAndWhitespace() {
        String a = "-- daily job\nSELECT /* hint */ COUNT(*)\n  FROM   Sales.T";
        String b = "select count(*) from sales.t";
        assertThat(fp.normalize(a)).isEqualTo(fp.normalize(b));
    }

    @Test
    void wordWhereInsideStringLiteralIsNotAClause() {
        assertThat(fp.normalize("select 'where' as w from t")).isEqualTo("select ? as w from t");
    }

    @Test
    void literalsMaskedOutsideWhere() {
        assertThat(fp.normalize("select * from t limit 100")).isEqualTo(fp.normalize("select * from t limit 5"));
        assertThat(fp.normalize("select a - 1 from t")).isEqualTo("select a - ? from t");
    }

    @Test
    void whereKeptWhenStrippingDisabled() {
        SqlFingerprinter keep = new SqlFingerprinter(false, true);
        assertThat(keep.normalize("select a from t where x in (1, 2, 3)")).isEqualTo("select a from t where x in ( ? )");
    }

    @Test
    void cleanseKeepsLiteralsAndFilters() {
        assertThat(fp.cleanse("SELECT  SUM(a) AS s -- c\nFROM t WHERE x = 'A' ;"))
                .isEqualTo("SELECT SUM(a) AS s FROM t WHERE x = 'A'");
        assertThat(fp.cleanse("with r as(select 1)select * from r")).isEqualTo("with r as (select 1) select * from r");
    }
}
