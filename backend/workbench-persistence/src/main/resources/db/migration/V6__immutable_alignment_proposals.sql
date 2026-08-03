CREATE TABLE alignment_proposal (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE RESTRICT,
    base_revision_id UUID NOT NULL REFERENCES document_revision(id) ON DELETE RESTRICT,
    base_etag VARCHAR(200) NOT NULL,
    action VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'READY',
    structured_patch JSONB NOT NULL,
    reason TEXT NOT NULL,
    source_refs JSONB NOT NULL,
    regulatory_material_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_alignment_proposal_action
        CHECK (action IN ('CONSISTENCY', 'REGULATORY', 'GAP_ANALYSIS', 'REWRITE')),
    CONSTRAINT ck_alignment_proposal_status CHECK (status = 'READY'),
    CONSTRAINT ck_alignment_proposal_patch_object CHECK (jsonb_typeof(structured_patch) = 'object'),
    CONSTRAINT ck_alignment_proposal_source_refs_array CHECK (jsonb_typeof(source_refs) = 'array'),
    CONSTRAINT ck_alignment_proposal_regulatory_materials_array
        CHECK (jsonb_typeof(regulatory_material_ids) = 'array')
);

CREATE INDEX ix_alignment_proposal_document_created
    ON alignment_proposal (document_id, created_at DESC, id);
CREATE INDEX ix_alignment_proposal_base_revision
    ON alignment_proposal (base_revision_id, created_at DESC);

CREATE TABLE alignment_proposal_adoption (
    proposal_id UUID PRIMARY KEY REFERENCES alignment_proposal(id) ON DELETE RESTRICT,
    revision_id UUID NOT NULL UNIQUE REFERENCES document_revision(id) ON DELETE RESTRICT,
    adopted_by UUID NOT NULL REFERENCES app_user(id),
    adopted_at TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION reject_alignment_proposal_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'alignment proposal rows are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER alignment_proposal_immutable
    BEFORE UPDATE OR DELETE ON alignment_proposal
    FOR EACH ROW EXECUTE FUNCTION reject_alignment_proposal_mutation();

CREATE FUNCTION reject_alignment_proposal_adoption_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'alignment proposal adoption rows are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER alignment_proposal_adoption_immutable
    BEFORE UPDATE OR DELETE ON alignment_proposal_adoption
    FOR EACH ROW EXECUTE FUNCTION reject_alignment_proposal_adoption_mutation();
