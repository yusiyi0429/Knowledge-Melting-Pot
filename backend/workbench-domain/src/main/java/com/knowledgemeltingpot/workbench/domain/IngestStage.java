package com.knowledgemeltingpot.workbench.domain;

public enum IngestStage {
    STARTED,
    HEAD_VERIFIED,
    HASH_VERIFIED,
    MIME_VERIFIED,
    MALWARE_CLEAN,
    ARCHIVE_BUDGET_VERIFIED,
    PARSED,
    CHUNKS_COMMITTED,
    EMBEDDINGS_COMMITTED,
    OBJECT_VERIFIED,
    FAILED
}
