package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.ReleaseItemSnapshot;
import com.knowledgemeltingpot.workbench.application.port.ReleaseRepository;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.Release;
import com.knowledgemeltingpot.workbench.domain.ReleaseCoverage;
import com.knowledgemeltingpot.workbench.domain.ReleaseItemDisposition;
import com.knowledgemeltingpot.workbench.domain.ReleaseStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReleaseRepository implements ReleaseRepository {
    private final JdbcClient jdbc;

    public JdbcReleaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockScene(UUID sceneId) {
        jdbc.sql("SELECT id FROM scene WHERE id = :sceneId FOR UPDATE")
                .param("sceneId", sceneId)
                .query(UUID.class)
                .single();
    }

    @Override
    public boolean isFinalizedDocumentRevision(UUID revisionId, UUID subSceneId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT r.finalized
                FROM document_revision r
                JOIN knowledge_document d ON d.id = r.document_id
                WHERE r.id = :revisionId AND d.sub_scene_id = :subSceneId
                """)
                .param("revisionId", revisionId)
                .param("subSceneId", subSceneId)
                .query(Boolean.class)
                .optional()
                .orElse(false));
    }

    @Override
    public Release savePublished(Release release, List<ReleaseItemSnapshot> items) {
        jdbc.sql("""
                INSERT INTO release_snapshot (
                    id, scene_id, version, status, partial, manifest, manifest_hash,
                    coverage, note, previous_release_id, created_by, created_at, published_at)
                VALUES (
                    :id, :sceneId, :version, :status, :partial, CAST(:manifest AS jsonb), :manifestHash,
                    :coverage, :note, :previousReleaseId, :createdBy, :createdAt, :publishedAt)
                """)
                .param("id", release.id())
                .param("sceneId", release.sceneId())
                .param("version", release.tag())
                .param("status", release.status().name())
                .param("partial", release.partial())
                .param("manifest", release.manifestJson())
                .param("manifestHash", release.manifestHash())
                .param("coverage", release.coverage().name())
                .param("note", release.note())
                .param("previousReleaseId", release.previousReleaseId())
                .param("createdBy", release.createdBy())
                .param("createdAt", JdbcTimes.toJdbc(release.createdAt()))
                .param("publishedAt", JdbcTimes.toJdbc(release.publishedAt()))
                .update();
        for (ReleaseItemSnapshot item : items) {
            jdbc.sql("""
                    INSERT INTO release_item (
                        release_id, asset_id, sub_scene_id, asset_type, asset_version,
                        document_revision_id, object_key, checksum, disposition, source_release_id)
                    VALUES (
                        :releaseId, :assetId, :subSceneId, :assetType, :assetVersion,
                        :documentRevisionId, :objectKey, :checksum, :disposition, :sourceReleaseId)
                    """)
                    .param("releaseId", release.id())
                    .param("assetId", item.assetId())
                    .param("subSceneId", item.subSceneId())
                    .param("assetType", item.assetType().name())
                    .param("assetVersion", item.assetVersion())
                    .param("documentRevisionId", item.documentRevisionId())
                    .param("objectKey", item.objectKey())
                    .param("checksum", item.checksum())
                    .param("disposition", item.disposition().name())
                    .param("sourceReleaseId", item.sourceReleaseId())
                    .update();
        }
        return release;
    }

    @Override
    public Optional<Release> find(UUID releaseId) {
        return jdbc.sql("SELECT * FROM release_snapshot WHERE id = :id")
                .param("id", releaseId)
                .query(JdbcReleaseRepository::mapRelease)
                .optional();
    }

    @Override
    public Optional<Release> findLatestPublished(UUID sceneId) {
        return jdbc.sql("""
                SELECT * FROM release_snapshot
                WHERE scene_id = :sceneId AND status = 'PUBLISHED'
                ORDER BY published_at DESC, id DESC LIMIT 1
                """)
                .param("sceneId", sceneId)
                .query(JdbcReleaseRepository::mapRelease)
                .optional();
    }

    @Override
    public List<ReleaseItemSnapshot> findItems(UUID releaseId) {
        return jdbc.sql("""
                SELECT asset_id, sub_scene_id, asset_type, asset_version, document_revision_id,
                       object_key, checksum, disposition, source_release_id
                FROM release_item WHERE release_id = :releaseId
                ORDER BY sub_scene_id, asset_type
                """)
                .param("releaseId", releaseId)
                .query((resultSet, rowNumber) -> new ReleaseItemSnapshot(
                        resultSet.getObject("asset_id", UUID.class),
                        resultSet.getObject("sub_scene_id", UUID.class),
                        AssetType.valueOf(resultSet.getString("asset_type")),
                        resultSet.getInt("asset_version"),
                        resultSet.getObject("document_revision_id", UUID.class),
                        resultSet.getString("object_key"),
                        resultSet.getString("checksum"),
                        ReleaseItemDisposition.valueOf(resultSet.getString("disposition")),
                        resultSet.getObject("source_release_id", UUID.class)))
                .list();
    }

    private static Release mapRelease(ResultSet resultSet, int rowNumber) throws SQLException {
        var publishedTimestamp = resultSet.getTimestamp("published_at");
        return new Release(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("scene_id", UUID.class),
                resultSet.getString("version"),
                ReleaseStatus.valueOf(resultSet.getString("status")),
                ReleaseCoverage.valueOf(resultSet.getString("coverage")),
                resultSet.getString("note"),
                resultSet.getObject("previous_release_id", UUID.class),
                resultSet.getString("manifest"),
                resultSet.getString("manifest_hash"),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getTimestamp("created_at").toInstant(),
                publishedTimestamp == null ? null : publishedTimestamp.toInstant());
    }
}
