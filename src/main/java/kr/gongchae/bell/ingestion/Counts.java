package kr.gongchae.bell.ingestion;

/** closed = existing postings that became CLOSED in this apply; new rows are not counted. */
public record Counts(int totalCount, int received, int inserted, int changed, int closed) {
}
