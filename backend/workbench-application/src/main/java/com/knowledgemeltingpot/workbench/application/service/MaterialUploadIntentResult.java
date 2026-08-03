package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialUploadIntent;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import java.util.List;

public record MaterialUploadIntentResult(
        MaterialUploadIntent intent,
        Material material,
        List<RoundMaterial> bindings,
        boolean replayed) {

    public MaterialUploadIntentResult {
        bindings = List.copyOf(bindings);
    }
}
