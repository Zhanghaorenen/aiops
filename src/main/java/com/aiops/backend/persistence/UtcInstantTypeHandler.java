package com.aiops.backend.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/**
 * Stores UTC wall-clock values in DATETIME(6), without using the JVM or session time zone.
 * JDBC 4.2 LocalDateTime binding also supports H2 TIMESTAMP in MySQL compatibility mode.
 */
@MappedTypes(Instant.class)
@MappedJdbcTypes(value = JdbcType.TIMESTAMP, includeNullJdbcType = true)
public final class UtcInstantTypeHandler extends BaseTypeHandler<Instant> {
    /** For >= and < bounds, round upward so sub-microsecond requests retain their meaning. */
    public static Instant ceilingToMicros(Instant value) {
        Instant floor = value.truncatedTo(ChronoUnit.MICROS);
        return floor.equals(value) ? floor : floor.plus(1, ChronoUnit.MICROS);
    }

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Instant value,
            JdbcType jdbcType) throws SQLException {
        statement.setObject(index,
                LocalDateTime.ofInstant(value.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC),
                Types.TIMESTAMP);
    }

    @Override
    public Instant getNullableResult(ResultSet results, String column) throws SQLException {
        return toInstant(results.getObject(column, LocalDateTime.class));
    }

    @Override
    public Instant getNullableResult(ResultSet results, int column) throws SQLException {
        return toInstant(results.getObject(column, LocalDateTime.class));
    }

    @Override
    public Instant getNullableResult(CallableStatement statement, int column) throws SQLException {
        return toInstant(statement.getObject(column, LocalDateTime.class));
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
