package com.careslot.scheduling.adapter.persistence;

import com.careslot.scheduling.domain.Interval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** tstzrange columns are read as lower()/upper() pairs and written as tstzrange(?, ?, '[)'). */
final class Pg {

    private Pg() {
    }

    static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, int col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    static Interval interval(ResultSet rs, int lowerCol) throws SQLException {
        return new Interval(instant(rs, lowerCol), instant(rs, lowerCol + 1));
    }
}
