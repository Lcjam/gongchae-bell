-- Initial load reads every page of list?pbancBgngYmd=<from> (no pbancEndYmd) and applies only rows starting by <to>.
-- Rows written before this version (strategy NULL) are the monthly queries and are never reused by START_SCAN.
ALTER TABLE collect_run
    ADD COLUMN strategy varchar(20) CHECK (strategy IN ('START_SCAN')),
    ADD COLUMN scan_generation bigint,
    ADD COLUMN applied_count integer CHECK (applied_count >= 0),
    ADD COLUMN applied_sns jsonb CHECK (jsonb_typeof(applied_sns) = 'array');
