package kr.gongchae.bell.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import kr.gongchae.bell.ingestion.ListResponseParser.PostingRow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ListResponseParserTest {
    private final ListResponseParser parser = new ListResponseParser();

    private PostingRow row(ListResponseParser.Page page, long sn) {
        return page.rows().stream().filter(r -> r.sn() == sn).findFirst().orElseThrow();
    }

    @Test
    void readsRealRowsWithSortedCodesAndPairedNames() {
        var page = parser.parse(ListFixture.sample().body());
        assertThat(page.totalCount()).isEqualTo(497);
        assertThat(page.rows()).hasSize(10);

        PostingRow food = row(page, 307027);
        assertThat(food.instCode()).isEqualTo("C0661");
        assertThat(food.instName()).isEqualTo("식품안전정보원");
        assertThat(food.begin()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(food.end()).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(food.nopeRaw()).isEqualTo("34");
        assertThat(food.nope()).isEqualTo(34L);
        assertThat(food.srcUrl()).isEqualTo("https://foodinfo.career.co.kr/");
        assertThat(food.codes()).containsEntry("NCS", List.of("R600001", "R600002", "R600020", "R600021", "R600025"))
                .containsEntry("EDU", List.of("R7010", "R7030", "R7040", "R7050", "R7060", "R7070"));
        assertThat(food.names().get("NCS")).containsEntry("R600020", "정보통신").containsEntry("R600002", "경영.회계.사무");

        PostingRow hospital = row(page, 307028);
        assertThat(hospital.pref()).isEqualTo("[우대조건]\n모집 분야별 우대조건이 상이하므로 우대내용 확인 요망\n\n[우대내용]\n"
                + "※공통\r\n국가유공자(보훈대상자): 서류 및 면접전형 시 만점의 10% 또는 5% 가산\r\n장애인: 서류 및 면접전형 시 만점의 10% 가산");
        assertThat(hospital.srcUrl()).isEqualTo(
                "https://www.knuch.kr:442/content/04info/11_01.asp?proc_type=view&b_num=5099&rtn_url=%2Fcontent%2F04info%2F11%5F01%2Easp");
    }

    @Test
    void acceptsItemWrappedElementsAndStringResultCode() {
        var flat = parser.parse(ListFixture.sample().body());
        var wrapped = parser.parse(ListFixture.sample().wrapItems().envelope(e -> e.put("resultCode", "200")).body());
        assertThat(wrapped).isEqualTo(flat);
    }

    @Test
    void keepsNonNumericHeadcountRawAndSkipsNamesWhenCountsDisagree() {
        var page = parser.parse(ListFixture.sample()
                .edit(307027, r -> r.put("recrutNope", "0명"))
                .edit(307031, r -> r.put("ncsCdNmLst", "경영.회계.사무"))
                .body());
        assertThat(row(page, 307027).nopeRaw()).isEqualTo("0명");
        assertThat(row(page, 307027).nope()).isNull();
        assertThat(row(page, 307031).codes()).containsEntry("NCS", List.of("R600002", "R600007"));
        assertThat(row(page, 307031).names()).doesNotContainKey("NCS");
    }

    @Test
    void preferenceKeepsOnlyThePresentOriginalsUnderTheirLabels() {
        var noSummary = parser.parse(ListFixture.sample().edit(307028, r -> r.putNull("prefCondCn")).body());
        assertThat(row(noSummary, 307028).pref()).startsWith("[우대내용]\n※공통").doesNotContain("[우대조건]");
        var noDetail = parser.parse(ListFixture.sample().edit(307028, r -> r.remove("prefCn")).body());
        assertThat(row(noDetail, 307028).pref()).isEqualTo("[우대조건]\n모집 분야별 우대조건이 상이하므로 우대내용 확인 요망");
        var none = parser.parse(ListFixture.sample().edit(307028, r -> {
            r.remove("prefCondCn");
            r.remove("prefCn");
        }).body());
        assertThat(row(none, 307028).pref()).isNull();
    }

    @Test
    void controlCharactersInTextAreKeptNotRejected() {
        var page = parser.parse(ListFixture.sample().edit(307032, r -> r.put("prefCn", "가\u001F나")).body());
        assertThat(row(page, 307032).pref()).endsWith("[우대내용]\n가\u001F나");
    }

    @Test
    void rejectsUnreadableEnvelopes() {
        assertFailure("<OpenAPI_ServiceResponse><cmmMsgHeader/></OpenAPI_ServiceResponse>", Failure.NON_JSON);
        String body = ListFixture.sample().body();
        assertFailure(body.substring(0, body.length() / 2), Failure.NON_JSON);
        assertFailure("[]", Failure.NON_JSON);
        assertFailure(ListFixture.sample().envelope(e -> e.put("resultCode", 3)).body(), Failure.RESULT_CODE_NOT_OK);
        assertFailure(ListFixture.sample().envelope(e -> e.remove("resultCode")).body(), Failure.RESULT_CODE_NOT_OK);
        assertFailure(ListFixture.sample().envelope(e -> e.put("totalCount", "497")).body(), Failure.INVALID_ENVELOPE);
        assertFailure(ListFixture.sample().envelope(e -> e.put("totalCount", -1)).body(), Failure.INVALID_ENVELOPE);
        assertFailure(ListFixture.sample().envelope(e -> e.putObject("result")).body(), Failure.INVALID_ENVELOPE);
    }

    @Test
    void rejectsTheWholeResponseForOneInvalidRow() {
        Map<String, Consumer<ObjectNode>> invalid = Map.ofEntries(
                Map.entry("missing sn", r -> r.remove("recrutPblntSn")),
                Map.entry("text sn", r -> r.put("recrutPblntSn", "A307032")),
                Map.entry("blank institution", r -> r.put("pblntInstCd", " ")),
                Map.entry("blank name", r -> r.put("instNm", "")),
                Map.entry("missing title", r -> r.remove("recrutPbancTtl")),
                Map.entry("dashed date", r -> r.put("pbancEndYmd", "2026-11-20")),
                Map.entry("nine digit date", r -> r.put("pbancEndYmd", "202611201")),
                Map.entry("impossible date", r -> r.put("pbancBgngYmd", "20261131")),
                Map.entry("ongoing flag", r -> r.put("ongoingYn", "X")),
                Map.entry("long recruit type", r -> r.put("recrutSe", "R".repeat(21))),
                Map.entry("long code", r -> r.put("ncsCdLst", "R".repeat(41))),
                Map.entry("object text", r -> r.putObject("aplyQlfcCn")));
        invalid.forEach((name, change) -> assertThatThrownBy(() -> parser.parse(ListFixture.sample().edit(307032, change).body()))
                .as(name).isInstanceOfSatisfying(IngestionException.class, e -> assertThat(e.failure()).isEqualTo(Failure.INVALID_ROW)));
    }

    @Test
    void rejectsDuplicateSn() {
        var fixture = ListFixture.sample();
        fixture.rows().add(fixture.row(307032).deepCopy());
        assertFailure(fixture.body(), Failure.DUPLICATE_SN);
    }

    private void assertFailure(String body, Failure expected) {
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOfSatisfying(IngestionException.class, e -> assertThat(e.failure()).isEqualTo(expected));
    }
}
