-- M2 alerts (#6). New tables only, so the previous app version keeps working (blue/green).
-- Contract: collab/2026-11-15-m2-readiness/12 + 13 + 14.

CREATE TABLE subscription (
    id uuid PRIMARY KEY,
    channel varchar(10) NOT NULL CHECK (channel IN ('EMAIL','DISCORD')),
    status varchar(10) NOT NULL CHECK (status IN ('PENDING','ACTIVE','DISABLED')),
    email varchar(254),
    webhook_enc bytea,
    webhook_key_id varchar(40),
    dest_hmac bytea NOT NULL,
    conditions jsonb NOT NULL CHECK (jsonb_typeof(conditions) = 'object'),
    notify_new boolean NOT NULL,
    notify_deadline boolean NOT NULL,
    token_version integer NOT NULL DEFAULT 1 CHECK (token_version > 0),
    confirm_token_hash bytea UNIQUE,
    confirm_expires_at timestamptz,
    consent_version varchar(40) NOT NULL,
    consented_at timestamptz NOT NULL,
    age_confirmed boolean NOT NULL CHECK (age_confirmed),
    confirmed_at timestamptz,
    disabled_at timestamptz,
    disabled_reason varchar(30),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (notify_new OR notify_deadline),
    CHECK ((channel = 'EMAIL' AND email IS NOT NULL AND webhook_enc IS NULL)
        OR (channel = 'DISCORD' AND email IS NULL AND webhook_enc IS NOT NULL AND webhook_key_id IS NOT NULL)),
    CHECK (status <> 'ACTIVE' OR confirmed_at IS NOT NULL),
    CHECK (status <> 'DISABLED' OR disabled_at IS NOT NULL)
);
-- One live (PENDING/ACTIVE) subscription per destination; DISABLED rows do not block a new sign-up.
CREATE UNIQUE INDEX subscription_live_destination ON subscription(channel, dest_hmac) WHERE status IN ('PENDING','ACTIVE');
CREATE INDEX subscription_status ON subscription(status, channel);

CREATE TABLE notification (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscription_id uuid NOT NULL REFERENCES subscription(id) ON DELETE CASCADE,
    kind varchar(20) NOT NULL CHECK (kind IN ('EMAIL_CONFIRM','EMAIL_RECOVER','EMAIL_DIGEST','DISCORD_NEW','DISCORD_DEADLINE','DISCORD_TEST')),
    batch_key varchar(64) NOT NULL,
    status varchar(10) NOT NULL CHECK (status IN ('PENDING','SENDING','RETRY','SENT','FAILED','CANCELLED','UNCERTAIN')),
    attempts smallint NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    next_attempt_at timestamptz,
    claimed_at timestamptz,
    created_at timestamptz NOT NULL,
    finished_at timestamptz,
    idempotency_key uuid NOT NULL,
    payload_sha256 bytea,
    last_error varchar(30),
    UNIQUE (subscription_id, kind, batch_key),
    CHECK ((status IN ('SENT','FAILED','CANCELLED','UNCERTAIN')) = (finished_at IS NOT NULL))
);
CREATE INDEX notification_due ON notification(next_attempt_at) WHERE status IN ('PENDING','RETRY');
CREATE INDEX notification_sending ON notification(claimed_at) WHERE status = 'SENDING';
CREATE INDEX notification_finished ON notification(finished_at) WHERE finished_at IS NOT NULL;

-- One row per reserved send attempt, committed with the SEND_TOTAL reservation before the HTTP request.
-- outcome NULL = the result is unknown (the request may have been sent).
CREATE TABLE notification_attempt (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_id bigint NOT NULL REFERENCES notification(id) ON DELETE CASCADE,
    attempt_no smallint NOT NULL CHECK (attempt_no BETWEEN 1 AND 3),
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    outcome varchar(30),
    http_status smallint,
    UNIQUE (notification_id, attempt_no)
);

CREATE TABLE notification_item (
    subscription_id uuid NOT NULL REFERENCES subscription(id) ON DELETE CASCADE,
    sn bigint NOT NULL REFERENCES posting(sn),
    kind varchar(10) NOT NULL CHECK (kind IN ('NEW','DEADLINE')),
    notification_id bigint REFERENCES notification(id) ON DELETE SET NULL,
    PRIMARY KEY (subscription_id, sn, kind)
);

-- Fixed-window counters keyed by HMAC (never the raw IP, address or webhook). window_end drives cleanup.
CREATE TABLE rate_limit (
    scope varchar(15) NOT NULL CHECK (scope IN ('SUBSCRIBE','RESEND','WEBHOOK_TEST','TOKEN_FAIL','SEND_TOTAL')),
    key_hmac bytea NOT NULL,
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL,
    count integer NOT NULL CHECK (count >= 0),
    PRIMARY KEY (scope, key_hmac, window_start)
);
CREATE INDEX rate_limit_window_end ON rate_limit(window_end);
