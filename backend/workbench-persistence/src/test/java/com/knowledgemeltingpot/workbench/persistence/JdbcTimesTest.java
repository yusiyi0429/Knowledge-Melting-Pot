package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JdbcTimesTest {
    @Test
    void convertsInstantToUtcOffsetDateTimeWithoutChangingThePointInTime() {
        Instant instant = Instant.parse("2026-08-03T07:49:58.123456Z");

        var jdbcValue = JdbcTimes.toJdbc(instant);

        assertThat(jdbcValue.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(jdbcValue.toInstant()).isEqualTo(instant);
    }

    @Test
    void preservesNullForNullableDatabaseColumns() {
        assertThat(JdbcTimes.toJdbc(null)).isNull();
    }
}
