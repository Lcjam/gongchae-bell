package kr.gongchae.bell.ingestion.runtime;
import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
class BackfillRangeTest {
    @Test void splitsInclusiveRollingThreeYearsWithoutGapsIncludingLeapDay() {
        var from=LocalDate.of(2023,11,15); var to=LocalDate.of(2026,11,15);
        var months=new BackfillRange(from,to).months();
        assertThat(months.getFirst().from()).isEqualTo(from);
        assertThat(months.getLast().to()).isEqualTo(to);
        assertThat(months).anySatisfy(month->{assertThat(month.from()).isEqualTo(LocalDate.of(2024,2,1));
            assertThat(month.to()).isEqualTo(LocalDate.of(2024,2,29));});
        long days=0;
        for(int i=0;i<months.size();i++) {
            var month=months.get(i); days+=java.time.temporal.ChronoUnit.DAYS.between(month.from(),month.to())+1;
            if(i>0) assertThat(months.get(i-1).to().plusDays(1)).isEqualTo(month.from());
        }
        assertThat(days).isEqualTo(java.time.temporal.ChronoUnit.DAYS.between(from,to)+1);
    }
    @Test void refusesInvalidPeriodAndUnsafeSlotBeforeAnyApiCall() {
        assertThatThrownBy(()->new BackfillRange(LocalDate.of(2026,2,1),LocalDate.of(2026,1,1))).hasMessage("INVALID_BACKFILL_RANGE");
        assertThatThrownBy(()->new CollectorProperties(false,"fixture",500,900,true,"bad/slot",null,null,false))
                .hasMessage("BACKFILL_SLOT_REQUIRED");
    }
}
