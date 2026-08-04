-- AssetStatus.BLOCKED: an asset that cannot be generated yet (for example an
-- EVALUATION_SET without a READY LABELED_HOLDOUT binding). BLOCKED is a real
-- terminal-ish state distinct from FAILED: it is not a fabrication and it
-- naturally blocks the release pre-check until the prerequisite exists.
ALTER TABLE asset DROP CONSTRAINT ck_asset_status;
ALTER TABLE asset ADD CONSTRAINT ck_asset_status CHECK (status IN (
    'PENDING', 'GENERATING', 'READY', 'FAILED', 'SUPERSEDED', 'BLOCKED'
));
