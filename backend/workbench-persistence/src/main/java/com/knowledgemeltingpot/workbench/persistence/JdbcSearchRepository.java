package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.SearchRepository;
import com.knowledgemeltingpot.workbench.domain.SearchResult;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSearchRepository implements SearchRepository {
    private final JdbcClient jdbc;

    public JdbcSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SearchResult> search(String normalizedQuery, int limit) {
        String escaped = normalizedQuery.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return jdbc.sql("""
                WITH matches AS (
                    SELECT 'SCENE' AS result_type, s.id AS scene_id, NULL::uuid AS sub_scene_id,
                           s.id AS resource_id, s.name AS title, LEFT(s.description, 240) AS excerpt,
                           CASE WHEN LOWER(s.name) = LOWER(:query) THEN 0 ELSE 1 END AS priority
                    FROM scene s WHERE s.name ILIKE :pattern ESCAPE '\\' OR s.description ILIKE :pattern ESCAPE '\\'
                    UNION ALL
                    SELECT 'SCENE', s.id, ss.id, ss.id, s.name || ' / ' || ss.name,
                           LEFT(ss.description, 240), 2
                    FROM sub_scene ss JOIN scene s ON s.id = ss.scene_id
                    WHERE ss.name ILIKE :pattern ESCAPE '\\' OR ss.description ILIKE :pattern ESCAPE '\\'
                    UNION ALL
                    SELECT 'RULE', s.id, ss.id, dr.id, s.name || ' / ' || ss.name || ' · 规则',
                           LEFT(REGEXP_REPLACE(dr.content, '\\s+', ' ', 'g'), 240), 3
                    FROM knowledge_document kd
                    JOIN document_revision dr ON dr.id = kd.current_revision_id
                    JOIN sub_scene ss ON ss.id = kd.sub_scene_id JOIN scene s ON s.id = ss.scene_id
                    WHERE dr.content ILIKE :pattern ESCAPE '\\'
                    UNION ALL
                    SELECT DISTINCT 'SOURCE', s.id, ss.id, m.id, m.file_name,
                           '来源素材 · ' || s.name || ' / ' || ss.name, 4
                    FROM material m JOIN round_material rm ON rm.material_id = m.id
                    JOIN sub_scene ss ON ss.id = rm.sub_scene_id JOIN scene s ON s.id = ss.scene_id
                    WHERE rm.active = TRUE AND rm.partition <> 'LABELED_HOLDOUT'
                      AND m.status = 'READY' AND m.file_name ILIKE :pattern ESCAPE '\\'
                )
                SELECT result_type, scene_id, sub_scene_id, resource_id, title, excerpt
                FROM matches ORDER BY priority, title, resource_id LIMIT :limit
                """).param("query", normalizedQuery).param("pattern", "%" + escaped + "%").param("limit", limit)
                .query((rs, row) -> new SearchResult(SearchResult.Type.valueOf(rs.getString("result_type")),
                        rs.getObject("scene_id", UUID.class), rs.getObject("sub_scene_id", UUID.class),
                        rs.getObject("resource_id", UUID.class), rs.getString("title"), rs.getString("excerpt")))
                .list();
    }
}
