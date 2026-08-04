package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence port for immutable material chunks. Chunk rows are committed in
 * one transaction and never rewritten; the unique key
 * (blob_id, parser_version, ordinal) makes repeated commits idempotent.
 */
public interface ChunkRepository {

    /**
     * Atomically inserts all chunks for one blob/parser-version combination.
     * Existing rows (same key) are skipped. Returns the number of chunks
     * present for the blob/parser-version after the call.
     */
    int commitAll(UUID blobId, String parserVersion, List<MaterialChunk> chunks);

    boolean existsForBlob(UUID blobId);

    List<MaterialChunk> findByBlob(UUID blobId);

    /**
     * Loads chunks for the verified blobs of the given materials (via
     * material.blob_id), grouped by material id. Used only to assemble a
     * server-side trusted context; callers cannot select chunks by arbitrary
     * client-provided keys.
     */
    Map<UUID, List<MaterialChunk>> findForMaterials(List<UUID> materialIds);

    /**
     * Resolves persisted source references only through active SOURCE/TRAIN
     * bindings of the requested round and sub-scene. HOLDOUT rows can never be
     * returned by this method.
     */
    List<MaterialSourceRef> findTrustedSourceRefs(UUID roundId, UUID subSceneId, List<String> sourceRefCodes);
}
