package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.port.SkillRepository;
import com.knowledgemeltingpot.workbench.domain.Skill;
import com.knowledgemeltingpot.workbench.domain.SkillKind;
import com.knowledgemeltingpot.workbench.domain.SkillVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSkillRepository implements SkillRepository {
    private static final String SKILL_COLUMNS = """
            id, name, kind, description, scene_id, source_skill_id, source_skill_version_id, created_by, created_at
            """;
    private static final String VERSION_COLUMNS = """
            id, skill_id, version, manifest_json, package_hash, created_by, created_at
            """;

    private final JdbcClient jdbc;

    public JdbcSkillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Skill> findSkills(SkillKind kind, UUID sceneId) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SKILL_COLUMNS).append(" FROM skill");
        boolean where = false;
        if (kind != null) {
            sql.append(" WHERE kind = :kind");
            where = true;
        }
        if (sceneId != null) {
            sql.append(where ? " AND" : " WHERE").append(" scene_id = :sceneId");
        }
        sql.append(" ORDER BY created_at DESC, id");
        var spec = jdbc.sql(sql.toString());
        if (kind != null) {
            spec = spec.param("kind", kind.name());
        }
        if (sceneId != null) {
            spec = spec.param("sceneId", sceneId);
        }
        return spec.query(JdbcSkillRepository::mapSkill).list();
    }

    @Override
    public Optional<Skill> findById(UUID skillId) {
        return jdbc.sql("SELECT " + SKILL_COLUMNS + " FROM skill WHERE id = :id")
                .param("id", skillId)
                .query(JdbcSkillRepository::mapSkill)
                .optional();
    }

    @Override
    public Skill insert(Skill skill) {
        jdbc.sql("""
                INSERT INTO skill (
                    id, name, kind, description, scene_id, source_skill_id, source_skill_version_id,
                    created_by, created_at)
                VALUES (
                    :id, :name, :kind, :description, :sceneId, :sourceSkillId, :sourceSkillVersionId,
                    :createdBy, :createdAt)
                """)
                .param("id", skill.id())
                .param("name", skill.name())
                .param("kind", skill.kind().name())
                .param("description", skill.description())
                .param("sceneId", skill.sceneId())
                .param("sourceSkillId", skill.sourceSkillId())
                .param("sourceSkillVersionId", skill.sourceSkillVersionId())
                .param("createdBy", skill.createdBy())
                .param("createdAt", JdbcTimes.toJdbc(skill.createdAt()))
                .update();
        return skill;
    }

    @Override
    public Optional<SkillVersion> findLatestVersion(UUID skillId) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId ORDER BY version DESC LIMIT 1")
                .param("skillId", skillId)
                .query(JdbcSkillRepository::mapVersion)
                .optional();
    }

    @Override
    public List<SkillVersion> findVersions(UUID skillId) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId ORDER BY version DESC")
                .param("skillId", skillId)
                .query(JdbcSkillRepository::mapVersion)
                .list();
    }

    @Override
    public Optional<SkillVersion> findVersion(UUID versionId) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM skill_version WHERE id = :id")
                .param("id", versionId)
                .query(JdbcSkillRepository::mapVersion)
                .optional();
    }

    @Override
    public SkillVersion insertVersion(Skill skill, UUID versionId, String manifestJson, String packageHash,
            UUID createdBy, Instant createdAt) {
        // Lock the skill row so concurrent version appends cannot produce the same version number.
        jdbc.sql("SELECT id FROM skill WHERE id = :id FOR UPDATE")
                .param("id", skill.id())
                .query(UUID.class)
                .single();
        Integer next = jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM skill_version WHERE skill_id = :skillId")
                .param("skillId", skill.id())
                .query(Integer.class)
                .single();
        jdbc.sql("""
                INSERT INTO skill_version (
                    id, skill_id, version, manifest_json, package_hash, created_by, created_at)
                VALUES (
                    :id, :skillId, :version, :manifestJson, :packageHash, :createdBy, :createdAt)
                """)
                .param("id", versionId)
                .param("skillId", skill.id())
                .param("version", next)
                .param("manifestJson", manifestJson)
                .param("packageHash", packageHash)
                .param("createdBy", createdBy)
                .param("createdAt", JdbcTimes.toJdbc(createdAt))
                .update();
        return new SkillVersion(versionId, skill.id(), next, manifestJson, packageHash, createdBy, createdAt);
    }

    private static Skill mapSkill(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Skill(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                SkillKind.valueOf(resultSet.getString("kind")),
                resultSet.getString("description"),
                resultSet.getObject("scene_id", UUID.class),
                resultSet.getObject("source_skill_id", UUID.class),
                resultSet.getObject("source_skill_version_id", UUID.class),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private static SkillVersion mapVersion(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SkillVersion(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("skill_id", UUID.class),
                resultSet.getInt("version"),
                resultSet.getString("manifest_json"),
                resultSet.getString("package_hash"),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
