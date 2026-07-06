CREATE TABLE IF NOT EXISTS ring_phone_attempts (
    attempt_id varchar(80) NOT NULL PRIMARY KEY,
    status varchar(32) NOT NULL,
    started_at timestamp(6) NOT NULL,
    ended_at timestamp(6) NULL,
    error_code varchar(80) NULL,
    error_message text NULL,
    created_at timestamp(6) NOT NULL,
    updated_at timestamp(6) NOT NULL
);

CREATE INDEX idx_ring_phone_attempts_status_updated ON ring_phone_attempts (status, updated_at);
