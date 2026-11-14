CREATE INDEX collect_run_latest_complete ON collect_run(finished_at DESC)
    WHERE kind = 'REGULAR' AND result = 'COMPLETE';
ALTER TABLE code_name ADD CONSTRAINT code_name_type
    CHECK (type IN ('NCS','REGION','HIRE','EDU'));
