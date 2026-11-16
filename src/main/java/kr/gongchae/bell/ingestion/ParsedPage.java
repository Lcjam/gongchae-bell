package kr.gongchae.bell.ingestion;

import java.time.LocalDate;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A backfill list page that parsed and validated in full but is not written yet, so the caller can reject it
 * (page size, totals across pages, requested dates) before {@link PostingIngestor#upsertPage} changes anything.
 */
public final class ParsedPage {
    private final ListResponseParser.Page page;
    private final SortedSet<Long> sns;

    ParsedPage(ListResponseParser.Page page) {
        this.page = page;
        var set = new TreeSet<Long>();
        page.rows().forEach(row -> set.add(row.sn()));
        this.sns = Collections.unmodifiableSortedSet(set);
    }

    public int totalCount() {
        return page.totalCount();
    }

    /** Every posting number in the page, stored before or not; distinct, so its size is the number of rows. */
    public SortedSet<Long> sns() {
        return sns;
    }

    /** Whether every row starts (pbancBgngYmd) within [from, to]. An empty page does. */
    public boolean beginsWithin(LocalDate from, LocalDate to) {
        return page.rows().stream().allMatch(row -> !row.begin().isBefore(from) && !row.begin().isAfter(to));
    }

    /** The rows starting on or before {@code to}, taken from this same parse; totalCount stays the upstream value. */
    public ParsedPage startingOnOrBefore(LocalDate to) {
        return new ParsedPage(new ListResponseParser.Page(page.totalCount(),
                page.rows().stream().filter(row -> !row.begin().isAfter(to)).toList()));
    }

    ListResponseParser.Page page() {
        return page;
    }
}
