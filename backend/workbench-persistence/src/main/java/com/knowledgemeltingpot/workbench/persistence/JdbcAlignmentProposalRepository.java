package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.AlignmentProposalRepository;
import com.knowledgemeltingpot.workbench.domain.AlignmentAction;
import com.knowledgemeltingpot.workbench.domain.AlignmentProposal;
import com.knowledgemeltingpot.workbench.domain.AlignmentProposalStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAlignmentProposalRepository implements AlignmentProposalRepository {
    private final JdbcClient jdbc;

    public JdbcAlignmentProposalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AlignmentProposal insert(AlignmentProposal proposal) {
        if (proposal.status() != AlignmentProposalStatus.READY) {
            throw new IllegalArgumentException("only READY proposals can be inserted");
        }
        jdbc.sql("""
                INSERT INTO alignment_proposal (
                    id, document_id, base_revision_id, base_etag, action, status,
                    structured_patch, reason, source_refs, regulatory_material_ids,
                    created_by, created_at)
                VALUES (
                    :id, :documentId, :baseRevisionId, :baseEtag, :action, 'READY',
                    CAST(:structuredPatch AS jsonb), :reason, CAST(:sourceRefs AS jsonb),
                    CAST(:regulatoryMaterialIds AS jsonb), :createdBy, :createdAt)
                """)
                .param("id", proposal.id())
                .param("documentId", proposal.documentId())
                .param("baseRevisionId", proposal.baseRevisionId())
                .param("baseEtag", proposal.baseEtag())
                .param("action", proposal.action().name())
                .param("structuredPatch", proposal.structuredPatchJson())
                .param("reason", proposal.reason())
                .param("sourceRefs", proposal.sourceRefsJson())
                .param("regulatoryMaterialIds", proposal.regulatoryMaterialIdsJson())
                .param("createdBy", proposal.createdBy())
                .param("createdAt", JdbcTimes.toJdbc(proposal.createdAt()))
                .update();
        return proposal;
    }

    @Override
    public Optional<AlignmentProposal> find(UUID proposalId) {
        return jdbc.sql("""
                SELECT p.id, p.document_id, p.base_revision_id, p.base_etag, p.action,
                       CASE WHEN a.proposal_id IS NULL THEN p.status ELSE 'ADOPTED' END AS effective_status,
                       p.structured_patch::text AS structured_patch,
                       p.reason, p.source_refs::text AS source_refs,
                       p.regulatory_material_ids::text AS regulatory_material_ids,
                       p.created_by, p.created_at, a.revision_id AS adopted_revision_id,
                       a.adopted_by, a.adopted_at
                FROM alignment_proposal p
                LEFT JOIN alignment_proposal_adoption a ON a.proposal_id = p.id
                WHERE p.id = :proposalId
                """)
                .param("proposalId", proposalId)
                .query(JdbcAlignmentProposalRepository::mapProposal)
                .optional();
    }

    @Override
    public List<AlignmentProposal> findByDocument(UUID documentId) {
        return jdbc.sql("""
                SELECT p.id, p.document_id, p.base_revision_id, p.base_etag, p.action,
                       CASE WHEN a.proposal_id IS NULL THEN p.status ELSE 'ADOPTED' END AS effective_status,
                       p.structured_patch::text AS structured_patch,
                       p.reason, p.source_refs::text AS source_refs,
                       p.regulatory_material_ids::text AS regulatory_material_ids,
                       p.created_by, p.created_at, a.revision_id AS adopted_revision_id,
                       a.adopted_by, a.adopted_at
                FROM alignment_proposal p
                LEFT JOIN alignment_proposal_adoption a ON a.proposal_id = p.id
                WHERE p.document_id = :documentId
                ORDER BY p.created_at DESC
                """)
                .param("documentId", documentId)
                .query(JdbcAlignmentProposalRepository::mapProposal)
                .list();
    }

    @Override
    public boolean insertAdoption(UUID proposalId, UUID revisionId, UUID actorId, Instant adoptedAt) {
        try {
            return jdbc.sql("""
                    INSERT INTO alignment_proposal_adoption (proposal_id, revision_id, adopted_by, adopted_at)
                    VALUES (:proposalId, :revisionId, :adoptedBy, :adoptedAt)
                    """)
                    .param("proposalId", proposalId)
                    .param("revisionId", revisionId)
                    .param("adoptedBy", actorId)
                    .param("adoptedAt", JdbcTimes.toJdbc(adoptedAt))
                    .update() == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    private static AlignmentProposal mapProposal(ResultSet resultSet, int rowNumber) throws SQLException {
        var adoptedTimestamp = resultSet.getTimestamp("adopted_at");
        return new AlignmentProposal(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("document_id", UUID.class),
                resultSet.getObject("base_revision_id", UUID.class),
                resultSet.getString("base_etag"),
                AlignmentAction.valueOf(resultSet.getString("action")),
                AlignmentProposalStatus.valueOf(resultSet.getString("effective_status")),
                resultSet.getString("structured_patch"),
                resultSet.getString("reason"),
                resultSet.getString("source_refs"),
                resultSet.getString("regulatory_material_ids"),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getObject("adopted_revision_id", UUID.class),
                resultSet.getObject("adopted_by", UUID.class),
                adoptedTimestamp == null ? null : adoptedTimestamp.toInstant());
    }
}
