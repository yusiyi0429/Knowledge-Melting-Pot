package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.ModelConfigVersion;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModelConnectionRepository {
    ModelConnection save(ModelConnection connection);

    Optional<ModelConnection> findConnection(UUID id);

    List<ModelConnection> findConnections();

    Optional<ModelConnection> markConfigurationValidated(UUID id, Instant validatedAt);

    boolean softDelete(UUID id, Instant deletedAt);

    ModelConfigVersion appendConfigVersion(UUID id, UUID modelConnectionId, String modelId,
            BigDecimal temperature, int maxOutputTokens, UUID createdBy, Instant createdAt);

    Optional<ModelConfigVersion> findConfigVersion(UUID id);

    List<ModelConfigVersion> findConfigVersions(UUID modelConnectionId);
}
