package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.MaterialUploadIntent;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaterialRepository {
    Material insert(Material material);

    Optional<Material> findById(UUID id);

    void insertBindings(List<RoundMaterial> bindings);

    List<RoundMaterial> findBindings(UUID materialId);

    MaterialUploadIntent insertIntent(MaterialUploadIntent intent);

    Optional<MaterialUploadIntent> findIntent(UUID intentId);

    Optional<MaterialUploadIntent> lockIntent(UUID intentId);

    boolean transitionStatus(UUID materialId, MaterialStatus expected, MaterialStatus target, Instant updatedAt);

    boolean completeIntent(UUID intentId, UUID jobId, String clientEtag, Instant completedAt);
}
