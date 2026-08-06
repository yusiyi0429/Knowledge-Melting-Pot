package com.knowledgemeltingpot.workbench.worker.agent;

import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Selects a bounded, representative exploration context without exposing source
 * content outside the Worker. DOCX tables commonly produce many tiny cells, so
 * taking the first N chunks would starve the model of the document body.
 */
final class ExplorationContextSelector {
    private static final int MIN_INFORMATIVE_CHARS = 6;

    private ExplorationContextSelector() { }

    static Selection select(List<Material> materials, Map<UUID, List<MaterialChunk>> byMaterial,
            int maxChunks, int maxCharacters) {
        if (maxChunks < 1 || maxCharacters < 1) {
            return new Selection(List.of(), 0, 0);
        }

        List<MaterialCandidates> candidatesByMaterial = materials.stream()
                .map(material -> new MaterialCandidates(material,
                        rankedCandidates(byMaterial.getOrDefault(material.id(), List.of()))))
                .filter(value -> !value.chunks().isEmpty())
                .toList();
        Map<UUID, List<SelectedChunk>> selectedByMaterial = new LinkedHashMap<>();
        int selectedChunks = 0;
        int selectedCharacters = 0;
        int rankIndex = 0;

        while (selectedChunks < maxChunks && selectedCharacters < maxCharacters) {
            boolean selectedAtThisRank = false;
            for (MaterialCandidates materialCandidates : candidatesByMaterial) {
                if (rankIndex >= materialCandidates.chunks().size()
                        || selectedChunks >= maxChunks || selectedCharacters >= maxCharacters) {
                    continue;
                }
                MaterialChunk chunk = materialCandidates.chunks().get(rankIndex);
                String content = chunk.content().strip();
                int remaining = maxCharacters - selectedCharacters;
                if (content.length() > remaining) {
                    content = content.substring(0, remaining);
                }
                if (content.isBlank()) {
                    continue;
                }
                selectedByMaterial.computeIfAbsent(materialCandidates.material().id(), ignored -> new ArrayList<>())
                        .add(new SelectedChunk(chunk, content));
                selectedChunks++;
                selectedCharacters += content.length();
                selectedAtThisRank = true;
            }
            if (!selectedAtThisRank) {
                break;
            }
            rankIndex++;
        }

        List<SelectedMaterial> selections = new ArrayList<>();
        for (Material material : materials) {
            List<SelectedChunk> selected = selectedByMaterial.get(material.id());
            if (selected == null || selected.isEmpty()) {
                continue;
            }
            selected.sort(Comparator.comparingInt(value -> value.chunk().ordinal()));
            selections.add(new SelectedMaterial(material, List.copyOf(selected)));
        }
        return new Selection(List.copyOf(selections), selectedChunks, selectedCharacters);
    }

    private static List<MaterialChunk> rankedCandidates(List<MaterialChunk> chunks) {
        Set<String> hashes = new HashSet<>();
        List<MaterialChunk> unique = chunks.stream()
                .filter(chunk -> !chunk.content().isBlank())
                .filter(chunk -> hashes.add(chunk.contentHash()))
                .toList();
        List<MaterialChunk> informative = unique.stream()
                .filter(chunk -> chunk.content().strip().length() >= MIN_INFORMATIVE_CHARS)
                .toList();
        List<MaterialChunk> candidates = informative.isEmpty() ? unique : informative;
        return candidates.stream()
                .sorted(Comparator.comparingInt((MaterialChunk chunk) -> chunk.content().strip().length())
                        .reversed()
                        .thenComparingInt(MaterialChunk::ordinal))
                .toList();
    }

    record Selection(List<SelectedMaterial> materials, int chunkCount, int characterCount) { }

    record SelectedMaterial(Material material, List<SelectedChunk> chunks) { }

    record SelectedChunk(MaterialChunk chunk, String content) { }

    private record MaterialCandidates(Material material, List<MaterialChunk> chunks) { }
}
