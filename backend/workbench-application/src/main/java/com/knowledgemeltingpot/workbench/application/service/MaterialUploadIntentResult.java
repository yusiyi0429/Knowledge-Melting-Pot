package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialUploadIntent;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import java.net.URL;
import java.util.List;

public record MaterialUploadIntentResult(
        MaterialUploadIntent intent,
        Material material,
        List<RoundMaterial> bindings,
        boolean replayed,
        boolean objectStorageConfigured,
        String uploadMode,
        String capabilityStatus,
        String messageCode,
        List<URL> presignedUrls) {

    public MaterialUploadIntentResult {
        bindings = List.copyOf(bindings);
        presignedUrls = presignedUrls == null ? List.of() : List.copyOf(presignedUrls);
    }

    public MaterialUploadIntentResult(MaterialUploadIntent intent, Material material,
            List<RoundMaterial> bindings, boolean replayed) {
        this(intent, material, bindings, replayed, false, "DECLARATION_ONLY",
                "OBJECT_STORAGE_NOT_CONFIGURED", "material.upload.object-storage-not-configured", List.of());
    }
}
