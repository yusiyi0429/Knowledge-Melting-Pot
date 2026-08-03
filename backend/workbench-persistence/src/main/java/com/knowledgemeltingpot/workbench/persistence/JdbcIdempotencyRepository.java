package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.IdempotencyRecord;
import com.knowledgemeltingpot.workbench.application.port.IdempotencyRepository;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyRepository implements IdempotencyRepository {
    private final JdbcClient jdbc;

    public JdbcIdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IdempotencyRecord> find(String scope, String key) {
        return jdbc.sql("""
                SELECT scope, idempotency_key, request_hash, resource_type, resource_id, created_at, expires_at
                FROM idempotency_record
                WHERE scope = :scope AND idempotency_key = :key AND expires_at > CURRENT_TIMESTAMP
                """)
                .param("scope", scope)
                .param("key", key)
                .query((resultSet, rowNumber) -> new IdempotencyRecord(
                        resultSet.getString("scope"),
                        resultSet.getString("idempotency_key"),
                        resultSet.getString("request_hash"),
                        resultSet.getString("resource_type"),
                        resultSet.getObject("resource_id", java.util.UUID.class),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    @Override
    public boolean tryReserve(IdempotencyRecord record) {
        return jdbc.sql("""
                INSERT INTO idempotency_record (
                    scope, idempotency_key, request_hash, resource_type, resource_id, created_at, expires_at)
                VALUES (:scope, :key, :requestHash, :resourceType, :resourceId, :createdAt, :expiresAt)
                ON CONFLICT (scope, idempotency_key) DO NOTHING
                """)
                .param("scope", record.scope())
                .param("key", record.key())
                .param("requestHash", record.requestHash())
                .param("resourceType", record.resourceType())
                .param("resourceId", record.resourceId())
                .param("createdAt", JdbcTimes.toJdbc(record.createdAt()))
                .param("expiresAt", JdbcTimes.toJdbc(record.expiresAt()))
                .update() == 1;
    }
}
