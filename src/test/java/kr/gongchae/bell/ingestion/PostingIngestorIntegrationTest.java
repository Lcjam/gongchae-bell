package kr.gongchae.bell.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.gongchae.bell.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PostingIngestorIntegrationTest extends IntegrationTestSupport {
    /** 2026-11-14 22:22 KST, when the fixtures were fetched. */
    static final Instant T0 = Instant.parse("2026-11-14T13:22:00Z");
    static final Instant T1 = T0.plusSeconds(3600);
    static final Instant T2 = T0.plusSeconds(7200);

    @Autowired PostingIngestor ingestor;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.sql("TRUNCATE posting_change, posting_code, posting, code_name, institution, collect_run RESTART IDENTITY CASCADE")
                .update();
    }

    @Test
    void completeSnapshotStoresRealRowsAndFinishesTheRun() {
        long id = run();
        RunResult result = ingestor.applyRegular(id, ListFixture.sample().complete().body(), T0);

        assertThat(result).isEqualTo(new RunResult("COMPLETE", null, new Counts(10, 10, 10, 0, 0)));
        assertThat(runRow(id)).containsEntry("result", "COMPLETE").containsEntry("error", null)
                .containsEntry("total_count", 10).containsEntry("received", 10).containsEntry("inserted", 10)
                .containsEntry("changed", 0).containsEntry("closed", 0);
        assertThat(freshness()).isNotNull();
        Map<String, Object> food = posting(307027);
        assertThat(food).containsEntry("inst_code", "C0661").containsEntry("status", "OPEN").containsEntry("ongoing_yn", "Y")
                .containsEntry("from_backfill", false).containsEntry("recrut_nope", 34L).containsEntry("recrut_nope_raw", "34")
                .containsEntry("broken_text", false).containsEntry("detail_status", "NONE").containsEntry("last_changed_at", null);
        assertThat(((Timestamp) food.get("first_seen_at")).toInstant()).isEqualTo(T0);
        assertThat(jdbc.sql("SELECT code FROM posting_code WHERE sn = 307027 AND type = 'NCS' ORDER BY code").query(String.class).list())
                .containsExactly("R600001", "R600002", "R600020", "R600021", "R600025");
        assertThat(jdbc.sql("SELECT name FROM code_name WHERE type = 'NCS' AND code = 'R600020'").query(String.class).single())
                .isEqualTo("정보통신");
        assertThat(jdbc.sql("SELECT name FROM institution WHERE code = 'C0008'").query(String.class).single()).isEqualTo("경북대학교병원");
        assertThat(count("posting WHERE broken_text")).isZero();
    }

    @Test
    void sameInputTwiceRecordsNoChange() {
        regular(ListFixture.sample().complete(), T0);
        RunResult second = regular(ListFixture.sample().complete(), T1);

        assertThat(second.counts()).isEqualTo(new Counts(10, 10, 0, 0, 0));
        assertThat(count("posting_change")).isZero();
        assertThat(((Timestamp) posting(307032).get("last_seen_at")).toInstant()).isEqualTo(T1);
    }

    @Test
    void decimalDayAndCodeOrderAreNotChangesButADeadlineChangeIsExactlyOne() {
        regular(ListFixture.sample().complete(), T0);
        RunResult cosmetic = regular(ListFixture.sample().complete()
                .edit(307032, r -> r.put("decimalDay", 99))
                .edit(307031, r -> {
                    r.put("ncsCdLst", "R600007,R600002");
                    r.put("ncsCdNmLst", "사회복지.종교,경영.회계.사무");
                }), T1);
        assertThat(cosmetic.counts().changed()).isZero();
        assertThat(count("posting_change")).isZero();

        RunResult deadline = regular(ListFixture.sample().complete().edit(307032, r -> r.put("pbancEndYmd", "20261121")), T2);
        assertThat(deadline.counts().changed()).isEqualTo(1);
        assertThat(changes()).containsExactly(Map.of("sn", 307032L, "observed_at", T2,
                "changed_fields", json("{\"end_date\":{\"old\":\"2026-11-20\",\"new\":\"2026-11-21\"}}")));
        assertThat(((Timestamp) posting(307032).get("last_changed_at")).toInstant()).isEqualTo(T2);
    }

    @Test
    void bothPreferenceOriginalsAreKeptAndEitherChangeIsObserved() {
        regular(ListFixture.sample().complete(), T0);
        String summary = "모집 분야별 우대조건이 상이하므로 우대내용 확인 요망";
        String original = (String) posting(307028).get("pref");
        assertThat(original).startsWith("[우대조건]\n" + summary + "\n\n[우대내용]\n※공통").doesNotContain("\u001F");
        String hash = (String) posting(307028).get("content_hash");

        regular(ListFixture.sample().complete().edit(307028, r -> r.put("prefCondCn", "우대조건 요약 변경")), T1);
        assertThat(posting(307028).get("content_hash")).isNotEqualTo(hash);
        assertThat(changes()).hasSize(1);
        assertThat(changes().getFirst().get("changed_fields")).isEqualTo(json(ListResponseParser.JSON.writeValueAsString(
                Map.of("pref", Map.of("old", original, "new", original.replace(summary, "우대조건 요약 변경"))))));

        regular(ListFixture.sample().complete().edit(307028, r -> {
            r.put("prefCondCn", "우대조건 요약 변경");
            r.put("prefCn", "우대내용 변경");
        }), T2);
        assertThat(changes()).hasSize(2);
        assertThat(posting(307028).get("pref")).isEqualTo("[우대조건]\n우대조건 요약 변경\n\n[우대내용]\n우대내용 변경");

        regular(ListFixture.sample().complete().edit(307028, r -> {
            r.remove("prefCondCn");
            r.put("prefCn", "우대내용 변경");
        }), T2.plusSeconds(3600));
        assertThat(changes()).as("summary disappearing is a change").hasSize(3);
    }

    @Test
    void controlCharacterInPreferenceDoesNotBlockTheCollection() {
        RunResult result = ingestor.applyRegular(run(),
                ListFixture.sample().complete().edit(307032, r -> r.put("prefCn", "가\u001F나")).body(), T0);

        assertThat(result.result()).isEqualTo("COMPLETE");
        assertThat(count("posting")).isEqualTo(10);
        assertThat((String) posting(307032).get("pref")).endsWith("[우대내용]\n가\u001F나");
    }

    @Test
    void writeErrorMidwayRollsBackEveryTableAndRecordsApplyError() {
        regular(ListFixture.sample().complete(), T0);
        List<List<Map<String, Object>>> before = dump();
        OffsetDateTime lastComplete = freshness();
        long id = run();
        // Rows are written in sn order: 307021 and 307023 change institution, posting, change log and codes before 307027 fails.
        String body = ListFixture.sample().complete()
                .edit(307021, r -> {
                    r.put("pbancEndYmd", "20261201");
                    r.put("instNm", "한국나노기술원(변경)");
                })
                .edit(307023, r -> {
                    r.put("ncsCdLst", "R600015");
                    r.put("ncsCdNmLst", "기계");
                })
                .edit(307032, r -> r.put("pbancEndYmd", "20261202"))
                .body();
        jdbc.sql("ALTER TABLE posting ADD CONSTRAINT test_reject_307027 CHECK (sn <> 307027) NOT VALID").update();
        try {
            assertThatThrownBy(() -> ingestor.applyRegular(id, body, T1)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.sql("ALTER TABLE posting DROP CONSTRAINT test_reject_307027").update();
        }

        assertThat(dump()).isEqualTo(before);
        assertThat(freshness()).isEqualTo(lastComplete);
        assertThat(runRow(id)).containsEntry("result", "FAILED").containsEntry("error", "APPLY_ERROR")
                .containsEntry("total_count", 10).containsEntry("received", 10).containsEntry("inserted", 0);
    }

    @Test
    void unusableResponsesChangeNoPostingDataAndKeepTheLastCompleteTime() {
        regular(ListFixture.sample().complete(), T0);
        List<List<Map<String, Object>>> before = dump();
        OffsetDateTime lastComplete = freshness();
        String body = ListFixture.sample().complete().body();
        var duplicate = ListFixture.sample().edit(307031, r -> r.put("pbancEndYmd", "20261201"));
        duplicate.rows().add(duplicate.row(307032).deepCopy());
        duplicate.complete();

        var cases = new LinkedHashMap<String, Object[]>();
        // Synthetic: gateway-level errors may not be JSON; no real one has been captured yet.
        cases.put("gateway xml", new Object[]{"<OpenAPI_ServiceResponse><cmmMsgHeader/></OpenAPI_ServiceResponse>", Failure.NON_JSON});
        cases.put("cut body", new Object[]{body.substring(0, body.length() / 2), Failure.NON_JSON});
        cases.put("error code", new Object[]{ListFixture.sample().complete().envelope(e -> e.put("resultCode", 22)).body(),
                Failure.RESULT_CODE_NOT_OK});
        cases.put("bad envelope", new Object[]{ListFixture.sample().envelope(e -> e.put("totalCount", "10")).body(),
                Failure.INVALID_ENVELOPE});
        cases.put("short list", new Object[]{ListFixture.sample().without(307021).body(), Failure.COUNT_MISMATCH});
        cases.put("empty", new Object[]{ListFixture.sample().envelope(e -> {
            e.putArray("result");
            e.put("totalCount", 0);
        }).body(), Failure.EMPTY_SNAPSHOT});
        cases.put("duplicate sn", new Object[]{duplicate.body(), Failure.DUPLICATE_SN});
        cases.put("invalid row", new Object[]{ListFixture.sample().complete()
                .edit(307031, r -> r.put("pbancEndYmd", "20261201"))
                .edit(307032, r -> r.put("pbancEndYmd", "2026-11-20")).body(), Failure.INVALID_ROW});

        cases.forEach((name, c) -> {
            long id = run();
            Failure expected = (Failure) c[1];
            RunResult result = ingestor.applyRegular(id, (String) c[0], T1);
            assertThat(result.failure()).as(name).isEqualTo(expected);
            assertThat(result.result()).as(name).isEqualTo(expected.result());
            assertThat(runRow(id)).as(name).containsEntry("result", expected.result()).containsEntry("error", expected.name());
            assertThat(dump()).as(name).isEqualTo(before);
            assertThat(freshness()).as(name).isEqualTo(lastComplete);
        });
        assertThat(jdbc.sql("SELECT total_count, received FROM collect_run WHERE error = 'COUNT_MISMATCH'").query().singleRow())
                .containsEntry("total_count", 497).containsEntry("received", 9);
    }

    @Test
    void deadlineUsesTheKoreanDate() {
        regular(ListFixture.sample().complete(), Instant.parse("2026-11-19T15:30:00Z")); // 2026-11-20 00:30 KST

        assertThat(status(307022)).isEqualTo("CLOSED"); // ends 11-18
        assertThat(status(307028)).isEqualTo("CLOSED"); // ends 11-19: would stay OPEN with the UTC date
        assertThat(status(307032)).isEqualTo("OPEN"); // ends 11-20: D-0
    }

    @Test
    void missingPostingIsCheckedAndOnlyTheDeadlineOrOngoingFlagClosesIt() {
        regular(ListFixture.sample().complete(), T0);
        RunResult missing = regular(ListFixture.sample().without(307021).complete(), T1);
        assertThat(status(307021)).isEqualTo("CHECK");
        assertThat(missing.counts().closed()).isZero();

        regular(ListFixture.sample().complete(), T2);
        assertThat(status(307021)).isEqualTo("OPEN");

        RunResult flag = regular(ListFixture.sample().complete().edit(307023, r -> r.put("ongoingYn", "N")), T2.plusSeconds(3600));
        assertThat(status(307023)).isEqualTo("CLOSED");
        assertThat(flag.counts().closed()).isEqualTo(1);

        Instant afterDeadline = Instant.parse("2026-11-18T15:30:00Z"); // 2026-11-19 00:30 KST; 307022 ended 11-18
        RunResult closed = regular(ListFixture.sample().without(307022).complete(), afterDeadline);
        assertThat(status(307022)).isEqualTo("CLOSED");
        assertThat(status(307028)).isEqualTo("OPEN"); // ends 11-19 = today
        assertThat(closed.counts().closed()).isEqualTo(1);

        regular(ListFixture.sample().complete().edit(307022, r -> r.put("pbancEndYmd", "20261130")), afterDeadline.plusSeconds(3600));
        assertThat(status(307022)).as("deadline extended").isEqualTo("OPEN");
    }

    @Test
    void sourceUrlIsStoredVerbatimAndGenericOnlyForFrontPagesOrSameInstitutionReuse() {
        var sample = ListFixture.sample();
        regular(sample.complete(), T0);

        assertThat(jdbc.sql("SELECT sn FROM posting WHERE src_url_generic ORDER BY sn").query(Long.class).list())
                .containsExactly(307022L, 307027L, 307029L, 307030L, 307031L, 307032L);
        assertThat(posting(307028).get("src_url")).isEqualTo(sample.row(307028).path("srcUrl").asString());

        regular(ListFixture.sample().without(307022).complete(), T1);
        assertThat(posting(307032).get("src_url_generic")).as("kept while the URL is unchanged").isEqualTo(true);
    }

    @Test
    void sameUrlAcrossInstitutionsIsNotGenericAndSchemeLessUrlsAreNotJudged() {
        String url = ListFixture.sample().row(307021).path("srcUrl").asString();
        regular(ListFixture.sample().complete()
                .edit(307023, r -> r.put("srcUrl", url))
                .edit(307026, r -> r.put("srcUrl", "www.kohom.or.kr")), T0);

        assertThat(posting(307021).get("src_url_generic")).isEqualTo(false);
        assertThat(posting(307023).get("src_url_generic")).isEqualTo(false);
        assertThat(posting(307026)).containsEntry("src_url", "www.kohom.or.kr").containsEntry("src_url_generic", false);
    }

    @Test
    void suspectedBrokenTextIsFlaggedWithoutRewritingIt() {
        var quality = ListFixture.quality();
        ingestor.upsertPage(quality.body(), T0);

        assertThat(posting(307009).get("broken_text")).isEqualTo(true);
        assertThat(posting(306968).get("broken_text")).isEqualTo(true);
        assertThat(posting(306968).get("aply_qlfc")).isEqualTo(quality.row(306968).path("aplyQlfcCn").asString());
        assertThat((String) posting(307009).get("pref")).endsWith(quality.row(307009).path("prefCn").asString());
    }

    @Test
    void listUpdatesNeverTouchDetailColumns() {
        regular(ListFixture.sample().complete(), T0);
        jdbc.sql("""
                UPDATE posting SET steps = '[{"name":"서류전형"}]', files = '[{"name":"공고문.pdf"}]', detail_status = 'DONE',
                    detail_collected_at = now() WHERE sn = 307032""").update();
        String detailQuery = "SELECT steps::text, files::text, detail_status, detail_collected_at FROM posting WHERE sn = 307032";
        Map<String, Object> before = jdbc.sql(detailQuery).query().singleRow();

        regular(ListFixture.sample().complete().edit(307032, r -> r.put("pbancEndYmd", "20261121")), T1);
        assertThat(jdbc.sql(detailQuery).query().singleRow()).isEqualTo(before);
    }

    @Test
    void backfillPageAddsRowsWithoutJudgingAbsentOnes() {
        regular(ListFixture.sample().complete(), T0);
        var page = ListFixture.sample().only(307032, 307021).edit(307021, r -> r.put("recrutPblntSn", 999001));

        PageResult result = ingestor.upsertPage(page.body(), T1);
        assertThat(result.counts()).isEqualTo(new Counts(497, 2, 1, 0, 0));
        assertThat(result.sns()).containsExactly(307032L, 999001L);
        assertThat(posting(999001)).containsEntry("from_backfill", true).containsEntry("status", "OPEN");
        assertThat(posting(307032).get("from_backfill")).isEqualTo(false);
        assertThat(count("posting WHERE status <> 'OPEN'")).isZero();
        PageResult empty = ingestor.upsertPage(ListFixture.sample().envelope(e -> {
            e.putArray("result");
            e.put("totalCount", 0);
        }).body(), T1);
        assertThat(empty.counts()).isEqualTo(new Counts(0, 0, 0, 0, 0));
        assertThat(empty.sns()).isEmpty();
    }

    @Test
    void pageSnSetCountsStoredPostingsAndStaysTheSameWhenThePageRepeats() {
        regular(ListFixture.sample().complete(), T0);
        String body = ListFixture.sample().only(307032, 307021, 307027).edit(307027, r -> r.put("recrutPblntSn", 999003)).body();

        PageResult first = ingestor.upsertPage(body, T1);
        PageResult again = ingestor.upsertPage(body, T2);

        assertThat(first.sns()).containsExactly(307021L, 307032L, 999003L);
        assertThat(first.counts().received()).isEqualTo(first.sns().size());
        assertThat(first.counts().inserted()).as("only the new one").isEqualTo(1);
        assertThat(again.sns()).isEqualTo(first.sns());
        assertThat(again.counts().inserted()).as("all stored now, yet the set is unchanged").isZero();
        assertThatThrownBy(() -> first.sns().add(1L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void backfillPageWithOneInvalidRowOrNegativeTotalAppliesNothing() {
        var page = ListFixture.sample().edit(307032, r -> r.put("pbancEndYmd", "2026-11-20"));
        assertThatThrownBy(() -> ingestor.upsertPage(page.body(), T0))
                .isInstanceOfSatisfying(IngestionException.class, e -> assertThat(e.failure()).isEqualTo(Failure.INVALID_ROW));

        var negative = ListFixture.sample().envelope(e -> e.put("totalCount", -1));
        assertThatThrownBy(() -> ingestor.upsertPage(negative.body(), T0))
                .isInstanceOfSatisfying(IngestionException.class, e -> assertThat(e.failure()).isEqualTo(Failure.INVALID_ENVELOPE));

        assertThat(count("posting")).isZero();
        assertThat(count("institution")).isZero();
    }

    @Test
    void finishedRunAndCallerTransactionAreRejected() {
        long id = run();
        jdbc.sql("UPDATE collect_run SET result = 'FAILED', finished_at = now() WHERE id = :id").param("id", id).update();
        String body = ListFixture.sample().complete().body();

        assertThatThrownBy(() -> ingestor.applyRegular(id, body, T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(s -> ingestor.upsertPage(body, T0))).isInstanceOf(IllegalStateException.class);
        assertThat(count("posting")).isZero();
    }

    @Test
    void postingInsertedConcurrentlyByAnotherTransactionIsUpdatedAsExisting() throws Exception {
        jdbc.sql("INSERT INTO institution(code, name) VALUES ('C0008', '경북대학교병원')").update();
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var other = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.sql("""
                        INSERT INTO posting(sn, inst_code, title, bgng_date, end_date, status, from_backfill, content_hash,
                            first_seen_at, last_seen_at)
                        VALUES (307032, 'C0008', '먼저 들어온 제목', DATE '2026-11-13', DATE '2026-11-20', 'OPEN', false, 'other',
                            now(), now())""").update();
                inserted.countDown();
                await(release);
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var page = executor.submit(() -> ingestor.upsertPage(ListFixture.sample().body(), T0));
            waitForLockWaiter();
            release.countDown();
            other.get(10, TimeUnit.SECONDS);

            PageResult result = page.get(30, TimeUnit.SECONDS);
            assertThat(result.counts()).isEqualTo(new Counts(497, 10, 9, 1, 0));
            assertThat(result.sns()).hasSize(10).contains(307032L);
        }
        assertThat(posting(307032)).containsEntry("title", "2026년 의료요원(진료교수, 진료의사) 28차 초빙(긴급) 공고")
                .containsEntry("from_backfill", false);
        assertThat(changes()).hasSize(1);
        assertThat(changes().getFirst().get("changed_fields").toString()).contains("먼저 들어온 제목");
    }

    @Test
    void regularRunAndBackfillPageOverlapWithoutDeadlock() throws Exception {
        regular(ListFixture.sample().complete(), T0);
        long id = run();
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var regular = executor.submit(() -> {
                start.await();
                return ingestor.applyRegular(id, ListFixture.sample().complete()
                        .edit(307021, r -> r.put("pbancEndYmd", "20261205")).body(), T1);
            });
            var backfill = executor.submit(() -> {
                start.await();
                return ingestor.upsertPage(ListFixture.sample()
                        .edit(307032, r -> r.put("pbancEndYmd", "20261206"))
                        .edit(307027, r -> r.put("recrutPblntSn", 999002)).body(), T1);
            });
            assertThat(regular.get(30, TimeUnit.SECONDS).result()).isEqualTo("COMPLETE");
            assertThat(backfill.get(30, TimeUnit.SECONDS).counts().received()).isEqualTo(10);
        }
        assertThat(count("posting")).isEqualTo(11);
    }

    private long run() {
        return jdbc.sql("INSERT INTO collect_run(kind, slot, started_at) VALUES ('REGULAR', :slot, now()) RETURNING id")
                .param("slot", "test-" + UUID.randomUUID()).query(Long.class).single();
    }

    private RunResult regular(ListFixture fixture, Instant at) {
        RunResult result = ingestor.applyRegular(run(), fixture.body(), at);
        assertThat(result.result()).isEqualTo("COMPLETE");
        return result;
    }

    private Map<String, Object> runRow(long id) {
        return jdbc.sql("SELECT result, error, total_count, received, inserted, changed, closed FROM collect_run WHERE id = :id")
                .param("id", id).query().singleRow();
    }

    private OffsetDateTime freshness() {
        return jdbc.sql("SELECT max(finished_at) FROM collect_run WHERE kind = 'REGULAR' AND result = 'COMPLETE'")
                .query(OffsetDateTime.class).single();
    }

    private Map<String, Object> posting(long sn) {
        return jdbc.sql("SELECT * FROM posting WHERE sn = :sn").param("sn", sn).query().singleRow();
    }

    private String status(long sn) {
        return (String) posting(sn).get("status");
    }

    private long count(String fromWhere) {
        return jdbc.sql("SELECT count(*) FROM " + fromWhere).query(Long.class).single();
    }

    private List<Map<String, Object>> changes() {
        var rows = new ArrayList<Map<String, Object>>();
        jdbc.sql("SELECT sn, observed_at, changed_fields::text AS fields FROM posting_change ORDER BY id").query(rs -> {
            rows.add(Map.of("sn", rs.getLong("sn"), "observed_at", rs.getTimestamp("observed_at").toInstant(),
                    "changed_fields", json(rs.getString("fields"))));
        });
        return rows;
    }

    private List<List<Map<String, Object>>> dump() {
        return List.of(
                jdbc.sql("SELECT * FROM posting ORDER BY sn").query().listOfRows(),
                jdbc.sql("SELECT * FROM posting_code ORDER BY sn, type, code").query().listOfRows(),
                jdbc.sql("SELECT * FROM posting_change ORDER BY id").query().listOfRows(),
                jdbc.sql("SELECT * FROM institution ORDER BY code").query().listOfRows(),
                jdbc.sql("SELECT * FROM code_name ORDER BY type, code").query().listOfRows());
    }

    private static Object json(String text) {
        return ListResponseParser.JSON.readTree(text);
    }

    private void waitForLockWaiter() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (count("pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()") > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("upsertPage never waited on the other transaction's insert");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
