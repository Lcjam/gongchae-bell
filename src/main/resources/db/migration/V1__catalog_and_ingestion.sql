CREATE TABLE institution (
    code varchar(40) PRIMARY KEY,
    name text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE posting (
    sn bigint PRIMARY KEY,
    inst_code varchar(40) NOT NULL REFERENCES institution(code),
    title text NOT NULL,
    bgng_date date NOT NULL,
    end_date date NOT NULL,
    recrut_se varchar(20),
    recrut_nope_raw text,
    recrut_nope bigint,
    aply_qlfc text,
    pref text,
    scrnprcdr text,
    src_url text,
    src_url_generic boolean NOT NULL DEFAULT false,
    broken_text boolean NOT NULL DEFAULT false,
    status varchar(10) NOT NULL CHECK (status IN ('OPEN','CLOSED','CHECK')),
    ongoing_yn varchar(1) CHECK (ongoing_yn IN ('Y','N')),
    from_backfill boolean NOT NULL,
    content_hash varchar(64) NOT NULL,
    first_seen_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    last_changed_at timestamptz,
    detail_status varchar(10) NOT NULL DEFAULT 'NONE' CHECK (detail_status IN ('NONE','DONE','FAILED')),
    steps jsonb NOT NULL DEFAULT '[]',
    files jsonb NOT NULL DEFAULT '[]',
    detail_collected_at timestamptz
);
CREATE INDEX posting_status_end ON posting(status, end_date, sn);
CREATE INDEX posting_begin ON posting(bgng_date, sn);
CREATE INDEX posting_institution_begin ON posting(inst_code, bgng_date);

CREATE TABLE posting_code (
    sn bigint NOT NULL REFERENCES posting(sn) ON DELETE CASCADE,
    type varchar(10) NOT NULL CHECK (type IN ('NCS','REGION','HIRE','EDU')),
    code varchar(40) NOT NULL,
    PRIMARY KEY (sn, type, code)
);
CREATE INDEX posting_code_search ON posting_code(type, code, sn);

CREATE TABLE code_name (
    type varchar(10) NOT NULL,
    code varchar(40) NOT NULL,
    name text NOT NULL,
    PRIMARY KEY (type, code)
);

CREATE TABLE posting_change (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sn bigint NOT NULL REFERENCES posting(sn),
    observed_at timestamptz NOT NULL,
    changed_fields jsonb NOT NULL
);
CREATE INDEX posting_change_observed ON posting_change(sn, observed_at);

CREATE TABLE collect_run (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind varchar(15) NOT NULL CHECK (kind IN ('BACKFILL','REGULAR','DETAIL','DIGEST','DEADLINE')),
    slot varchar(100) NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    result varchar(12) CHECK (result IN ('COMPLETE','INCOMPLETE','FAILED')),
    total_count integer,
    received integer,
    inserted integer,
    changed integer,
    closed integer,
    error varchar(100),
    UNIQUE (kind, slot)
);

CREATE TABLE api_budget (
    day date PRIMARY KEY,
    count integer NOT NULL CHECK (count >= 0)
);
