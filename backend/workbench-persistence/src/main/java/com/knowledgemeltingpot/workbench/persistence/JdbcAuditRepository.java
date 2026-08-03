package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.AuditRepository;
import com.knowledgemeltingpot.workbench.domain.AuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditRepository implements AuditRepository {
    private final JdbcClient jdbc;

    public JdbcAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(AuditEvent event) {
        jdbc.sql("""
                INSERT INTO audit_log (
                    id, actor_id, action, target_type, target_id, details, trace_id, occurred_at)
                VALUES (
                    :id, :actorId, :action, :targetType, :targetId, CAST(:details AS jsonb), :traceId, :occurredAt)
                """)
                .param("id", event.id())
                .param("actorId", event.actorId())
                .param("action", event.action())
                .param("targetType", event.targetType())
                .param("targetId", event.targetId())
                .param("details", event.detailsJson())
                .param("traceId", event.traceId())
                .param("occurredAt", JdbcTimes.toJdbc(event.occurredAt()))
                .update();
    }

    @Override
    public List<AuditEvent> findRecent(int limit, int offset) {
        return jdbc.sql("""
                SELECT id, actor_id, action, target_type, target_id, details, trace_id, occurred_at
                FROM audit_log ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset
                """)
                .param("limit", limit)
                .param("offset", offset)
                .query((resultSet, rowNumber) -> new AuditEvent(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("actor_id", UUID.class),
                        resultSet.getString("action"),
                        resultSet.getString("target_type"),
                        resultSet.getObject("target_id", UUID.class),
                        resultSet.getString("details"),
                        resultSet.getString("trace_id"),
                        resultSet.getTimestamp("occurred_at").toInstant()))
                .list();
    }
}
