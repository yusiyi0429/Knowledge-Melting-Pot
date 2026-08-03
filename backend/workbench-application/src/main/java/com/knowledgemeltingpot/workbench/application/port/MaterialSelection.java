package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;

public record MaterialSelection(Material material, RoundMaterial binding) {
    public MaterialSelection {
        if (material == null || binding == null) {
            throw new IllegalArgumentException("material and binding are required");
        }
        if (!material.id().equals(binding.materialId())) {
            throw new IllegalArgumentException("binding does not reference material");
        }
    }
}
