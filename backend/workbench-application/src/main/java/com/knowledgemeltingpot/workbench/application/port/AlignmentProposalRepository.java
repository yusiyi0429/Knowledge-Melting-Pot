package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.AlignmentProposal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlignmentProposalRepository {
    AlignmentProposal insert(AlignmentProposal proposal);

    Optional<AlignmentProposal> find(UUID proposalId);

    List<AlignmentProposal> findByDocument(UUID documentId);

    boolean insertAdoption(UUID proposalId, UUID revisionId, UUID actorId, Instant adoptedAt);
}
