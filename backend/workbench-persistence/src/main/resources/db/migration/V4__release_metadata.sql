ALTER TABLE document_revision
    ADD COLUMN revision_note VARCHAR(500) NOT NULL DEFAULT '',
    ADD COLUMN finalized BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN finalized_by UUID REFERENCES app_user(id),
    ADD COLUMN finalized_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_document_revision_finalized
        CHECK (finalized = (finalized_by IS NOT NULL AND finalized_at IS NOT NULL));

ALTER TABLE release_snapshot
    ADD COLUMN coverage VARCHAR(16),
    ADD COLUMN note TEXT,
    ADD COLUMN previous_release_id UUID REFERENCES release_snapshot(id);

UPDATE release_snapshot
SET coverage = CASE WHEN partial THEN 'PARTIAL' ELSE 'FULL' END,
    note = 'Migrated immutable release ' || version;

ALTER TABLE release_snapshot
    ALTER COLUMN coverage SET NOT NULL,
    ALTER COLUMN note SET NOT NULL,
    ADD CONSTRAINT ck_release_coverage CHECK (coverage IN ('PARTIAL', 'FULL'));

ALTER TABLE release_item
    ADD COLUMN disposition VARCHAR(32) NOT NULL DEFAULT 'SELECTED',
    ADD COLUMN source_release_id UUID REFERENCES release_snapshot(id),
    ADD CONSTRAINT ck_release_item_disposition
        CHECK (disposition IN ('SELECTED', 'CARRIED_FORWARD'));

ALTER TABLE release_item
    ALTER COLUMN disposition DROP DEFAULT;
