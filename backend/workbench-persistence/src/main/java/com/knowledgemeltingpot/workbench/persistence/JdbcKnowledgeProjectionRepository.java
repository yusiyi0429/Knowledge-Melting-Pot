package com.knowledgemeltingpot.workbench.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeProjectionRepository;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKnowledgeProjectionRepository implements KnowledgeProjectionRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcKnowledgeProjectionRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insert(UUID revisionId, KnowledgeIr ir, String irHash, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO document_revision_projection (
                    revision_id, schema_version, knowledge_ir, ir_hash, created_at)
                VALUES (:revisionId, :schemaVersion, CAST(:knowledgeIr AS jsonb), :irHash, :createdAt)
                """)
                .param("revisionId", revisionId)
                .param("schemaVersion", ir.schemaVersion())
                .param("knowledgeIr", toJson(ir))
                .param("irHash", irHash)
                .param("createdAt", JdbcTimes.toJdbc(createdAt))
                .update();
        for (KnowledgeIr.SourceRef ref : ir.sourceRefs()) {
            jdbc.sql("""
                    INSERT INTO document_revision_source_ref (
                        revision_id, source_ref_code, material_id, material_sha256,
                        chunk_id, locator, excerpt_hash)
                    VALUES (
                        :revisionId, :code, :materialId, :materialSha256,
                        :chunkId, CAST(:locator AS jsonb), :excerptHash)
                    """)
                    .param("revisionId", revisionId)
                    .param("code", ref.code())
                    .param("materialId", ref.materialId())
                    .param("materialSha256", ref.materialSha256())
                    .param("chunkId", ref.chunkId())
                    .param("locator", toJson(new Locator(
                            ref.locatorType(), ref.page(), ref.paragraph(), ref.table(), ref.sheet(),
                            ref.rowStart(), ref.rowEnd(), ref.colStart(), ref.colEnd(),
                            ref.lineStart(), ref.lineEnd())))
                    .param("excerptHash", ref.excerptHash())
                    .update();
        }
    }

    @Override
    public Optional<KnowledgeIr> find(UUID revisionId) {
        return jdbc.sql("""
                SELECT knowledge_ir::text FROM document_revision_projection WHERE revision_id = :revisionId
                """)
                .param("revisionId", revisionId)
                .query(String.class)
                .optional()
                .map(this::fromJson);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("KnowledgeIR persistence JSON could not be serialized", exception);
        }
    }

    private KnowledgeIr fromJson(String value) {
        try {
            return objectMapper.readValue(value, KnowledgeIr.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted KnowledgeIR is invalid", exception);
        }
    }

    private record Locator(
            String locatorType, Integer page, Integer paragraph, Integer table, String sheet,
            Integer rowStart, Integer rowEnd, Integer colStart, Integer colEnd,
            Integer lineStart, Integer lineEnd) {
    }
}
