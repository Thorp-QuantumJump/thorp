CREATE TABLE market_flow_field_summary (
    field_id VARCHAR(64) NOT NULL,
    registry_version VARCHAR(128) NOT NULL,
    dataset_version VARCHAR(128) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    display_order INTEGER NOT NULL,
    narrative TEXT,
    narrative_rule_version VARCHAR(128) NOT NULL,
    narrative_subjects JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL,
    is_example_data BOOLEAN NOT NULL,
    reference_month VARCHAR(7),
    reference_month_held_back_by JSONB NOT NULL DEFAULT '[]'::jsonb,
    last_updated_at TIMESTAMPTZ,
    built_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_market_flow_field_summary
        PRIMARY KEY (field_id, registry_version, dataset_version),
    CONSTRAINT ck_market_flow_field_summary_status
        CHECK (status IN ('READY', 'EXAMPLE', 'EMPTY', 'COLLECTION_FAILED')),
    CONSTRAINT ck_market_flow_field_summary_display_order
        CHECK (display_order BETWEEN 1 AND 7),
    CONSTRAINT ck_market_flow_field_summary_example
        CHECK (NOT is_example_data OR status = 'EXAMPLE')
);

CREATE INDEX idx_market_flow_field_summary_versions
    ON market_flow_field_summary (registry_version, dataset_version, display_order);
