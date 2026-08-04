package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import java.util.List;

/**
 * One READY material together with its server-derived chunks. The chunks are
 * loaded exclusively from the database via the material's verified blob;
 * no chunk text, locator or source reference is ever accepted from a client.
 */
public record TrustedContext(MaterialSelection selection, List<MaterialChunk> chunks) {

    public TrustedContext {
        if (selection == null) {
            throw new IllegalArgumentException("selection is required");
        }
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("a trusted context requires at least one chunk");
        }
    }
}
