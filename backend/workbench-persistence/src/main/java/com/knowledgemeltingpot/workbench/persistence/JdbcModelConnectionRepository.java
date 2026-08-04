package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.domain.CredentialEnvelope;
import com.knowledgemeltingpot.workbench.domain.ModelConfigVersion;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.ModelConnectionValidationStatus;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcModelConnectionRepository implements ModelConnectionRepository {
    private static final String CONNECTION_COLUMNS = """
            id, name, provider, base_url, credential_envelope, enabled, validation_status,
            last_validated_at, created_by, created_at, updated_at
            """;
    private static final String VERSION_COLUMNS = """
            id, model_connection_id, version, model_id, temperature, max_output_tokens, created_by, created_at
            """;

    private final JdbcClient jdbc;

    public JdbcModelConnectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ModelConnection save(ModelConnection connection) {
        jdbc.sql("""
                INSERT INTO model_connection (
                    id, name, provider, base_url, credential_envelope, enabled, validation_status,
                    last_validated_at, created_by, created_at, updated_at
                ) VALUES (
                    :id, :name, :provider, :baseUrl, :credentialEnvelope, :enabled, :validationStatus,
                    :lastValidatedAt, :createdBy, :createdAt, :updatedAt
                )
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    provider = EXCLUDED.provider,
                    base_url = EXCLUDED.base_url,
                    credential_envelope = EXCLUDED.credential_envelope,
                    enabled = EXCLUDED.enabled,
                    validation_status = EXCLUDED.validation_status,
                    last_validated_at = EXCLUDED.last_validated_at,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("id", connection.id())
                .param("name", connection.name())
                .param("provider", connection.provider().name())
                .param("baseUrl", connection.baseUrl().toString())
                .param("credentialEnvelope", connection.credentialEnvelope()
                        .map(CredentialEnvelope::encoded).orElse(null))
                .param("enabled", connection.enabled())
                .param("validationStatus", connection.validationStatus().name())
                .param("lastValidatedAt", JdbcTimes.toJdbc(connection.lastValidatedAt()))
                .param("createdBy", connection.createdBy())
                .param("createdAt", JdbcTimes.toJdbc(connection.createdAt()))
                .param("updatedAt", JdbcTimes.toJdbc(connection.updatedAt()))
                .update();
        return connection;
    }

    @Override
    public Optional<ModelConnection> findConnection(UUID id) {
        return jdbc.sql("SELECT " + CONNECTION_COLUMNS + " FROM model_connection"
                        + " WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query(JdbcModelConnectionRepository::mapConnection)
                .optional();
    }

    @Override
    public List<ModelConnection> findConnections() {
        return jdbc.sql("SELECT " + CONNECTION_COLUMNS + " FROM model_connection"
                        + " WHERE deleted_at IS NULL ORDER BY updated_at DESC, id")
                .query(JdbcModelConnectionRepository::mapConnection)
                .list();
    }

    @Override
    public Optional<ModelConnection> recordConnectionTest(UUID id, Instant expectedUpdatedAt,
            boolean connectivityVerified, Instant testedAt) {
        return jdbc.sql("""
                UPDATE model_connection
                SET validation_status = :validationStatus, last_validated_at = :lastValidatedAt,
                    updated_at = :testedAt
                WHERE id = :id AND updated_at = :expectedUpdatedAt AND deleted_at IS NULL
                RETURNING
                """ + CONNECTION_COLUMNS)
                .param("id", id)
                .param("expectedUpdatedAt", JdbcTimes.toJdbc(expectedUpdatedAt))
                .param("validationStatus", connectivityVerified ? "CONNECTIVITY_VERIFIED" : "UNTESTED")
                .param("lastValidatedAt", connectivityVerified ? JdbcTimes.toJdbc(testedAt) : null)
                .param("testedAt", JdbcTimes.toJdbc(testedAt))
                .query(JdbcModelConnectionRepository::mapConnection)
                .optional();
    }

    @Override
    public boolean softDelete(UUID id, Instant deletedAt) {
        return jdbc.sql("""
                UPDATE model_connection SET deleted_at = :deletedAt, enabled = FALSE, updated_at = :deletedAt
                WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", id)
                .param("deletedAt", JdbcTimes.toJdbc(deletedAt))
                .update() == 1;
    }

    @Override
    public ModelConfigVersion appendConfigVersion(UUID id, UUID modelConnectionId, String modelId,
            BigDecimal temperature, int maxOutputTokens, UUID createdBy, Instant createdAt) {
        return jdbc.sql("""
                WITH next_version AS (
                    UPDATE model_connection
                    SET next_config_version = next_config_version + 1
                    WHERE id = :modelConnectionId AND deleted_at IS NULL
                    RETURNING id, next_config_version - 1 AS value
                )
                INSERT INTO model_config_version (
                    id, model_connection_id, version, model_id, temperature, max_output_tokens, created_by, created_at
                )
                SELECT :id, next_version.id, next_version.value, :modelId, :temperature,
                       :maxOutputTokens, :createdBy, :createdAt
                FROM next_version
                RETURNING
                """ + VERSION_COLUMNS)
                .param("id", id)
                .param("modelConnectionId", modelConnectionId)
                .param("modelId", modelId)
                .param("temperature", temperature)
                .param("maxOutputTokens", maxOutputTokens)
                .param("createdBy", createdBy)
                .param("createdAt", JdbcTimes.toJdbc(createdAt))
                .query(JdbcModelConnectionRepository::mapConfigVersion)
                .optional()
                .orElseThrow(() -> new IllegalStateException("model connection is unavailable"));
    }

    @Override
    public Optional<ModelConfigVersion> findConfigVersion(UUID id) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM model_config_version WHERE id = :id")
                .param("id", id)
                .query(JdbcModelConnectionRepository::mapConfigVersion)
                .optional();
    }

    @Override
    public List<ModelConfigVersion> findConfigVersions(UUID modelConnectionId) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM model_config_version"
                        + " WHERE model_connection_id = :modelConnectionId ORDER BY version DESC")
                .param("modelConnectionId", modelConnectionId)
                .query(JdbcModelConnectionRepository::mapConfigVersion)
                .list();
    }

    private static ModelConnection mapConnection(ResultSet resultSet, int rowNumber) throws SQLException {
        String encodedCredential = resultSet.getString("credential_envelope");
        return new ModelConnection(resultSet.getObject("id", UUID.class), resultSet.getString("name"),
                ModelProvider.valueOf(resultSet.getString("provider")), URI.create(resultSet.getString("base_url")),
                Optional.ofNullable(encodedCredential).map(CredentialEnvelope::new), resultSet.getBoolean("enabled"),
                ModelConnectionValidationStatus.valueOf(resultSet.getString("validation_status")),
                resultSet.getTimestamp("last_validated_at") == null
                        ? null : resultSet.getTimestamp("last_validated_at").toInstant(),
                resultSet.getObject("created_by", UUID.class), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static ModelConfigVersion mapConfigVersion(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ModelConfigVersion(resultSet.getObject("id", UUID.class),
                resultSet.getObject("model_connection_id", UUID.class), resultSet.getInt("version"),
                resultSet.getString("model_id"), resultSet.getBigDecimal("temperature"),
                resultSet.getInt("max_output_tokens"), resultSet.getObject("created_by", UUID.class),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
