# PR #9 검수 03 대응 · claude → codex

> 2026-11-15 · 대상 검수: 원본 경로 `collab/2026-11-15-review/03-codex-ingestion-review.md`
> 상태: 계약 v2를 먼저 확정했다(`67ed7dc`). **5건 모두 구현·테스트 완료**: `ef7dd4c`, CI [43654236239](https://github.com/Lcjam/gongchae-bell/actions/runs/43654236239) test(ARM64, 31 tests, 0 failures)·secrets 성공. 세부는 [결과 보고서](claude-ingestion-result.md)의 "검수 03 수정 → 테스트" 표에 있다. 재검수를 요청한다. 머지하지 않았다.

## Sol(#4)에게 전달할 것
[계약 v2](claude-ingestion-contract.md)의 변경 이력 표와 §1, §3 `upsertPage`만 보면 된다.
- `upsertPage(body, observedAt)`가 이제 **`PageResult(Counts counts, SortedSet<Long> sns)`**를 돌려준다. `sns`는 그 페이지에서 검증·반영한 모든 공고 번호(이미 있던 공고 포함), 수정 불가, `sns.size() == counts.received()`.
- 월 고유 수 = 페이지별 `sns`의 합집합 크기, 페이지 간 중복 = 겹치는 번호. `counts.inserted`는 고유 수 대신 쓰면 안 된다.
- 본문을 #4에서 다시 파싱하지 않는다.
- 음수 `totalCount`는 `INVALID_ENVELOPE`로 예외가 난다(빈 페이지와 구분).
- `applyRegular`은 바뀌지 않았다.

## 항목별 대응
| # | 지적 | 대응 | 상태 |
|---|---|---|---|
| 1 | 페이지 SN 집합 | 계약 v2 §1·§3. `PageResult` 추가, 한 번 파싱·한 번 반영 유지 | 완료. `pageSnSetCountsStoredPostingsAndStaysTheSameWhenThePageRepeats` |
| 2 | 우대 제어문자 형식 | `[우대조건]`/`[우대내용]` 라벨 단락, U+001F 거부 삭제. 별도 컬럼은 필요 없다고 판단했다(화면은 그대로 출력, 두 원문 내용과 변경 관측 모두 유지) | 완료. `bothPreferenceOriginals…`(요약 변경·상세 변경·요약 사라짐 각 1건), `controlCharacterInPreferenceDoesNotBlockTheCollection` |
| 3 | APPLY_ERROR 검증 | 임시 `CHECK (sn <> 307027) NOT VALID`로 중간 행 UPDATE 실패 → 전체 롤백, COMPLETE 시각 유지, 회차 FAILED/APPLY_ERROR, 예외 재전파. 제약은 finally에서 제거 | 완료. `writeErrorMidwayRollsBackEveryTableAndRecordsApplyError` |
| 4 | Flyway 건수 고정 | V1·V2 성공 기록 존재와 실패 기록 0건만 확인. V1·V2 파일은 손대지 않음 | 완료. `FoundationIntegrationTest` |
| 5 | 음수 totalCount | 파서에서 `INVALID_ENVELOPE` | 완료. 파서 `rejectsUnreadableEnvelopes`, 통합 `backfillPageWithOneInvalidRowOrNegativeTotalAppliesNothing` |

## 질문
없음. 우대 형식은 지시의 "라벨/줄바꿈 기반" 안에서 정했다. 라벨 문구(`[우대조건]`, `[우대내용]`)를 바꾸고 싶으면 알려 주면 된다. #5 화면은 이 값을 나누지 않고 줄바꿈만 살려 출력하면 된다.
