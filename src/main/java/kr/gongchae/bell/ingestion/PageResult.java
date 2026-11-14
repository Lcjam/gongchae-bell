package kr.gongchae.bell.ingestion;

import java.util.SortedSet;

/**
 * One backfill page: sns are every posting number validated and applied from that page, stored before or not
 * (unmodifiable). Callers union them across pages for the month's unique count; counts.inserted is not that count.
 */
public record PageResult(Counts counts, SortedSet<Long> sns) {
}
