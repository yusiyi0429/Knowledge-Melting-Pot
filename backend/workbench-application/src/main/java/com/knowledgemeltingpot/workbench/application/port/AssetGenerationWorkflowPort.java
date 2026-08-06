package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.AssetType;
import java.util.List;
import java.util.UUID;

/** Typed boundary for the four asset-generation roles. Dynamic Agent runtime values never cross this interface. */
public interface AssetGenerationWorkflowPort {
    AssetDraft generate(AssetRequest request);

    record AssetRequest(UUID jobId, AssetType assetType, UUID modelConfigVersionId, UUID skillVersionId,
            String documentMarkdown, List<String> sourceRefCodes, List<HoldoutSource> holdoutSources) {
        public AssetRequest {
            sourceRefCodes = sourceRefCodes == null ? List.of() : List.copyOf(sourceRefCodes);
            holdoutSources = holdoutSources == null ? List.of() : List.copyOf(holdoutSources);
            documentMarkdown = documentMarkdown == null ? "" : documentMarkdown;
            if (assetType == AssetType.EVALUATION_SET && !documentMarkdown.isBlank()) {
                throw new IllegalArgumentException("evaluation generation must not receive document content");
            }
        }
    }

    record HoldoutSource(UUID materialId, String sha256, String format, long sizeBytes) { }

    record AssetDraft(String summary, List<DraftItem> items) {
        public AssetDraft {
            summary = summary == null ? "" : summary;
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * Common typed item: title/content become rule+basis, step+basis, prompt block, or question+answer by asset type.
     */
    record DraftItem(String id, String title, String content, List<String> sourceRefs, List<String> tags) {
        public DraftItem {
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }
}
