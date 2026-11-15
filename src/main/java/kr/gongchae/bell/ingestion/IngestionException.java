package kr.gongchae.bell.ingestion;

/** A list response that must not be applied. Nothing has been written when this is thrown. */
public class IngestionException extends RuntimeException {
    private final Failure failure;

    IngestionException(Failure failure, String detail) {
        super(detail == null ? failure.name() : failure.name() + ": " + detail);
        this.failure = failure;
    }

    public Failure failure() {
        return failure;
    }
}
