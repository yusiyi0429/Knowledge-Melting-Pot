package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.MaterialBlobRepository;
import com.knowledgemeltingpot.workbench.domain.MaterialBlob;
import com.knowledgemeltingpot.workbench.domain.SecurityPartition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMaterialBlobRepository implements MaterialBlobRepository {

    private final JdbcClient jdbc;

    public JdbcMaterialBlobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public MaterialBlob insert(MaterialBlob blob) {
        try {
            jdbc.sql("""
                    INSERT INTO material_blob (
                        id, security_partition, verified_sha256, clean_object_key, size_bytes, detected_mime,
                        scan_engine_version, scan_signature_version, parser_name, parser_version, created_at)
                    VALUES (
                        :id, :securityPartition, :verifiedSha256, :cleanObjectKey, :sizeBytes, :detectedMime,
                        :scanEngineVersion, :scanSignatureVersion, :parserName, :parserVersion, :createdAt)
                    """)
                    .param("id", blob.id())
                    .param("securityPartition", blob.securityPartition().name())
                    .param("verifiedSha256", blob.verifiedSha256())
                    .param("cleanObjectKey", blob.cleanObjectKey())
                    .param("sizeBytes", blob.sizeBytes())
                    .param("detectedMime", blob.detectedMime())
                    .param("scanEngineVersion", blob.scanEngineVersion())
                    .param("scanSignatureVersion", blob.scanSignatureVersion())
                    .param("parserName", blob.parserName())
                    .param("parserVersion", blob.parserVersion())
                    .param("createdAt", JdbcTimes.toJdbc(blob.createdAt()))
                    .update();
            return blob;
        } catch (DataIntegrityViolationException conflict) {
            // Concurrent ingest of identical content: the content-unique constraint
            // (security_partition, verified_sha256) means one row wins and the
            // loser reuses the committed blob idempotently.
            return findByPartitionAndSha256(blob.securityPartition(), blob.verifiedSha256())
                    .orElseThrow(() -> conflict);
        }
    }

    @Override
    public Optional<MaterialBlob> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, security_partition, verified_sha256, clean_object_key, size_bytes, detected_mime,
                       scan_engine_version, scan_signature_version, parser_name, parser_version, created_at
                FROM material_blob WHERE id = :id
                """)
                .param("id", id)
                .query(JdbcMaterialBlobRepository::mapBlob)
                .optional();
    }

    @Override
    public Optional<MaterialBlob> findByPartitionAndSha256(SecurityPartition partition, String sha256) {
        return jdbc.sql("""
                SELECT id, security_partition, verified_sha256, clean_object_key, size_bytes, detected_mime,
                       scan_engine_version, scan_signature_version, parser_name, parser_version, created_at
                FROM material_blob
                WHERE security_partition = :partition AND verified_sha256 = :sha256
                """)
                .param("partition", partition.name())
                .param("sha256", sha256)
                .query(JdbcMaterialBlobRepository::mapBlob)
                .optional();
    }

    private static MaterialBlob mapBlob(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MaterialBlob(
                resultSet.getObject("id", UUID.class),
                SecurityPartition.valueOf(resultSet.getString("security_partition")),
                resultSet.getString("verified_sha256").trim(),
                resultSet.getString("clean_object_key"),
                resultSet.getLong("size_bytes"),
                resultSet.getString("detected_mime"),
                resultSet.getString("scan_engine_version"),
                resultSet.getString("scan_signature_version"),
                resultSet.getString("parser_name"),
                resultSet.getString("parser_version"),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
