package com.knowledgemeltingpot.workbench.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.ExtractionRunRepository;
import com.knowledgemeltingpot.workbench.application.port.FrozenExtractionChunk;
import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import com.knowledgemeltingpot.workbench.domain.ExtractionRun;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcExtractionRunRepository implements ExtractionRunRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcExtractionRunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insert(ExtractionRun run, List<FrozenExtractionChunk> chunks) {
        jdbc.update("""
                INSERT INTO extraction_run (
                    id, job_id, document_id, sub_scene_id, round_id, base_revision_id, base_etag,
                    model_config_version_id, skill_version_id, role_config_version_id,
                    generation_parameters, canonical_input_hash, schema_version, stage,
                    created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, '{}'::jsonb, ?, ?, ?, ?, ?, ?)
                """, run.id(), run.jobId(), run.documentId(), run.subSceneId(), run.roundId(),
                run.baseRevisionId(), run.baseEtag(), run.modelConfigVersionId(), run.skillVersionId(),
                run.canonicalInputHash(), KnowledgeIr.SCHEMA_VERSION, run.stage().name(), run.createdBy(),
                JdbcTimes.toJdbc(run.createdAt()), JdbcTimes.toJdbc(run.updatedAt()));
        for (FrozenExtractionChunk frozen : chunks) {
            jdbc.update("""
                    INSERT INTO extraction_run_material (
                        run_id, material_id, verified_sha256, partition)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (run_id, material_id) DO NOTHING
                    """, run.id(), frozen.materialId(), frozen.materialSha256(), frozen.partition().name());
            MaterialChunk chunk = frozen.chunk();
            jdbc.update("""
                    INSERT INTO extraction_run_chunk (
                        run_id, chunk_id, material_id, source_ref_code, content_hash, ordinal)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, run.id(), chunk.id(), frozen.materialId(), chunk.sourceRefCode(),
                    chunk.contentHash(), chunk.ordinal());
        }
    }

    @Override
    public Optional<ExtractionRun> findByJobId(UUID jobId) {
        return jdbc.query("""
                SELECT id, job_id, document_id, sub_scene_id, round_id, base_revision_id, base_etag,
                       model_config_version_id, skill_version_id, canonical_input_hash, stage,
                       created_by, created_at, updated_at
                FROM extraction_run WHERE job_id = ?
                """, JdbcExtractionRunRepository::mapRun, jobId).stream().findFirst();
    }

    @Override
    public List<FrozenExtractionChunk> findChunks(UUID runId) {
        return jdbc.query("""
                SELECT erc.material_id, erm.verified_sha256, erm.partition,
                       mc.id, mc.blob_id, mc.ordinal, mc.source_ref_code, mc.locator,
                       mc.content, mc.content_hash, mc.char_count, mc.parser_version, mc.created_at
                FROM extraction_run_chunk erc
                JOIN extraction_run_material erm
                  ON erm.run_id = erc.run_id AND erm.material_id = erc.material_id
                JOIN material_chunk mc ON mc.id = erc.chunk_id
                WHERE erc.run_id = ?
                ORDER BY erc.ordinal, erc.source_ref_code
                """, (resultSet, rowNumber) -> {
                    UUID materialId = resultSet.getObject("material_id", UUID.class);
                    String materialSha = resultSet.getString("verified_sha256").trim();
                    MaterialChunk chunk = mapChunk(resultSet);
                    ChunkLocator locator = chunk.locator();
                    KnowledgeIr.SourceRef ref = new KnowledgeIr.SourceRef(
                            chunk.sourceRefCode(), materialId, materialSha, chunk.id(), locator.type().name(),
                            locator.page(), locator.paragraph(), locator.table(), locator.sheet(), locator.rowStart(),
                            locator.rowEnd(), locator.colStart(), locator.colEnd(), locator.lineStart(),
                            locator.lineEnd(), chunk.contentHash());
                    return new FrozenExtractionChunk(materialId, materialSha,
                            MaterialPartition.valueOf(resultSet.getString("partition")), chunk, ref);
                }, runId);
    }

    @Override
    public Optional<String> findMapResult(UUID runId, UUID chunkId) {
        return jdbc.query("""
                SELECT result::text FROM extraction_map_result WHERE run_id = ? AND chunk_id = ?
                """, (resultSet, rowNumber) -> resultSet.getString(1), runId, chunkId).stream().findFirst();
    }

    @Override
    public void insertMapResult(UUID runId, UUID chunkId, String resultJson, String resultHash, Instant createdAt) {
        jdbc.update("""
                INSERT INTO extraction_map_result (run_id, chunk_id, result, result_hash, created_at)
                VALUES (?, ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT (run_id, chunk_id) DO NOTHING
                """, runId, chunkId, resultJson, resultHash, JdbcTimes.toJdbc(createdAt));
    }

    @Override
    public void insertReduceResult(UUID runId, KnowledgeIr ir, String irHash, Instant createdAt) {
        jdbc.update("""
                INSERT INTO extraction_reduce_result (run_id, knowledge_ir, ir_hash, created_at)
                VALUES (?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT (run_id) DO NOTHING
                """, runId, toJson(ir), irHash, JdbcTimes.toJdbc(createdAt));
    }

    @Override
    public void updateStage(UUID runId, ExtractionRun.Stage stage, Instant updatedAt) {
        jdbc.update("UPDATE extraction_run SET stage = ?, updated_at = ? WHERE id = ?",
                stage.name(), JdbcTimes.toJdbc(updatedAt), runId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("extraction result could not be serialized", exception);
        }
    }

    private static ExtractionRun mapRun(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ExtractionRun(resultSet.getObject("id", UUID.class), resultSet.getObject("job_id", UUID.class),
                resultSet.getObject("document_id", UUID.class), resultSet.getObject("sub_scene_id", UUID.class),
                resultSet.getObject("round_id", UUID.class), resultSet.getObject("base_revision_id", UUID.class),
                resultSet.getString("base_etag"), resultSet.getObject("model_config_version_id", UUID.class),
                resultSet.getObject("skill_version_id", UUID.class), resultSet.getString("canonical_input_hash").trim(),
                ExtractionRun.Stage.valueOf(resultSet.getString("stage")),
                resultSet.getObject("created_by", UUID.class), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static MaterialChunk mapChunk(ResultSet resultSet) throws SQLException {
        try {
            return new MaterialChunk(resultSet.getObject("id", UUID.class),
                    resultSet.getObject("blob_id", UUID.class), resultSet.getInt("ordinal"),
                    resultSet.getString("source_ref_code"), ChunkLocators.deserialize(resultSet.getString("locator")),
                    resultSet.getString("content"), resultSet.getString("content_hash").trim(),
                    resultSet.getInt("char_count"), resultSet.getString("parser_version"),
                    resultSet.getTimestamp("created_at").toInstant());
        } catch (com.fasterxml.jackson.core.JacksonException exception) {
            throw new SQLException("invalid chunk locator JSON", exception);
        }
    }
}
