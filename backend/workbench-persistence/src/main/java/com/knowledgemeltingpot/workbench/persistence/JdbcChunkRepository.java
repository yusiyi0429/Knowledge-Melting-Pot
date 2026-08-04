package com.knowledgemeltingpot.workbench.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.ChunkRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialSourceRef;
import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcChunkRepository implements ChunkRepository {

    private static final String CHUNK_COLUMNS = """
            SELECT id, blob_id, ordinal, source_ref_code, locator, content, content_hash,
                   char_count, parser_version, created_at
            FROM material_chunk
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcChunkRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public int commitAll(UUID blobId, String parserVersion, List<MaterialChunk> chunks) {
        for (MaterialChunk chunk : chunks) {
            jdbc.sql("""
                    INSERT INTO material_chunk (
                        id, blob_id, ordinal, source_ref_code, locator, content, content_hash,
                        char_count, parser_version, created_at)
                    VALUES (
                        :id, :blobId, :ordinal, :sourceRefCode, CAST(:locator AS JSONB), :content, :contentHash,
                        :charCount, :parserVersion, :createdAt)
                    ON CONFLICT (blob_id, parser_version, ordinal) DO NOTHING
                    """)
                    .param("id", chunk.id())
                    .param("blobId", blobId)
                    .param("ordinal", chunk.ordinal())
                    .param("sourceRefCode", chunk.sourceRefCode())
                    .param("locator", toJson(chunk.locator()))
                    .param("content", chunk.content())
                    .param("contentHash", chunk.contentHash())
                    .param("charCount", chunk.charCount())
                    .param("parserVersion", parserVersion)
                    .param("createdAt", JdbcTimes.toJdbc(chunk.createdAt()))
                    .update();
        }
        return countFor(blobId, parserVersion);
    }

    @Override
    public boolean existsForBlob(UUID blobId) {
        Boolean present = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM material_chunk WHERE blob_id = :blobId)
                """)
                .param("blobId", blobId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(present);
    }

    @Override
    public List<MaterialChunk> findByBlob(UUID blobId) {
        return jdbc.sql(CHUNK_COLUMNS + " WHERE blob_id = :blobId ORDER BY ordinal")
                .param("blobId", blobId)
                .query(JdbcChunkRepository::mapChunk)
                .list();
    }

    @Override
    public Map<UUID, List<MaterialChunk>> findForMaterials(List<UUID> materialIds) {
        if (materialIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<MaterialChunk>> grouped = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT m.id AS material_id, mc.id, mc.blob_id, mc.ordinal, mc.source_ref_code, mc.locator,
                       mc.content, mc.content_hash, mc.char_count, mc.parser_version, mc.created_at
                FROM material_chunk mc
                JOIN material_blob mb ON mb.id = mc.blob_id
                JOIN material m ON m.blob_id = mb.id
                WHERE m.id IN (:materialIds)
                ORDER BY m.id, mc.ordinal
                """)
                .param("materialIds", materialIds)
                .query((resultSet, rowNumber) -> {
                    UUID materialId = resultSet.getObject("material_id", UUID.class);
                    grouped.computeIfAbsent(materialId, ignored -> new ArrayList<>()).add(mapChunk(resultSet, rowNumber));
                    return null;
                })
                .list();
        return grouped;
    }

    @Override
    public List<MaterialSourceRef> findTrustedSourceRefs(UUID roundId, UUID subSceneId,
            List<String> sourceRefCodes) {
        if (sourceRefCodes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT mc.source_ref_code, m.id AS material_id, m.sha256 AS material_sha256,
                       mc.id AS chunk_id, mc.locator, mc.content_hash AS excerpt_hash,
                       mc.char_count, mc.created_at
                FROM round_material rm
                JOIN material m ON m.id = rm.material_id
                JOIN material_chunk mc ON mc.blob_id = m.blob_id
                WHERE rm.round_id = :roundId
                  AND rm.sub_scene_id = :subSceneId
                  AND rm.active = TRUE
                  AND rm.partition IN ('SOURCE', 'LABELED_TRAIN')
                  AND m.status = 'READY'
                  AND mc.source_ref_code IN (:codes)
                ORDER BY mc.source_ref_code, m.id
                """)
                .param("roundId", roundId)
                .param("subSceneId", subSceneId)
                .param("codes", sourceRefCodes)
                .query((resultSet, rowNumber) -> {
                    ChunkLocator locator;
                    try {
                        locator = ChunkLocators.deserialize(resultSet.getString("locator"));
                    } catch (com.fasterxml.jackson.core.JacksonException exception) {
                        throw new SQLException("invalid chunk locator JSON", exception);
                    }
                    return new MaterialSourceRef(
                            resultSet.getString("source_ref_code"),
                            resultSet.getObject("material_id", UUID.class),
                            resultSet.getString("material_sha256").trim(),
                            resultSet.getObject("chunk_id", UUID.class),
                            locator.type().name(), locator.page(), locator.paragraph(), locator.table(),
                            locator.sheet(), locator.rowStart(), locator.rowEnd(), locator.colStart(),
                            locator.colEnd(), locator.lineStart(), locator.lineEnd(),
                            resultSet.getString("excerpt_hash").trim(), resultSet.getInt("char_count"),
                            resultSet.getTimestamp("created_at").toInstant());
                })
                .list();
    }

    private int countFor(UUID blobId, String parserVersion) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM material_chunk
                WHERE blob_id = :blobId AND parser_version = :parserVersion
                """)
                .param("blobId", blobId)
                .param("parserVersion", parserVersion)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private String toJson(ChunkLocator locator) {
        try {
            return objectMapper.writeValueAsString(locator);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize chunk locator", exception);
        }
    }

    private static MaterialChunk mapChunk(ResultSet resultSet, int rowNumber) throws SQLException {
        String locatorJson = resultSet.getString("locator");
        try {
            return new MaterialChunk(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("blob_id", UUID.class),
                    resultSet.getInt("ordinal"),
                    resultSet.getString("source_ref_code"),
                    ChunkLocators.deserialize(locatorJson),
                    resultSet.getString("content"),
                    resultSet.getString("content_hash").trim(),
                    resultSet.getInt("char_count"),
                    resultSet.getString("parser_version"),
                    resultSet.getTimestamp("created_at").toInstant());
        } catch (com.fasterxml.jackson.core.JacksonException exception) {
            throw new SQLException("invalid chunk locator JSON", exception);
        }
    }
}
