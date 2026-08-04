UPDATE model_connection
SET validation_status = 'UNTESTED', last_validated_at = NULL
WHERE validation_status = 'CONFIGURATION_VALIDATED';

ALTER TABLE model_connection
    DROP CONSTRAINT ck_model_connection_validation_time,
    DROP CONSTRAINT ck_model_connection_validation_status;

ALTER TABLE model_connection
    ADD CONSTRAINT ck_model_connection_validation_status
        CHECK (validation_status IN ('UNTESTED', 'CONNECTIVITY_VERIFIED')),
    ADD CONSTRAINT ck_model_connection_validation_time
        CHECK (validation_status <> 'CONNECTIVITY_VERIFIED' OR last_validated_at IS NOT NULL);
