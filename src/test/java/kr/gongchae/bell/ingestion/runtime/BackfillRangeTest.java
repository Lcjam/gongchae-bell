package kr.gongchae.bell.ingestion.runtime;
import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
class BackfillRangeTest {
    @Test void refusesInvalidPeriodAndUnsafeSlotBeforeAnyApiCall() {
        assertThatThrownBy(()->new BackfillRange(LocalDate.of(2026,2,1),LocalDate.of(2026,1,1))).hasMessage("INVALID_BACKFILL_RANGE");
        assertThatThrownBy(()->new CollectorProperties(false,"fixture",500,900,true,"bad/slot",null,null,false))
                .hasMessage("BACKFILL_SLOT_REQUIRED");
    }
}
