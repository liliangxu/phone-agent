CREATE TABLE IF NOT EXISTS codex_sessions (
    id varchar(64) NOT NULL PRIMARY KEY,
    title varchar(160) NOT NULL,
    cwd text NOT NULL,
    status varchar(48) NOT NULL,
    tmux_name varchar(160) NOT NULL,
    ttyd_port int NULL,
    ttyd_pid bigint NULL,
    ttyd_url text NULL,
    thread_id varchar(160) NULL,
    jsonl_path text NULL,
    waiting_marker boolean NOT NULL DEFAULT false,
    last_assistant_message mediumtext NULL,
    last_processed_jsonl_size bigint NOT NULL DEFAULT 0,
    last_relevant_event_timestamp varchar(128) NULL,
    initial_prompt_submitted boolean NOT NULL DEFAULT false,
    started_at_epoch_second bigint NOT NULL DEFAULT 0,
    error_message text NULL,
    phone_bridge_error_code varchar(80) NULL,
    phone_bridge_error_message text NULL,
    created_at timestamp(6) NOT NULL,
    updated_at timestamp(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS phone_tasks (
    task_id varchar(80) NOT NULL PRIMARY KEY,
    bridge_id varchar(80) NULL,
    text mediumtext NOT NULL,
    status varchar(64) NOT NULL,
    failure_stage varchar(64) NULL,
    error_message text NULL,
    slot int NULL,
    task_audio_file text NULL,
    recording_file text NULL,
    reply_text mediumtext NULL,
    recording_callback_handled boolean NOT NULL DEFAULT false,
    replaced_task_id varchar(80) NULL,
    created_at timestamp(6) NOT NULL,
    updated_at timestamp(6) NOT NULL
);

CREATE INDEX idx_phone_tasks_bridge_id ON phone_tasks (bridge_id);
CREATE INDEX idx_phone_tasks_status_created ON phone_tasks (status, created_at, task_id);
CREATE INDEX idx_phone_tasks_slot ON phone_tasks (slot);

CREATE TABLE IF NOT EXISTS phone_slots (
    slot int NOT NULL PRIMARY KEY,
    status varchar(64) NOT NULL,
    current_task_id varchar(80) NULL,
    started_task_id varchar(80) NULL,
    updated_at timestamp(6) NOT NULL
);

INSERT INTO phone_slots (slot, status, updated_at)
SELECT 1, 'IDLE', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM phone_slots WHERE slot = 1);
INSERT INTO phone_slots (slot, status, updated_at)
SELECT 2, 'IDLE', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM phone_slots WHERE slot = 2);
INSERT INTO phone_slots (slot, status, updated_at)
SELECT 3, 'IDLE', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM phone_slots WHERE slot = 3);
INSERT INTO phone_slots (slot, status, updated_at)
SELECT 4, 'IDLE', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM phone_slots WHERE slot = 4);
INSERT INTO phone_slots (slot, status, updated_at)
SELECT 5, 'IDLE', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM phone_slots WHERE slot = 5);
INSERT INTO phone_slots (slot, status, updated_at)
SELECT 6, 'IDLE', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM phone_slots WHERE slot = 6);
INSERT INTO phone_slots (slot, status, updated_at)
SELECT 7, 'IDLE', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM phone_slots WHERE slot = 7);
INSERT INTO phone_slots (slot, status, updated_at)
SELECT 8, 'IDLE', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM phone_slots WHERE slot = 8);

CREATE TABLE IF NOT EXISTS codex_phone_bridges (
    bridge_id varchar(80) NOT NULL PRIMARY KEY,
    codex_session_id varchar(64) NOT NULL,
    thread_id varchar(160) NOT NULL,
    waiting_event_key varchar(256) NOT NULL,
    last_assistant_message mediumtext NOT NULL,
    status varchar(64) NOT NULL,
    task_id varchar(80) NULL,
    replaced_task_id varchar(80) NULL,
    slot int NULL,
    reply_text mediumtext NULL,
    error_code varchar(80) NULL,
    error_message text NULL,
    cancelled_at timestamp(6) NULL,
    created_at timestamp(6) NOT NULL,
    updated_at timestamp(6) NOT NULL,
    CONSTRAINT uk_bridge_waiting_event UNIQUE (codex_session_id, thread_id, waiting_event_key)
);

CREATE INDEX idx_bridge_session_updated ON codex_phone_bridges (codex_session_id, updated_at);
CREATE INDEX idx_bridge_status_updated ON codex_phone_bridges (status, updated_at);
CREATE INDEX idx_bridge_task_id ON codex_phone_bridges (task_id);
