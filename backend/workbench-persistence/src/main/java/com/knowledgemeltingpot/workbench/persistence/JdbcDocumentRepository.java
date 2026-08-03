package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.error.PreconditionFailedException;
import com.knowledgemeltingpot.workbench.application.port.DocumentRepository;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentRepository implements DocumentRepository {
    private final JdbcTemplate jdbc;

    public JdbcDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DocumentRevision> findLatest(UUID documentId) {
        List<DocumentRevision> rows = jdbc.query("""
                SELECT r.id, r.document_id, d.sub_scene_id, r.revision, r.base_revision_id,
                       r.content, r.content_hash, r.revision_note, r.finalized, r.finalized_by,
                       r.finalized_at, r.created_by, r.created_at
                FROM knowledge_document d
                JOIN document_revision r ON r.id = d.current_revision_id
                WHERE d.id = ?
                """, JdbcDocumentRepository::mapRevision, documentId);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<DocumentRevision> findRevision(UUID revisionId) {
        List<DocumentRevision> rows = jdbc.query("""
                SELECT r.id, r.document_id, d.sub_scene_id, r.revision, r.base_revision_id,
                       r.content, r.content_hash, r.revision_note, r.finalized, r.finalized_by,
                       r.finalized_at, r.created_by, r.created_at
                FROM document_revision r
                JOIN knowledge_document d ON d.id = r.document_id
                WHERE r.id = ?
                """, JdbcDocumentRepository::mapRevision, revisionId);
        return rows.stream().findFirst();
    }

    @Override
    public List<DocumentRevision> findRevisions(UUID documentId) {
        return jdbc.query("""
                SELECT r.id, r.document_id, d.sub_scene_id, r.revision, r.base_revision_id,
                       r.content, r.content_hash, r.revision_note, r.finalized, r.finalized_by,
                       r.finalized_at, r.created_by, r.created_at
                FROM document_revision r
                JOIN knowledge_document d ON d.id = r.document_id
                WHERE r.document_id = ?
                ORDER BY r.revision DESC
                """, JdbcDocumentRepository::mapRevision, documentId);
    }

    @Override
    public DocumentRevision saveNextRevision(UUID documentId, UUID subSceneId, Long expectedRevision,
            UUID revisionId, String content, String contentHash, String revisionNote, boolean finalized,
            UUID actorId, Instant now) {
        ensureDocumentRow(documentId, subSceneId, expectedRevision, now);
        DocumentHead head = jdbc.queryForObject("""
                SELECT current_revision, current_revision_id
                FROM knowledge_document WHERE id = ? FOR UPDATE
                """, (resultSet, rowNumber) -> new DocumentHead(
                        resultSet.getLong("current_revision"),
                        resultSet.getObject("current_revision_id", UUID.class)), documentId);
        if (head == null) {
            throw new PreconditionFailedException("knowledge document does not exist");
        }
        if (expectedRevision == null && head.revision() != 0) {
            throw new PreconditionFailedException("knowledge document was created concurrently");
        }
        if (expectedRevision != null && head.revision() != expectedRevision) {
            throw new PreconditionFailedException("knowledge document has a newer revision");
        }
        long nextRevision = head.revision() + 1;
        jdbc.update("""
                INSERT INTO document_revision (
                    id, document_id, revision, base_revision_id, content, content_hash, revision_note,
                    finalized, finalized_by, finalized_at, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, revisionId, documentId, nextRevision, head.revisionId(), content, contentHash,
                revisionNote == null ? "" : revisionNote, finalized, finalized ? actorId : null,
                finalized ? JdbcTimes.toJdbc(now) : null, actorId, JdbcTimes.toJdbc(now));
        jdbc.update("""
                UPDATE knowledge_document
                SET current_revision = ?, current_revision_id = ?, updated_at = ?
                WHERE id = ?
                """, nextRevision, revisionId, JdbcTimes.toJdbc(now), documentId);
        return new DocumentRevision(revisionId, documentId, subSceneId, nextRevision, head.revisionId(), content,
                contentHash, revisionNote, finalized, finalized ? actorId : null, finalized ? now : null,
                actorId, now);
    }

    private void ensureDocumentRow(UUID documentId, UUID subSceneId, Long expectedRevision, Instant now) {
        if (expectedRevision != null) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO knowledge_document (
                        id, sub_scene_id, current_revision, current_revision_id, created_at, updated_at)
                    VALUES (?, ?, 0, NULL, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, documentId, subSceneId, JdbcTimes.toJdbc(now), JdbcTimes.toJdbc(now));
        } catch (DuplicateKeyException exception) {
            throw new PreconditionFailedException("sub-scene already has a knowledge document");
        }
    }

    private static DocumentRevision mapRevision(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DocumentRevision(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("document_id", UUID.class),
                resultSet.getObject("sub_scene_id", UUID.class),
                resultSet.getLong("revision"),
                resultSet.getObject("base_revision_id", UUID.class),
                resultSet.getString("content"),
                resultSet.getString("content_hash"),
                resultSet.getString("revision_note"),
                resultSet.getBoolean("finalized"),
                resultSet.getObject("finalized_by", UUID.class),
                resultSet.getTimestamp("finalized_at") == null
                        ? null
                        : resultSet.getTimestamp("finalized_at").toInstant(),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private record DocumentHead(long revision, UUID revisionId) {
    }
}
