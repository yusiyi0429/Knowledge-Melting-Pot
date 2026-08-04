package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.port.EmbeddingProfileVersionRepository;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.ModelConnectionValidationStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbeddingProfileService {
    private final EmbeddingProfileVersionRepository profiles;
    private final ModelConnectionRepository models;
    private final AuditService audit;
    private final Clock clock;

    public EmbeddingProfileService(EmbeddingProfileVersionRepository profiles,
            ModelConnectionRepository models, AuditService audit, Clock clock) {
        this.profiles = profiles;
        this.models = models;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<EmbeddingProfileVersion> list() {
        return profiles.findAll();
    }

    @Transactional
    public EmbeddingProfileVersion createAndActivate(UUID modelConnectionId, String modelId, int dimension,
            String profileVersion, String normalization, String distanceFunction,
            UUID actorId, String traceId) {
        if (dimension < 1 || dimension > 2_000) {
            throw new IllegalArgumentException("HNSW embedding dimension must be between 1 and 2000");
        }
        ModelConnection connection = models.findConnection(modelConnectionId)
                .orElseThrow(() -> new IllegalArgumentException("model connection not found"));
        if (!connection.enabled() || !connection.credentialConfigured()
                || connection.validationStatus() != ModelConnectionValidationStatus.CONNECTIVITY_VERIFIED) {
            throw new IllegalArgumentException(
                    "embedding profile requires an enabled, credentialed, connectivity-verified model connection");
        }
        Instant now = Instant.now(clock);
        EmbeddingProfileVersion profile = profiles.insertAndActivate(new EmbeddingProfileVersion(
                UUID.randomUUID(), connection.id(), connection.provider().name(), modelId, dimension,
                profileVersion, normalization, distanceFunction, true, now), actorId, now);
        audit.record(actorId, "EMBEDDING_PROFILE_ACTIVATED", "EMBEDDING_PROFILE", profile.id(),
                Map.of("modelConnectionId", modelConnectionId, "provider", profile.provider(),
                        "modelId", profile.modelId(), "dimension", profile.dimension(),
                        "profileVersion", profile.profileVersion(),
                        "distanceFunction", profile.distanceFunction()), traceId);
        return profile;
    }
}
