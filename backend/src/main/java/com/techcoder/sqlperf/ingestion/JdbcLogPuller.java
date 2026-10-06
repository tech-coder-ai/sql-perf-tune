package com.techcoder.sqlperf.ingestion;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.config.SptProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Pulls log rows from an Oracle / Impala / generic JDBC source using the connection's {@code LOG_QUERY}.
 * The query must alias its columns to the standard names (SEQ_ID, EXECUTED_QUERY, ...). If it contains
 * {@code :since} it is bound to the connection's last watermark (max START_TIME already loaded).
 *
 * <p>Drivers: Oracle (ojdbc11) ships with the service. For Impala add the Cloudera Impala JDBC jar
 * (com.cloudera.impala.jdbc.Driver) to the classpath, e.g. {@code -Dloader.path=/opt/drivers}.
 */
@Component
public class JdbcLogPuller {

    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    private final Environment env;
    private final SptProperties props;

    public JdbcLogPuller(Environment env, SptProperties props) {
        this.env = env;
        this.props = props;
    }

    /** Returns the max START_TIME seen (new watermark), or the old one if no rows came back. */
    public LocalDateTime pull(SourceConnection src, LogRowSink sink) throws SQLException {
        String sql = src.getLogQuery();
        boolean incremental = sql.contains(":since");
        if (incremental) {
            sql = sql.replace(":since", "?");
        }
        LocalDateTime watermark = src.getLastWatermark();
        try (Connection con = open(src); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setFetchSize(Math.max(1, src.getFetchSize()));
            ps.setQueryTimeout(props.ingestion().jdbcQueryTimeoutSeconds());
            if (incremental) {
                ps.setTimestamp(1, Timestamp.valueOf(watermark == null ? EPOCH : watermark));
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Optional<LogColumn>> cols = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    cols.add(LogColumn.fromHeader(md.getColumnLabel(i)));
                }
                long row = 0;
                while (rs.next()) {
                    row++;
                    RawLogRecord rec = new RawLogRecord(row);
                    for (int i = 0; i < cols.size(); i++) {
                        if (cols.get(i).isEmpty()) {
                            continue;
                        }
                        LogColumn c = cols.get(i).get();
                        int type = md.getColumnType(i + 1);
                        if (type == Types.TIMESTAMP || type == Types.DATE || type == Types.TIMESTAMP_WITH_TIMEZONE) {
                            Timestamp ts = rs.getTimestamp(i + 1);
                            rec.putDate(c, ts == null ? null : ts.toLocalDateTime());
                        } else {
                            rec.put(c, rs.getString(i + 1));
                        }
                    }
                    try {
                        LocalDateTime start = rec.toQueryLog().getStartTime();
                        if (start != null && (watermark == null || start.isAfter(watermark))) {
                            watermark = start;
                        }
                    } catch (IllegalArgumentException ignored) {
                        // reported by the sink
                    }
                    sink.accept(rec);
                }
            }
        }
        return watermark;
    }

    /** Opens a connection and runs a trivial validation; used by the "Test connection" button. */
    public void test(SourceConnection src) throws SQLException {
        try (Connection con = open(src)) {
            if (!con.isValid(10)) {
                throw new SQLException("Connection is not valid");
            }
        }
    }

    private Connection open(SourceConnection src) throws SQLException {
        Properties p = new Properties();
        if (!Texts.isBlank(src.getUsername())) {
            p.setProperty("user", src.getUsername());
        }
        if (!Texts.isBlank(src.getPasswordRef())) {
            String pwd = env.getProperty(src.getPasswordRef());
            if (pwd == null) {
                throw new SQLException("Password reference '" + src.getPasswordRef()
                        + "' is not defined (set it as an environment variable or property)");
            }
            p.setProperty("password", pwd);
        }
        if (!Texts.isBlank(src.getDriverClass())) {
            try {
                Driver d = (Driver) Class.forName(src.getDriverClass()).getDeclaredConstructor().newInstance();
                Connection c = d.connect(src.getJdbcUrl(), p);
                if (c == null) {
                    throw new SQLException("Driver " + src.getDriverClass() + " does not accept URL " + src.getJdbcUrl());
                }
                return c;
            } catch (ReflectiveOperationException e) {
                throw new SQLException("JDBC driver not on classpath: " + src.getDriverClass(), e);
            }
        }
        return java.sql.DriverManager.getConnection(src.getJdbcUrl(), p);
    }
}
