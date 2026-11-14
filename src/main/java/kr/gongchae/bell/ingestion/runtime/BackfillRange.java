package kr.gongchae.bell.ingestion.runtime;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public record BackfillRange(LocalDate from, LocalDate to) {
    public BackfillRange {
        if(from==null || to==null || from.isAfter(to)) throw new IllegalArgumentException("INVALID_BACKFILL_RANGE");
    }
    public List<BackfillRange> months() {
        List<BackfillRange> months=new ArrayList<>();
        for(LocalDate start=from;!start.isAfter(to);) {
            LocalDate end=start.withDayOfMonth(start.lengthOfMonth());
            if(end.isAfter(to)) end=to;
            months.add(new BackfillRange(start,end)); start=end.plusDays(1);
        }
        return List.copyOf(months);
    }
}
