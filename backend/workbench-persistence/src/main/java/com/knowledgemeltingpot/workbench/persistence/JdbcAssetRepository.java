package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.port.AssetRepository;
import com.knowledgemeltingpot.workbench.domain.Asset;
import com.knowledgemeltingpot.workbench.domain.AssetStatus;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAssetRepository implements AssetRepository {
    private final JdbcClient jdbc;

    public JdbcAssetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void ensurePlaceholders(UUID subSceneId, Instant now) {
        for (AssetType type : AssetType.values()) {
            jdbc.sql("""
                    INSERT INTO asset (
                        id, sub_scene_id, asset_type, version, status, object_key, checksum,
                        failure_reason, created_at, updated_at)
                    VALUES (:id, :subSceneId, :assetType, 1, 'PENDING', '', '', '', :now, :now)
                    ON CONFLICT (sub_scene_id, asset_type, version) DO NOTHING
                    """)
                    .param("id", UUID.randomUUID())
                    .param("subSceneId", subSceneId)
                    .param("assetType", type.name())
                    .param("now", JdbcTimes.toJdbc(now))
                    .update();
        }
    }

    @Override
    public List<Asset> findLatestBySubScene(UUID subSceneId) {
        return jdbc.sql("""
                SELECT DISTINCT ON (asset_type) *
                FROM asset WHERE sub_scene_id = :subSceneId
                ORDER BY asset_type, version DESC
                """)
                .param("subSceneId", subSceneId)
                .query(JdbcAssetRepository::mapAsset)
                .list();
    }

    @Override
    public List<Asset> findLatestByScene(UUID sceneId) {
        return jdbc.sql("""
                SELECT DISTINCT ON (a.sub_scene_id, a.asset_type) a.*
                FROM asset a
                JOIN sub_scene s ON s.id = a.sub_scene_id
                WHERE s.scene_id = :sceneId
                ORDER BY a.sub_scene_id, a.asset_type, a.version DESC
                """)
                .param("sceneId", sceneId)
                .query(JdbcAssetRepository::mapAsset)
                .list();
    }

    @Override
    public List<Asset> findLatestReadyByScene(UUID sceneId) {
        return jdbc.sql("""
                SELECT DISTINCT ON (a.sub_scene_id, a.asset_type) a.*
                FROM asset a
                JOIN sub_scene s ON s.id = a.sub_scene_id
                WHERE s.scene_id = :sceneId AND a.status = 'READY'
                ORDER BY a.sub_scene_id, a.asset_type, a.version DESC
                """)
                .param("sceneId", sceneId)
                .query(JdbcAssetRepository::mapAsset)
                .list();
    }

    @Override
    public Asset saveNextVersion(Asset asset) {
        insert(asset);
        return asset;
    }

    @Override
    public Asset beginGeneration(UUID subSceneId, AssetType type, UUID documentRevisionId, Instant now) {
        jdbc.sql("SELECT id FROM sub_scene WHERE id = :id FOR UPDATE")
                .param("id", subSceneId)
                .query(UUID.class)
                .single();
        Integer currentVersion = jdbc.sql("""
                SELECT COALESCE(MAX(version), 0) FROM asset
                WHERE sub_scene_id = :subSceneId AND asset_type = :assetType
                """)
                .param("subSceneId", subSceneId)
                .param("assetType", type.name())
                .query(Integer.class)
                .single();
        Asset asset = new Asset(UUID.randomUUID(), subSceneId, type, currentVersion + 1,
                AssetStatus.GENERATING, documentRevisionId, "", "", "", now, now);
        insert(asset);
        return asset;
    }

    @Override
    public Asset markReady(UUID assetId, String objectKey, String checksum, Instant now) {
        int updated = jdbc.sql("""
                UPDATE asset SET status = 'READY', object_key = :objectKey, checksum = :checksum,
                    failure_reason = '', updated_at = :now
                WHERE id = :id AND status = 'GENERATING'
                """)
                .param("id", assetId)
                .param("objectKey", objectKey)
                .param("checksum", checksum)
                .param("now", JdbcTimes.toJdbc(now))
                .update();
        if (updated != 1) {
            throw new ConflictException("asset cannot transition to READY");
        }
        return find(assetId);
    }

    @Override
    public Asset markFailed(UUID assetId, String failureReason, Instant now) {
        int updated = jdbc.sql("""
                UPDATE asset SET status = 'FAILED', failure_reason = :failureReason, updated_at = :now
                WHERE id = :id AND status IN ('PENDING', 'GENERATING')
                """)
                .param("id", assetId)
                .param("failureReason", failureReason == null ? "" : failureReason)
                .param("now", JdbcTimes.toJdbc(now))
                .update();
        if (updated != 1) {
            throw new ConflictException("asset cannot transition to FAILED");
        }
        return find(assetId);
    }

    private void insert(Asset asset) {
        jdbc.sql("""
                INSERT INTO asset (
                    id, sub_scene_id, asset_type, version, status, document_revision_id,
                    object_key, checksum, failure_reason, created_at, updated_at)
                VALUES (
                    :id, :subSceneId, :assetType, :version, :status, :documentRevisionId,
                    :objectKey, :checksum, :failureReason, :createdAt, :updatedAt)
                """)
                .param("id", asset.id())
                .param("subSceneId", asset.subSceneId())
                .param("assetType", asset.type().name())
                .param("version", asset.version())
                .param("status", asset.status().name())
                .param("documentRevisionId", asset.documentRevisionId())
                .param("objectKey", asset.objectKey())
                .param("checksum", asset.checksum())
                .param("failureReason", asset.failureReason())
                .param("createdAt", JdbcTimes.toJdbc(asset.createdAt()))
                .param("updatedAt", JdbcTimes.toJdbc(asset.updatedAt()))
                .update();
    }

    private Asset find(UUID assetId) {
        return jdbc.sql("SELECT * FROM asset WHERE id = :id")
                .param("id", assetId)
                .query(JdbcAssetRepository::mapAsset)
                .single();
    }

    private static Asset mapAsset(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Asset(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("sub_scene_id", UUID.class),
                AssetType.valueOf(resultSet.getString("asset_type")),
                resultSet.getInt("version"),
                AssetStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("document_revision_id", UUID.class),
                resultSet.getString("object_key"),
                resultSet.getString("checksum"),
                resultSet.getString("failure_reason"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
