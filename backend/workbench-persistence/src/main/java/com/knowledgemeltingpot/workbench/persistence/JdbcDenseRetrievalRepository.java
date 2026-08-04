package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.DenseRetrievalRepository;
import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import com.knowledgemeltingpot.workbench.domain.DenseRetrievalResult;
import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import java.sql.SQLException;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDenseRetrievalRepository implements DenseRetrievalRepository {
    private final JdbcClient jdbc;

    public JdbcDenseRetrievalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DenseRetrievalResult> searchKnowledge(UUID roundId, UUID subSceneId,
            EmbeddingProfileVersion profile, List<Float> queryVector, int limit) {
        String operator = switch (profile.distanceFunction()) {
            case "COSINE" -> "<=>";
            case "L2" -> "<->";
            default -> throw new IllegalArgumentException("unsupported embedding distance function");
        };
        int dimension = profile.dimension();
        if (queryVector.size() != dimension || dimension < 1 || dimension > 8192) {
            throw new IllegalArgumentException("query vector dimension does not match the active profile");
        }
        String profileLiteral = profile.id().toString();
        String distance = "(ce.vector::vector(" + dimension + ") " + operator
                + " CAST(:queryVector AS vector(" + dimension + ")))";
        String score = "COSINE".equals(profile.distanceFunction())
                ? "(1.0 - " + distance + ")"
                : "(1.0 / (1.0 + " + distance + "))";
        String sql = """
                WITH eligible AS (
                    SELECT DISTINCT ON (mc.id)
                           mc.id AS chunk_id, m.id AS material_id, mc.source_ref_code,
                           mc.locator, mc.content
                    FROM round_material rm
                    JOIN material m ON m.id = rm.material_id
                    JOIN material_chunk mc ON mc.blob_id = m.blob_id
                    WHERE m.status = 'READY' AND rm.active = TRUE
                      AND rm.partition IN ('SOURCE', 'LABELED_TRAIN')
                      AND rm.sub_scene_id IN (
                          SELECT ss.id FROM sub_scene ss
                          WHERE ss.scene_id = (
                              SELECT requested.scene_id FROM sub_scene requested
                              WHERE requested.id = :subSceneId))
                      AND (
                          (rm.share_scope = 'ROUND' AND rm.round_id = :roundId
                              AND rm.sub_scene_id = :subSceneId)
                          OR (rm.share_scope = 'SUBSCENE' AND rm.sub_scene_id = :subSceneId)
                          OR rm.share_scope = 'SCENE')
                    ORDER BY mc.id, rm.created_at, m.id
                )
                SELECT e.chunk_id, e.material_id, e.source_ref_code, e.locator, e.content,
                       %s AS score
                FROM eligible e
                JOIN chunk_embedding ce ON ce.chunk_id = e.chunk_id
                WHERE ce.profile_version_id = '%s'::uuid
                ORDER BY %s, e.chunk_id
                LIMIT :limit
                """.formatted(score, profileLiteral, distance);
        return jdbc.sql(sql)
                .param("roundId", roundId)
                .param("subSceneId", subSceneId)
                .param("queryVector", vectorText(queryVector))
                .param("limit", limit)
                .query((resultSet, rowNumber) -> {
                    ChunkLocator locator;
                    try {
                        locator = ChunkLocators.deserialize(resultSet.getString("locator"));
                    } catch (com.fasterxml.jackson.core.JacksonException exception) {
                        throw new SQLException("invalid chunk locator JSON", exception);
                    }
                    return new DenseRetrievalResult(resultSet.getObject("chunk_id", UUID.class),
                            resultSet.getObject("material_id", UUID.class),
                            resultSet.getString("source_ref_code"), locator,
                            resultSet.getString("content"), resultSet.getDouble("score"));
                })
                .list();
    }

    private static String vectorText(List<Float> vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        vector.forEach(value -> joiner.add(Float.toString(value)));
        return joiner.toString();
    }
}
