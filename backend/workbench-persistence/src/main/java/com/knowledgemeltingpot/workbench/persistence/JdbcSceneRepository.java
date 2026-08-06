package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import com.knowledgemeltingpot.workbench.domain.ExtractionRoundStatus;
import com.knowledgemeltingpot.workbench.domain.Scene;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSceneRepository implements SceneRepository {
    private final JdbcClient jdbc;

    public JdbcSceneRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Scene save(Scene scene) {
        jdbc.sql("""
                INSERT INTO scene (id, name, description, created_at, updated_at)
                VALUES (:id, :name, :description, :createdAt, :updatedAt)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("id", scene.id())
                .param("name", scene.name())
                .param("description", scene.description())
                .param("createdAt", JdbcTimes.toJdbc(scene.createdAt()))
                .param("updatedAt", JdbcTimes.toJdbc(scene.updatedAt()))
                .update();
        return scene;
    }

    @Override
    public Optional<Scene> findScene(UUID id) {
        return jdbc.sql("""
                SELECT id, name, description, created_at, updated_at
                FROM scene WHERE id = :id AND archived_at IS NULL
                """)
                .param("id", id)
                .query(JdbcSceneRepository::mapScene)
                .optional();
    }

    @Override
    public List<Scene> findAllScenes() {
        return jdbc.sql("""
                SELECT id, name, description, created_at, updated_at
                FROM scene WHERE archived_at IS NULL ORDER BY updated_at DESC, id
                """)
                .query(JdbcSceneRepository::mapScene)
                .list();
    }

    @Override
    public boolean archiveScene(UUID id, UUID actorId, java.time.Instant archivedAt) {
        return jdbc.sql("""
                UPDATE scene SET archived_at = :archivedAt, archived_by = :actorId
                WHERE id = :id AND archived_at IS NULL
                """)
                .param("id", id)
                .param("actorId", actorId)
                .param("archivedAt", JdbcTimes.toJdbc(archivedAt))
                .update() == 1;
    }

    @Override
    public SubScene save(SubScene subScene) {
        jdbc.sql("""
                INSERT INTO sub_scene (id, scene_id, name, description, created_at, updated_at)
                VALUES (:id, :sceneId, :name, :description, :createdAt, :updatedAt)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("id", subScene.id())
                .param("sceneId", subScene.sceneId())
                .param("name", subScene.name())
                .param("description", subScene.description())
                .param("createdAt", JdbcTimes.toJdbc(subScene.createdAt()))
                .param("updatedAt", JdbcTimes.toJdbc(subScene.updatedAt()))
                .update();
        return subScene;
    }

    @Override
    public Optional<SubScene> findSubScene(UUID id) {
        return jdbc.sql("""
                SELECT ss.id, ss.scene_id, ss.name, ss.description, ss.created_at, ss.updated_at
                FROM sub_scene ss
                JOIN scene s ON s.id = ss.scene_id AND s.archived_at IS NULL
                WHERE ss.id = :id
                """)
                .param("id", id)
                .query(JdbcSceneRepository::mapSubScene)
                .optional();
    }

    @Override
    public List<SubScene> findSubScenes(UUID sceneId) {
        return jdbc.sql("""
                SELECT ss.id, ss.scene_id, ss.name, ss.description, ss.created_at, ss.updated_at
                FROM sub_scene ss
                JOIN scene s ON s.id = ss.scene_id AND s.archived_at IS NULL
                WHERE ss.scene_id = :sceneId ORDER BY ss.created_at, ss.id
                """)
                .param("sceneId", sceneId)
                .query(JdbcSceneRepository::mapSubScene)
                .list();
    }

    @Override
    public Optional<ExtractionRound> findRound(UUID id) {
        return jdbc.sql("""
                SELECT er.id, er.sub_scene_id, er.round_number, er.status, er.created_at, er.updated_at
                FROM extraction_round er
                JOIN sub_scene ss ON ss.id = er.sub_scene_id
                JOIN scene s ON s.id = ss.scene_id AND s.archived_at IS NULL
                WHERE er.id = :id
                """)
                .param("id", id)
                .query((resultSet, rowNumber) -> new ExtractionRound(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("sub_scene_id", UUID.class),
                        resultSet.getInt("round_number"),
                        ExtractionRoundStatus.valueOf(resultSet.getString("status")),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    @Override
    public ExtractionRound createNextRound(UUID subSceneId, UUID roundId, java.time.Instant now) {
        jdbc.sql("""
                SELECT ss.id FROM sub_scene ss
                JOIN scene s ON s.id = ss.scene_id AND s.archived_at IS NULL
                WHERE ss.id = :id FOR UPDATE OF ss
                """)
                .param("id", subSceneId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("sub-scene does not exist"));
        int nextRound = jdbc.sql("""
                SELECT COALESCE(MAX(round_number), 0) + 1
                FROM extraction_round WHERE sub_scene_id = :subSceneId
                """)
                .param("subSceneId", subSceneId)
                .query(Integer.class)
                .single();
        ExtractionRound round = new ExtractionRound(roundId, subSceneId, nextRound,
                ExtractionRoundStatus.DRAFT, now, now);
        jdbc.sql("""
                INSERT INTO extraction_round (
                    id, sub_scene_id, round_number, status, created_at, updated_at)
                VALUES (:id, :subSceneId, :roundNumber, :status, :createdAt, :updatedAt)
                """)
                .param("id", round.id())
                .param("subSceneId", round.subSceneId())
                .param("roundNumber", round.roundNumber())
                .param("status", round.status().name())
                .param("createdAt", JdbcTimes.toJdbc(round.createdAt()))
                .param("updatedAt", JdbcTimes.toJdbc(round.updatedAt()))
                .update();
        return round;
    }

    @Override
    public List<ExtractionRound> findRoundsByScene(UUID sceneId) {
        return jdbc.sql("""
                SELECT r.id, r.sub_scene_id, r.round_number, r.status, r.created_at, r.updated_at
                FROM extraction_round r
                JOIN sub_scene s ON s.id = r.sub_scene_id
                WHERE s.scene_id = :sceneId
                ORDER BY r.created_at, r.id
                """)
                .param("sceneId", sceneId)
                .query((resultSet, rowNumber) -> new ExtractionRound(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("sub_scene_id", UUID.class),
                        resultSet.getInt("round_number"),
                        ExtractionRoundStatus.valueOf(resultSet.getString("status")),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()))
                .list();
    }

    private static Scene mapScene(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Scene(resultSet.getObject("id", UUID.class), resultSet.getString("name"),
                resultSet.getString("description"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static SubScene mapSubScene(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SubScene(resultSet.getObject("id", UUID.class), resultSet.getObject("scene_id", UUID.class),
                resultSet.getString("name"), resultSet.getString("description"),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant());
    }
}
