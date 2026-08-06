ALTER TABLE model_connection
    DROP CONSTRAINT ck_model_connection_base_url_https;

ALTER TABLE model_connection
    ADD CONSTRAINT ck_model_connection_base_url_scheme
        CHECK (base_url LIKE 'https://%' OR base_url LIKE 'http://%');

CREATE TABLE model_endpoint_rule (
    id UUID PRIMARY KEY,
    host VARCHAR(253) NOT NULL,
    allowed_ports VARCHAR(200) NOT NULL,
    allow_http BOOLEAN NOT NULL DEFAULT FALSE,
    allow_private_addresses BOOLEAN NOT NULL DEFAULT FALSE,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_model_endpoint_rule_host
        CHECK (host = LOWER(host) AND host !~ '[*\\/:]' AND LENGTH(host) BETWEEN 1 AND 253),
    CONSTRAINT ck_model_endpoint_rule_ports
        CHECK (allowed_ports ~ '^[0-9]+(,[0-9]+)*$')
);

CREATE UNIQUE INDEX uk_model_endpoint_rule_host ON model_endpoint_rule (host);
