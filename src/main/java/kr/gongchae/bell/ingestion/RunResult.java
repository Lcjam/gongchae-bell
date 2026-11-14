package kr.gongchae.bell.ingestion;

/** result is COMPLETE, INCOMPLETE or FAILED; failure is null when COMPLETE; counts is null when the body was unreadable. */
public record RunResult(String result, Failure failure, Counts counts) {
}
