package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.MaterialBlob;
import com.knowledgemeltingpot.workbench.domain.SecurityPartition;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for immutable verified material blobs.
 */
public interface MaterialBlobRepository {

    MaterialBlob insert(MaterialBlob blob);

    Optional<MaterialBlob> findById(UUID id);

    Optional<MaterialBlob> findByPartitionAndSha256(SecurityPartition partition, String sha256);
}
