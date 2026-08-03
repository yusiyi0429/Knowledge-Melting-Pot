package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.domain.ReleaseCoverage;
import java.util.List;
import java.util.UUID;

public record ReleaseValidation(
        boolean ready,
        ReleaseCoverage coverage,
        UUID baseReleaseId,
        List<UUID> selected,
        List<UUID> carriedForward,
        List<UUID> missing,
        List<String> blockers,
        List<String> warnings) {

    public ReleaseValidation {
        selected = List.copyOf(selected);
        carriedForward = List.copyOf(carriedForward);
        missing = List.copyOf(missing);
        blockers = List.copyOf(blockers);
        warnings = List.copyOf(warnings);
    }
}
