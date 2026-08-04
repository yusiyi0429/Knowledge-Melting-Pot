-- V10: immutable material chunks with trusted locators, the embedding profile
-- version table and idempotent chunk embeddings.

-- The pgvector image only pre-creates the extension in its default database;
-- every workbench database (including Compose verification projects) must
-- create it explicitly before the vector type can be used.
CREATE EXTENSION IF NOT EXISTS vector;

-- The client-declared material SHA is not the deduplication fact (the verified
-- blob is): identical content uploaded twice must share one verified blob and
-- one chunk set. The historical per-material sha256 uniqueness is removed so
-- the content-addressed blob remains the single identity.
ALTER TABLE material DROP CONSTRAINT uk_material_sha256;

-- A verified blob is parsed at most once per parser version. The unique key
-- (blob_id, parser_version, ordinal) makes re-parse idempotent; locator is the
-- server-derived origin (page/paragraph/table/sheet/row/column/line ranges).
CREATE TABLE material_chunk (
    id UUID PRIMARY KEY,
    blob_id UUID NOT NULL REFERENCES material_blob(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL,
    source_ref_code VARCHAR(120) NOT NULL,
    locator JSONB NOT NULL,
    content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    char_count INTEGER NOT NULL,
    parser_version VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_material_chunk UNIQUE (blob_id, parser_version, ordinal),
    CONSTRAINT uk_material_chunk_source_ref UNIQUE (source_ref_code),
    CONSTRAINT ck_material_chunk_ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_material_chunk_content_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_material_chunk_char_count CHECK (char_count >= 0)
);

CREATE INDEX ix_material_chunk_blob ON material_chunk (blob_id, ordinal);

CREATE INDEX ix_material_chunk_source_ref ON material_chunk (source_ref_code);

-- Terminal chunk data is immutable: a verified blob is never re-parsed into
-- changed content under the same parser version.
CREATE FUNCTION prevent_material_chunk_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'material_chunk is immutable';
END;
$$;

CREATE TRIGGER trg_material_chunk_immutable
BEFORE UPDATE OR DELETE ON material_chunk
FOR EACH ROW
EXECUTE FUNCTION prevent_material_chunk_mutation();

-- A versioned, immutable description of a real embedding model configuration.
-- A row must exist before any vector is written; the worker never fabricates
-- vectors from random, hashes or fixed values.
CREATE TABLE embedding_profile_version (
    id UUID PRIMARY KEY,
    provider VARCHAR(100) NOT NULL,
    model_id VARCHAR(200) NOT NULL,
    dimension INTEGER NOT NULL,
    profile_version VARCHAR(100) NOT NULL,
    normalization VARCHAR(20) NOT NULL,
    distance_function VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_embedding_profile_version
        UNIQUE (provider, model_id, dimension, profile_version),
    CONSTRAINT ck_embedding_profile_dimension CHECK (dimension BETWEEN 1 AND 8192),
    CONSTRAINT ck_embedding_profile_normalization CHECK (normalization IN ('NONE', 'L2')),
    CONSTRAINT ck_embedding_profile_distance CHECK (distance_function IN ('COSINE', 'L2'))
);

-- At most one active profile at a time.
CREATE UNIQUE INDEX uq_embedding_profile_active
    ON embedding_profile_version (active) WHERE active = TRUE;

CREATE FUNCTION prevent_embedding_profile_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'embedding_profile_version is immutable';
END;
$$;

CREATE TRIGGER trg_embedding_profile_immutable
BEFORE UPDATE OR DELETE ON embedding_profile_version
FOR EACH ROW
EXECUTE FUNCTION prevent_embedding_profile_mutation();

-- One vector per (chunk, profile version). The unique key makes repeated
-- vectorization idempotent; content_hash is re-verified against the chunk at
-- write time and the vector dimension must match the declared dimension.
-- No fixed-dimension vector column or approximate index is created until the
-- embedding dimension for production retrieval is confirmed.
CREATE TABLE chunk_embedding (
    chunk_id UUID NOT NULL REFERENCES material_chunk(id) ON DELETE RESTRICT,
    profile_version_id UUID NOT NULL REFERENCES embedding_profile_version(id) ON DELETE RESTRICT,
    content_hash CHAR(64) NOT NULL,
    dimension INTEGER NOT NULL,
    vector vector NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_chunk_embedding PRIMARY KEY (chunk_id, profile_version_id),
    CONSTRAINT ck_chunk_embedding_content_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chunk_embedding_dimension CHECK (vector_dims(vector) = dimension)
);

CREATE INDEX ix_chunk_embedding_profile ON chunk_embedding (profile_version_id);

CREATE FUNCTION prevent_chunk_embedding_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'chunk_embedding is immutable';
END;
$$;

CREATE TRIGGER trg_chunk_embedding_immutable
BEFORE UPDATE OR DELETE ON chunk_embedding
FOR EACH ROW
EXECUTE FUNCTION prevent_chunk_embedding_mutation();
