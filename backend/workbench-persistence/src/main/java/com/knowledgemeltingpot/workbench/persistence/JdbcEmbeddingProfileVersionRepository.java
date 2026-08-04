package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.EmbeddingProfileVersionRepository;
import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcEmbeddingProfileVersionRepository implements EmbeddingProfileVersionRepository {

    private static final String PROFILE_COLUMNS = """
            SELECT ep.id, ep.model_connection_id, ep.provider, ep.model_id, ep.dimension,
                   ep.profile_version, ep.normalization, ep.distance_function,
                   (epa.profile_version_id IS NOT NULL) AS active, ep.created_at
            FROM embedding_profile_version ep
            LEFT JOIN embedding_profile_activation epa ON epa.profile_version_id = ep.id
            """;

    private final JdbcClient jdbc;

    public JdbcEmbeddingProfileVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public EmbeddingProfileVersion insertAndActivate(EmbeddingProfileVersion profile, UUID activatedBy,
            Instant activatedAt) {
        jdbc.sql("""
                INSERT INTO embedding_profile_version (
                    id, model_connection_id, provider, model_id, dimension, profile_version,
                    normalization, distance_function, active, created_at)
                VALUES (
                    :id, :modelConnectionId, :provider, :modelId, :dimension, :profileVersion,
                    :normalization, :distanceFunction, FALSE, :createdAt)
                """)
                .param("id", profile.id())
                .param("modelConnectionId", profile.modelConnectionId())
                .param("provider", profile.provider())
                .param("modelId", profile.modelId())
                .param("dimension", profile.dimension())
                .param("profileVersion", profile.profileVersion())
                .param("normalization", profile.normalization())
                .param("distanceFunction", profile.distanceFunction())
                .param("createdAt", JdbcTimes.toJdbc(profile.createdAt()))
                .update();
        jdbc.sql("""
                INSERT INTO embedding_profile_activation (
                    singleton_key, profile_version_id, activated_by, activated_at)
                VALUES (1, :profileVersionId, :activatedBy, :activatedAt)
                ON CONFLICT (singleton_key) DO UPDATE SET
                    profile_version_id = EXCLUDED.profile_version_id,
                    activated_by = EXCLUDED.activated_by,
                    activated_at = EXCLUDED.activated_at
                """)
                .param("profileVersionId", profile.id())
                .param("activatedBy", activatedBy)
                .param("activatedAt", JdbcTimes.toJdbc(activatedAt))
                .update();
        createDenseIndex(profile);
        return withActive(profile, true);
    }

    @Override
    public List<EmbeddingProfileVersion> findAll() {
        return jdbc.sql(PROFILE_COLUMNS + " ORDER BY ep.created_at DESC, ep.id")
                .query(JdbcEmbeddingProfileVersionRepository::mapProfile)
                .list();
    }

    @Override
    public Optional<EmbeddingProfileVersion> findById(UUID id) {
        return jdbc.sql(PROFILE_COLUMNS + " WHERE ep.id = :id")
                .param("id", id)
                .query(JdbcEmbeddingProfileVersionRepository::mapProfile)
                .optional();
    }

    @Override
    public Optional<EmbeddingProfileVersion> findActive() {
        return jdbc.sql(PROFILE_COLUMNS + " WHERE epa.singleton_key = 1")
                .query(JdbcEmbeddingProfileVersionRepository::mapProfile)
                .optional();
    }

    private void createDenseIndex(EmbeddingProfileVersion profile) {
        int dimension = profile.dimension();
        if (dimension < 1 || dimension > 2_000) {
            throw new IllegalArgumentException("embedding dimension is outside the pgvector HNSW safety bound");
        }
        String operatorClass = switch (profile.distanceFunction()) {
            case "COSINE" -> "vector_cosine_ops";
            case "L2" -> "vector_l2_ops";
            default -> throw new IllegalArgumentException("unsupported embedding distance function");
        };
        String compactId = profile.id().toString().replace("-", "");
        String sql = "CREATE INDEX IF NOT EXISTS ix_ce_hnsw_" + compactId
                + " ON chunk_embedding USING hnsw ((vector::vector(" + dimension + ")) "
                + operatorClass + ") WHERE profile_version_id = '" + profile.id() + "'::uuid";
        jdbc.sql(sql).update();
    }

    private static EmbeddingProfileVersion withActive(EmbeddingProfileVersion profile, boolean active) {
        return new EmbeddingProfileVersion(profile.id(), profile.modelConnectionId(), profile.provider(),
                profile.modelId(), profile.dimension(), profile.profileVersion(), profile.normalization(),
                profile.distanceFunction(), active, profile.createdAt());
    }

    private static EmbeddingProfileVersion mapProfile(ResultSet resultSet, int rowNumber) throws SQLException {
        return new EmbeddingProfileVersion(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("model_connection_id", UUID.class),
                resultSet.getString("provider"),
                resultSet.getString("model_id"),
                resultSet.getInt("dimension"),
                resultSet.getString("profile_version"),
                resultSet.getString("normalization"),
                resultSet.getString("distance_function"),
                resultSet.getBoolean("active"),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
