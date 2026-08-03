package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.MaterialRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelectionPort;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.MaterialUploadIntent;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import com.knowledgemeltingpot.workbench.domain.UploadState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMaterialRepository implements MaterialRepository, MaterialSelectionPort {
    private static final String SELECTION_COLUMNS = """
            SELECT m.id AS material_id, m.file_name, m.file_format, m.media_type, m.object_key,
                   m.sha256, m.size_bytes, m.status AS material_status,
                   m.created_at AS material_created_at, m.updated_at AS material_updated_at,
                   rm.id AS binding_id, rm.round_id, rm.sub_scene_id, rm.partition, rm.share_scope,
                   rm.regulatory_source, rm.active, rm.created_at AS binding_created_at
            FROM material m
            JOIN round_material rm ON rm.material_id = m.id
            WHERE rm.round_id = :roundId AND rm.sub_scene_id = :subSceneId
              AND rm.active = TRUE AND m.status = 'READY'
            """;
    static final String KNOWLEDGE_PARTITIONS = " AND rm.partition IN ('SOURCE', 'LABELED_TRAIN')";
    static final String REGULATORY_ONLY = " AND rm.regulatory_source = TRUE";
    static final String HOLDOUT_PARTITION = " AND rm.partition = 'LABELED_HOLDOUT'";

    private final JdbcClient jdbc;

    public JdbcMaterialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Material insert(Material material) {
        jdbc.sql("""
                INSERT INTO material (
                    id, file_name, file_format, media_type, object_key, sha256, size_bytes,
                    status, created_at, updated_at)
                VALUES (
                    :id, :fileName, :fileFormat, :mediaType, :objectKey, :sha256, :sizeBytes,
                    :status, :createdAt, :updatedAt)
                """)
                .param("id", material.id())
                .param("fileName", material.fileName())
                .param("fileFormat", material.format().name())
                .param("mediaType", material.mediaType())
                .param("objectKey", material.objectKey())
                .param("sha256", material.sha256())
                .param("sizeBytes", material.sizeBytes())
                .param("status", material.status().name())
                .param("createdAt", JdbcTimes.toJdbc(material.createdAt()))
                .param("updatedAt", JdbcTimes.toJdbc(material.updatedAt()))
                .update();
        return material;
    }

    @Override
    public Optional<Material> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, file_name, file_format, media_type, object_key, sha256, size_bytes,
                       status, created_at, updated_at
                FROM material WHERE id = :id
                """)
                .param("id", id)
                .query(JdbcMaterialRepository::mapMaterial)
                .optional();
    }

    @Override
    public void insertBindings(List<RoundMaterial> bindings) {
        for (RoundMaterial binding : bindings) {
            jdbc.sql("""
                    INSERT INTO round_material (
                        id, material_id, round_id, sub_scene_id, partition, share_scope,
                        regulatory_source, active, created_at)
                    VALUES (
                        :id, :materialId, :roundId, :subSceneId, :partition, :shareScope,
                        :regulatorySource, :active, :createdAt)
                    """)
                    .param("id", binding.id())
                    .param("materialId", binding.materialId())
                    .param("roundId", binding.roundId())
                    .param("subSceneId", binding.subSceneId())
                    .param("partition", binding.partition().name())
                    .param("shareScope", binding.shareScope().name())
                    .param("regulatorySource", binding.regulatorySource())
                    .param("active", binding.active())
                    .param("createdAt", JdbcTimes.toJdbc(binding.createdAt()))
                    .update();
        }
    }

    @Override
    public List<RoundMaterial> findBindings(UUID materialId) {
        return jdbc.sql("""
                SELECT id, material_id, round_id, sub_scene_id, partition, share_scope,
                       regulatory_source, active, created_at
                FROM round_material WHERE material_id = :materialId
                ORDER BY created_at, id
                """)
                .param("materialId", materialId)
                .query(JdbcMaterialRepository::mapBinding)
                .list();
    }

    @Override
    public MaterialUploadIntent insertIntent(MaterialUploadIntent intent) {
        jdbc.sql("""
                INSERT INTO material_upload_intent (
                    id, material_id, created_by, validation_job_id, client_etag, created_at, completed_at,
                    storage_upload_id, quarantine_object_key, part_size, part_count, expires_at,
                    upload_state, completion_attempt)
                VALUES (
                    :id, :materialId, :createdBy, NULL, :clientEtag, :createdAt, NULL,
                    :storageUploadId, :quarantineObjectKey, :partSize, :partCount, :expiresAt,
                    :uploadState, :completionAttempt)
                """)
                .param("id", intent.id())
                .param("materialId", intent.materialId())
                .param("createdBy", intent.createdBy())
                .param("clientEtag", intent.clientEtag())
                .param("createdAt", JdbcTimes.toJdbc(intent.createdAt()))
                .param("storageUploadId", intent.storageUploadId())
                .param("quarantineObjectKey", intent.quarantineObjectKey())
                .param("partSize", intent.partSize())
                .param("partCount", intent.partCount())
                .param("expiresAt", JdbcTimes.toJdbc(intent.expiresAt()))
                .param("uploadState", intent.uploadState().name())
                .param("completionAttempt", intent.completionAttempt())
                .update();
        return intent;
    }

    @Override
    public Optional<MaterialUploadIntent> findIntent(UUID intentId) {
        return queryIntent(intentId, false);
    }

    @Override
    public Optional<MaterialUploadIntent> lockIntent(UUID intentId) {
        return queryIntent(intentId, true);
    }

    @Override
    public boolean transitionStatus(UUID materialId, MaterialStatus expected, MaterialStatus target,
            Instant updatedAt) {
        return jdbc.sql("""
                UPDATE material SET status = :target, updated_at = :updatedAt
                WHERE id = :id AND status = :expected
                """)
                .param("id", materialId)
                .param("expected", expected.name())
                .param("target", target.name())
                .param("updatedAt", JdbcTimes.toJdbc(updatedAt))
                .update() == 1;
    }

    @Override
    public boolean updateBlobId(UUID materialId, UUID blobId, MaterialStatus expected, MaterialStatus target,
            Instant updatedAt) {
        return jdbc.sql("""
                UPDATE material SET blob_id = :blobId, status = :target, updated_at = :updatedAt
                WHERE id = :id AND status = :expected
                """)
                .param("id", materialId)
                .param("blobId", blobId)
                .param("expected", expected.name())
                .param("target", target.name())
                .param("updatedAt", JdbcTimes.toJdbc(updatedAt))
                .update() == 1;
    }

    @Override
    public boolean completeIntent(UUID intentId, UUID jobId, String clientEtag, Instant completedAt) {
        return jdbc.sql("""
                UPDATE material_upload_intent
                SET validation_job_id = :jobId, client_etag = :clientEtag, completed_at = :completedAt,
                    upload_state = 'COMPLETED'
                WHERE id = :id AND validation_job_id IS NULL
                """)
                .param("id", intentId)
                .param("jobId", jobId)
                .param("clientEtag", clientEtag)
                .param("completedAt", JdbcTimes.toJdbc(completedAt))
                .update() == 1;
    }

    @Override
    public boolean updateIntentState(UUID intentId, UploadState state) {
        return jdbc.sql("""
                UPDATE material_upload_intent SET upload_state = :state WHERE id = :id
                """)
                .param("id", intentId)
                .param("state", state.name())
                .update() == 1;
    }

    @Override
    public boolean incrementCompletionAttempt(UUID intentId) {
        return jdbc.sql("""
                UPDATE material_upload_intent
                SET completion_attempt = completion_attempt + 1
                WHERE id = :id
                """)
                .param("id", intentId)
                .update() == 1;
    }

    @Override
    public boolean abortIntent(UUID intentId, Instant abortedAt) {
        return jdbc.sql("""
                UPDATE material_upload_intent
                SET upload_state = 'ABORTED', completed_at = :abortedAt
                WHERE id = :id AND upload_state IN ('INITIATED', 'UPLOADING', 'COMPLETING')
                """)
                .param("id", intentId)
                .param("abortedAt", JdbcTimes.toJdbc(abortedAt))
                .update() == 1;
    }

    @Override
    public List<MaterialSelection> findForExtraction(UUID roundId, UUID subSceneId) {
        return select(SELECTION_COLUMNS + KNOWLEDGE_PARTITIONS, roundId, subSceneId);
    }

    @Override
    public List<MaterialSelection> findForAlignment(UUID roundId, UUID subSceneId) {
        return select(SELECTION_COLUMNS + KNOWLEDGE_PARTITIONS + REGULATORY_ONLY, roundId, subSceneId);
    }

    @Override
    public List<MaterialSelection> findForQa(UUID roundId, UUID subSceneId) {
        return select(SELECTION_COLUMNS + KNOWLEDGE_PARTITIONS, roundId, subSceneId);
    }

    @Override
    public List<MaterialSelection> findForEvaluation(UUID roundId, UUID subSceneId) {
        return select(SELECTION_COLUMNS + HOLDOUT_PARTITION, roundId, subSceneId);
    }

    private List<MaterialSelection> select(String sql, UUID roundId, UUID subSceneId) {
        return jdbc.sql(sql)
                .param("roundId", roundId)
                .param("subSceneId", subSceneId)
                .query(JdbcMaterialRepository::mapSelection)
                .list();
    }

    private Optional<MaterialUploadIntent> queryIntent(UUID intentId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.sql("""
                SELECT id, material_id, created_by, validation_job_id, client_etag, created_at, completed_at,
                       storage_upload_id, quarantine_object_key, part_size, part_count, expires_at,
                       upload_state, completion_attempt
                FROM material_upload_intent WHERE id = :id
                """ + suffix)
                .param("id", intentId)
                .query(JdbcMaterialRepository::mapIntent)
                .optional();
    }

    private static Material mapMaterial(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Material(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("file_name"),
                MaterialFormat.valueOf(resultSet.getString("file_format")),
                resultSet.getString("media_type"),
                resultSet.getString("object_key"),
                resultSet.getString("sha256").trim(),
                resultSet.getLong("size_bytes"),
                MaterialStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static RoundMaterial mapBinding(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RoundMaterial(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("material_id", UUID.class),
                resultSet.getObject("round_id", UUID.class),
                resultSet.getObject("sub_scene_id", UUID.class),
                MaterialPartition.valueOf(resultSet.getString("partition")),
                MaterialShareScope.valueOf(resultSet.getString("share_scope")),
                resultSet.getBoolean("regulatory_source"),
                resultSet.getBoolean("active"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private static MaterialUploadIntent mapIntent(ResultSet resultSet, int rowNumber) throws SQLException {
        var completedAt = resultSet.getTimestamp("completed_at");
        Long partSize = resultSet.getObject("part_size", Long.class);
        Integer partCount = resultSet.getObject("part_count", Integer.class);
        Timestamp expiresAt = resultSet.getTimestamp("expires_at");
        return new MaterialUploadIntent(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("material_id", UUID.class),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getObject("validation_job_id", UUID.class),
                resultSet.getString("client_etag"),
                resultSet.getTimestamp("created_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant(),
                resultSet.getString("storage_upload_id"),
                resultSet.getString("quarantine_object_key"),
                partSize,
                partCount,
                expiresAt == null ? null : expiresAt.toInstant(),
                UploadState.valueOf(resultSet.getString("upload_state")),
                resultSet.getInt("completion_attempt"));
    }

    private static MaterialSelection mapSelection(ResultSet resultSet, int rowNumber) throws SQLException {
        Material material = new Material(
                resultSet.getObject("material_id", UUID.class),
                resultSet.getString("file_name"),
                MaterialFormat.valueOf(resultSet.getString("file_format")),
                resultSet.getString("media_type"),
                resultSet.getString("object_key"),
                resultSet.getString("sha256").trim(),
                resultSet.getLong("size_bytes"),
                MaterialStatus.valueOf(resultSet.getString("material_status")),
                resultSet.getTimestamp("material_created_at").toInstant(),
                resultSet.getTimestamp("material_updated_at").toInstant());
        RoundMaterial binding = new RoundMaterial(
                resultSet.getObject("binding_id", UUID.class),
                material.id(),
                resultSet.getObject("round_id", UUID.class),
                resultSet.getObject("sub_scene_id", UUID.class),
                MaterialPartition.valueOf(resultSet.getString("partition")),
                MaterialShareScope.valueOf(resultSet.getString("share_scope")),
                resultSet.getBoolean("regulatory_source"),
                resultSet.getBoolean("active"),
                resultSet.getTimestamp("binding_created_at").toInstant());
        return new MaterialSelection(material, binding);
    }
}
