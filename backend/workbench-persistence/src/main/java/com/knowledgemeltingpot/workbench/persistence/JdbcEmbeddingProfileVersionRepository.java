package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.EmbeddingProfileVersionRepository;
import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEmbeddingProfileVersionRepository implements EmbeddingProfileVersionRepository {

    private static final String PROFILE_COLUMNS = """
            SELECT id, provider, model_id, dimension, profile_version, normalization,
                   distance_function, active, created_at
            FROM embedding_profile_version
            """;

    private final JdbcClient jdbc;

    public JdbcEmbeddingProfileVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public EmbeddingProfileVersion insert(EmbeddingProfileVersion profile) {
        jdbc.sql("""
                INSERT INTO embedding_profile_version (
                    id, provider, model_id, dimension, profile_version, normalization,
                    distance_function, active, created_at)
                VALUES (
                    :id, :provider, :modelId, :dimension, :profileVersion, :normalization,
                    :distanceFunction, :active, :createdAt)
                """)
                .param("id", profile.id())
                .param("provider", profile.provider())
                .param("modelId", profile.modelId())
                .param("dimension", profile.dimension())
                .param("profileVersion", profile.profileVersion())
                .param("normalization", profile.normalization())
                .param("distanceFunction", profile.distanceFunction())
                .param("active", profile.active())
                .param("createdAt", JdbcTimes.toJdbc(profile.createdAt()))
                .update();
        return profile;
    }

    @Override
    public Optional<EmbeddingProfileVersion> findActive() {
        return jdbc.sql(PROFILE_COLUMNS + " WHERE active = TRUE ORDER BY created_at DESC LIMIT 1")
                .query(JdbcEmbeddingProfileVersionRepository::mapProfile)
                .optional();
    }

    private static EmbeddingProfileVersion mapProfile(ResultSet resultSet, int rowNumber) throws SQLException {
        return new EmbeddingProfileVersion(
                resultSet.getObject("id", UUID.class),
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
