CREATE TABLE IF NOT EXISTS inbound_intents (
    intent_id varchar(80) NOT NULL PRIMARY KEY,
    source varchar(64) NOT NULL,
    input_type varchar(32) NOT NULL,
    status varchar(64) NOT NULL,
    input_status varchar(64) NOT NULL,
    failure_stage varchar(64) NULL,
    error_message text NULL,
    recording_file text NULL,
    transcript mediumtext NULL,
    codex_session_id varchar(64) NULL,
    recording_callback_handled boolean NOT NULL DEFAULT false,
    created_at timestamp(6) NOT NULL,
    updated_at timestamp(6) NOT NULL
);

CREATE INDEX idx_inbound_intents_status_updated ON inbound_intents (status, updated_at);
CREATE INDEX idx_inbound_intents_codex_session ON inbound_intents (codex_session_id);
