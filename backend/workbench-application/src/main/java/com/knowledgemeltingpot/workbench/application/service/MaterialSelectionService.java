package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.ChunkRepository;
import com.knowledgemeltingpot.workbench.application.port.ContextBudget;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelectionPort;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.application.port.TrustedContext;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side trusted context assembly. The strong-typed entry points bind a
 * workflow (knowledge / regulatory / evaluation) to a partition-fixed port
 * query; callers can never pass an arbitrary partition. Every returned chunk
 * and source reference comes from the database via the material's verified
 * blob — no client-supplied text, chunk id or locator is ever accepted.
 */
@Service
public class MaterialSelectionService {
    private final MaterialSelectionPort selectionPort;
    private final ChunkRepository chunkRepository;
    private final SceneRepository sceneRepository;

    public MaterialSelectionService(MaterialSelectionPort selectionPort, ChunkRepository chunkRepository,
            SceneRepository sceneRepository) {
        this.selectionPort = selectionPort;
        this.chunkRepository = chunkRepository;
        this.sceneRepository = sceneRepository;
    }

    @Transactional(readOnly = true)
    public List<MaterialSelection> forExtraction(UUID roundId, UUID subSceneId) {
        return requireEligible(selectionPort.findForExtraction(roundId, subSceneId), roundId, subSceneId,
                false, false);
    }

    @Transactional(readOnly = true)
    public List<MaterialSelection> forAlignment(UUID roundId, UUID subSceneId) {
        return requireEligible(selectionPort.findForAlignment(roundId, subSceneId), roundId, subSceneId,
                true, false);
    }

    @Transactional(readOnly = true)
    public List<MaterialSelection> forQa(UUID roundId, UUID subSceneId) {
        return requireEligible(selectionPort.findForQa(roundId, subSceneId), roundId, subSceneId,
                false, false);
    }

    @Transactional(readOnly = true)
    public List<MaterialSelection> forEvaluation(UUID roundId, UUID subSceneId) {
        return requireEligible(selectionPort.findForEvaluation(roundId, subSceneId), roundId, subSceneId,
                false, true);
    }

    /**
     * Assembles the trusted context for EXTRACT/QA-style workflows: READY
     * non-holdout materials and their persisted chunks, capped by the budget.
     */
    @Transactional(readOnly = true)
    public List<TrustedContext> knowledgeContext(UUID roundId, UUID subSceneId, ContextBudget budget) {
        List<MaterialSelection> selections = requireEligible(
                selectionPort.findForExtraction(roundId, subSceneId), roundId, subSceneId, false, false);
        return assemble(selections, budget);
    }

    /**
     * Assembles the trusted context for ALIGN: READY regulatory non-holdout
     * materials and their persisted chunks, capped by the budget.
     */
    @Transactional(readOnly = true)
    public List<TrustedContext> regulatoryContext(UUID roundId, UUID subSceneId, ContextBudget budget) {
        List<MaterialSelection> selections = requireEligible(
                selectionPort.findForAlignment(roundId, subSceneId), roundId, subSceneId, true, false);
        return assemble(selections, budget);
    }

    /**
     * Assembles the trusted context for EVALUATE: only LABELED_HOLDOUT
     * materials, capped by the budget.
     */
    @Transactional(readOnly = true)
    public List<TrustedContext> evaluationContext(UUID roundId, UUID subSceneId, ContextBudget budget) {
        List<MaterialSelection> selections = requireEligible(
                selectionPort.findForEvaluation(roundId, subSceneId), roundId, subSceneId, false, true);
        return assemble(selections, budget);
    }

    private List<TrustedContext> assemble(List<MaterialSelection> selections, ContextBudget budget) {
        if (selections.isEmpty()) {
            return List.of();
        }
        List<UUID> materialIds = selections.stream().map(selection -> selection.material().id()).toList();
        Map<UUID, List<MaterialChunk>> byMaterial = chunkRepository.findForMaterials(materialIds);
        List<TrustedContext> result = new ArrayList<>();
        long totalChars = 0;
        int acceptedChunks = 0;
        boolean budgetExhausted = false;
        for (MaterialSelection selection : selections) {
            List<MaterialChunk> materialChunks = byMaterial.get(selection.material().id());
            if (materialChunks == null || materialChunks.isEmpty()) {
                // READY but not yet chunked (for example ingested before V10):
                // excluded from the trusted context rather than fabricated.
                continue;
            }
            List<MaterialChunk> accepted = new ArrayList<>();
            for (MaterialChunk chunk : materialChunks) {
                if (acceptedChunks >= budget.topK()) {
                    budgetExhausted = true;
                    break;
                }
                if (totalChars + chunk.charCount() > budget.maxTotalChars()) {
                    budgetExhausted = true;
                    break;
                }
                totalChars += chunk.charCount();
                acceptedChunks++;
                accepted.add(chunk);
            }
            if (!accepted.isEmpty()) {
                result.add(new TrustedContext(selection, List.copyOf(accepted)));
            }
            if (budgetExhausted) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private List<MaterialSelection> requireEligible(List<MaterialSelection> candidates, UUID roundId,
            UUID subSceneId, boolean regulatoryOnly, boolean evaluationOnly) {
        List<MaterialSelection> selections = List.copyOf(candidates);
        if (!sceneRepository.findRound(roundId)
                .filter(round -> round.subSceneId().equals(subSceneId))
                .isPresent()) {
            throw new NotFoundException("round does not belong to sub-scene: " + roundId);
        }
        UUID sceneId = sceneRepository.findSubScene(subSceneId)
                .orElseThrow(() -> new NotFoundException("sub-scene not found: " + subSceneId))
                .sceneId();
        selections.forEach(selection -> requireEligible(
                selection, roundId, subSceneId, sceneId, regulatoryOnly, evaluationOnly));
        return selections;
    }

    private void requireEligible(MaterialSelection selection, UUID roundId, UUID subSceneId, UUID sceneId,
            boolean regulatoryOnly, boolean evaluationOnly) {
        RoundMaterial binding = selection.binding();
        if (!binding.active() || selection.material().status() != MaterialStatus.READY) {
            throw new IllegalStateException("material selection port returned an ineligible binding");
        }
        if (evaluationOnly) {
            if (binding.partition() != MaterialPartition.LABELED_HOLDOUT) {
                throw new IllegalStateException("material isolation breach: evaluation received non-holdout material");
            }
        } else {
            if (binding.partition() == MaterialPartition.LABELED_HOLDOUT) {
                throw new IllegalStateException("material isolation breach: knowledge workflow received holdout material");
            }
            if (regulatoryOnly && !binding.regulatorySource()) {
                throw new IllegalStateException("material isolation breach: alignment received non-regulatory material");
            }
        }
        UUID bindingSceneId = sceneRepository.findSubScene(binding.subSceneId())
                .orElseThrow(() -> new IllegalStateException(
                        "material selection port returned a binding with an unknown sub-scene"))
                .sceneId();
        if (!sceneId.equals(bindingSceneId)) {
            throw new IllegalStateException("material selection port returned a binding outside the requested scene");
        }
        if (binding.shareScope() == MaterialShareScope.ROUND
                && (!binding.roundId().equals(roundId) || !binding.subSceneId().equals(subSceneId))) {
            throw new IllegalStateException("material selection port returned a binding outside the requested round");
        }
        if (binding.shareScope() == MaterialShareScope.SUBSCENE
                && !binding.subSceneId().equals(subSceneId)) {
            throw new IllegalStateException("material selection port returned a binding outside the requested sub-scene");
        }
    }
}
