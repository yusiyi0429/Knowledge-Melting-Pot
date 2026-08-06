package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.MaterialUploadIntent;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import com.knowledgemeltingpot.workbench.domain.UploadState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaterialRepository {
    Material insert(Material material);

    Optional<Material> findById(UUID id);

    void insertBindings(List<RoundMaterial> bindings);

    List<RoundMaterial> findBindings(UUID materialId);

    boolean deactivateBinding(UUID materialId, UUID bindingId);

    MaterialUploadIntent insertIntent(MaterialUploadIntent intent);

    Optional<MaterialUploadIntent> findIntent(UUID intentId);

    Optional<MaterialUploadIntent> lockIntent(UUID intentId);

    boolean transitionStatus(UUID materialId, MaterialStatus expected, MaterialStatus target, Instant updatedAt);

    boolean updateBlobId(UUID materialId, UUID blobId, MaterialStatus expected, MaterialStatus target,
            Instant updatedAt);

    boolean completeIntent(UUID intentId, UUID jobId, String clientEtag, Instant completedAt);

    boolean updateIntentState(UUID intentId, UploadState state);

    boolean incrementCompletionAttempt(UUID intentId);

    boolean abortIntent(UUID intentId, Instant abortedAt);

    /**
     * Workbench listing for a round + sub-scene, including every material status
     * (PENDING_UPLOAD, UPLOADED, SCANNING, FAILED, INACTIVE) and its binding.
     * Unlike the READY-only extraction selections, this must not filter by status.
     */
    List<MaterialSelection> findWorkbenchMaterials(UUID roundId, UUID subSceneId);
}
