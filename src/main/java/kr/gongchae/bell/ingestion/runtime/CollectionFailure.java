package kr.gongchae.bell.ingestion.runtime;

/** Only these codes, never transport exceptions or response bodies, leave the HTTP boundary. */
public final class CollectionFailure extends RuntimeException {
    public enum Code { BUDGET_EXHAUSTED, HTTP_TIMEOUT, HTTP_CONNECTION, HTTP_STATUS, HTTP_RATE_LIMIT,
        DATABASE_FAILURE, INTERRUPTED, INVALID_DETAIL, INGESTION_FAILED, BACKFILL_COUNT_CHANGED, BACKFILL_PAGE_MISMATCH, BACKFILL_DUPLICATE_SN, BACKFILL_RANGE_MISMATCH }
    private final Code code;
    public CollectionFailure(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}
