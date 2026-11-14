ALTER TABLE collect_run
    ADD COLUMN period_from date,
    ADD COLUMN period_to date,
    ADD COLUMN range_from date,
    ADD COLUMN range_to date,
    ADD COLUMN page_no integer CHECK (page_no > 0),
    ADD COLUMN unique_count integer CHECK (unique_count >= 0),
    ADD COLUMN received_sns jsonb CHECK (jsonb_typeof(received_sns) = 'array');
CREATE INDEX collect_run_backfill_progress ON collect_run(range_from, range_to, page_no)
    WHERE kind='BACKFILL' AND result='COMPLETE';

CREATE TABLE api_attempt (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    day date NOT NULL REFERENCES api_budget(day),
    kind varchar(10) NOT NULL CHECK (kind IN ('REGULAR','BACKFILL','DETAIL')),
    attempt_no integer NOT NULL CHECK (attempt_no BETWEEN 1 AND 3),
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    outcome varchar(30) CHECK (outcome IN ('SUCCESS','HTTP_TIMEOUT','HTTP_CONNECTION','HTTP_STATUS','HTTP_RATE_LIMIT','INTERRUPTED'))
);
CREATE INDEX api_attempt_day ON api_attempt(day, kind);
