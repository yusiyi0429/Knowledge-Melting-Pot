-- V19: remove exploration sessions from the active ledger without destroying evidence lineage.

ALTER TABLE exploration_session
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by UUID REFERENCES app_user(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_exploration_delete_actor CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
    );

CREATE INDEX ix_exploration_active_updated
    ON exploration_session (updated_at DESC, id)
    WHERE deleted_at IS NULL;
