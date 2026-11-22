# PR23 성공 지표 설명 후속 정정

2026-11-19 root Codex가 PR28 소비자 검수 중 producer의 트랜잭션 경계를 재확인했다. 이전 `pr-23-codex-round2.md`의 'finishAsync 커밋 성공' 표현은 SQL 갱신 성공과 외곽 트랜잭션 완료를 정확히 구별하지 않았다. `DeliveryLedger.finishAsync`는 갱신 여부를 반환하고, 실제 외곽 commit은 `AlertDispatcher.dispatchClaim`의 tx.execute가 끝날 때 이뤄진다.

성공타이머·중복위험 기록은 그 이전에 호출된다. 따라서 SENT UPDATE 후 commit실패 시 이미터가 늘어나는지를 원작성자에게 실제 dispatcher/worker·격리PG·대역HTTP 경로로 재현/필요수정하도록 배정했다. 요청은 `collab/2026-11-19-resume/agy-delivery-metric-commit-request.md`다. 아직 이 문서를 쓸 때 실행 재현/수정 결과는 받지 않았다.

기존 검수 원문과 당시SHA/CI 증거는 보존한다. API 성공응답·SQL 갱신·DB commit·Prometheus 표본의 의미를 구별한다. PR28 소비자 쿼리의 별도 수용과 이 #7 producer 후속을 혼동하지 않는다. 실제 운영은 ALERTS_ENABLED=false이며 사용자 발송은 아직 시작하지 않았다.

## 2026-11-20 결과 확인

작성자 agy가 실제 worker+격리 PG deferred constraint trigger로 원본 실패를 재현하고 PR31(코드94f816e, 최종4367f94)을 제출했다. 성공 지표를 afterCommit로 옮겼으며 원본 실패·수정 후 169개 시험 통과는 `agy-delivery-metric-commit-result.md`에 있다. root는 실제 diff·회귀의 구별력·호출자를 `pr-31-codex.md`에서 검수하고 최종 CI7잡 성공을 직접 조회했다. Claude도 독립 수용했다. 이 문서 초기 시점의 '결과 미수신' 상태는 이제 해결됐다. 당시 원문은 역사 기록으로 보존한다.
