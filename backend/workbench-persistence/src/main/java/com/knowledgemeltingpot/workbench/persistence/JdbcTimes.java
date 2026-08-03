package com.knowledgemeltingpot.workbench.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class JdbcTimes {
    private JdbcTimes() {
    }

    static OffsetDateTime toJdbc(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
