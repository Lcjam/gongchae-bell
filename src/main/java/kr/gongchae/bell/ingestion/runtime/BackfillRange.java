package kr.gongchae.bell.ingestion.runtime;

import java.time.LocalDate;

/** The initial load's target: postings whose start date (pbancBgngYmd) is within [from, to], both inclusive. */
public record BackfillRange(LocalDate from, LocalDate to) {
    public BackfillRange {
        if(from==null || to==null || from.isAfter(to)) throw new IllegalArgumentException("INVALID_BACKFILL_RANGE");
    }
}
