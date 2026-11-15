package kr.gongchae.bell.ingestion;

/** Why a list response was not applied. Only {@link #name()} is stored in collect_run.error. */
public enum Failure {
    NON_JSON("FAILED"),
    RESULT_CODE_NOT_OK("FAILED"),
    INVALID_ENVELOPE("FAILED"),
    INVALID_ROW("INCOMPLETE"),
    DUPLICATE_SN("INCOMPLETE"),
    COUNT_MISMATCH("INCOMPLETE"),
    EMPTY_SNAPSHOT("INCOMPLETE"),
    APPLY_ERROR("FAILED");

    private final String result;

    Failure(String result) {
        this.result = result;
    }

    /** collect_run.result for this failure. */
    public String result() {
        return result;
    }
}
