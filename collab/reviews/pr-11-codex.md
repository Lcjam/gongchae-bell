# PR #11 검수 · codex

- 최종 검수 head: `56faf0510c30d2b7cecc00a40e2757c2d509128e`(코드 `5e2950bd93dc6a8f6b5b1fb1b5aff0dbf6aae9b3`, 이후 보고서만 변경).
- 판정: 지정 head의 수집기 구현 수용. 최신 합본과 agy 독립 최종 리뷰를 확인한 뒤 병합한다.
- 이전 검수·결정: [11-codex-collector-review.md](../2026-11-15-review/11-codex-collector-review.md), [11-review-decisions.md](../2026-11-15-review/11-review-decisions.md), [11-second-review.md](../2026-11-15-review/11-second-review.md).

## 최종 변경 대조

| 경계 | 확인 결과 |
|---|---|
| 실패 페이지 | 파싱·총수·크기·기간·중복 검사를 쓰기 전에 한다. validate는 seen을 바꾸지 않으며 검증·반영·체크포인트 완료 후 집계한다. 기존 공고 무변경 및 거부 페이지의 월 집계 오염 회귀 테스트가 있다. |
| 재개 | 완료 월 행 자체의 SN 스냅숏을 읽는다. upstream 축소 후 재시작 사례에서 이전 시도의 페이지가 섞이지 않는다. |
| 실패 요약 | 명령은 완료 월, 월은 받아들인 페이지의 누적치를 실패 시에도 남긴다. HTTP 오류 및 예상 밖 예외 경로를 테스트한다. |
| 잠금 | 전용 물리 연결을 풀 밖에서 열고 unlock 실패에도 닫는다. 실제 세션 종료·다른 소유자 재획득 및 Hikari 배선 검증이 있다. ADR-0016은 회차 UNIQUE 소유권을 유지하고 보조 잠금의 범위·한계를 명시한다. |
| 상세 상한 | 공고당 실패 3회, 새 요청 시작 30건·10분 제한, 비정기 호출 예산 828에서 정기 몫 72를 원자적으로 남긴다. 예상 밖 상세 예외도 FAILED로 표시하며 DONE 배열은 보존한다. |
| 키 입력 | 설정 경계에서 유효 percent escape만 한 번 디코딩하고 평문 +를 보존한다. 잘못된 escape는 고정 오류다. 원문·Encoding·소문자 hex가 같은 wire 값이 되는 대역 테스트가 있다. 원본 .env를 바꾸지 않았다. |
| 문서 | V3 열·api_attempt·집계 범위가 ERD에 반영됐다. 시간 제한이 새 요청 시작 제한임과 진행 중 요청의 추가 시간이 명시됐다. 실제 공식 응답과 아직 남은 적재·운영 경보 게이트를 구분한다. |

root는 immutable diff·코드·회귀 테스트·운영 문서를 대조했다. 구현자의 로컬 결과는 79 tests/0 failures/errors/skipped이며, 수정 전 코드에서 새 실패 사례 3건이 재현됐다고 보고됐다. root가 그 red 실행을 직접 반복하지는 않았다.

GitHub connector로 [최종 CI 43684804047](https://github.com/Lcjam/gongchae-bell/actions/runs/43684804047)의 `test`(`check bootJar`)·`secrets` 성공을 직접 확인했다. 공식 fixture의 본문·영속 예산 4회·키 비노출은 별도 [Sol 독립 검수](../2026-11-15-review/official-fixtures-sol-review.md)와 대조했다.

## 남은 검증과 한계

최신 #3/#4/#5 합본 테스트 및 실제 3년 적재·표본 검수, M3의 500 경보 실제 수신은 별도다. DB 장애로 실패 기록 자체를 남길 수 없는 경우와 종료 유예를 넘긴 작업은 미완료 기록으로 남을 수 있다. 상세 429 이후 sweep의 다른 공고도 시도하지만 정기 예산과 sweep 상한은 적용된다. PgBouncer transaction pooling은 지원하지 않는다. 이 검수는 공개 배포와 M1 전체 완료를 선언하지 않는다.

## START_SCAN 독립 최종 검수 (2026-11-15 21:17 KST)

- 검수 head: `007c1dd464012446f74dfed4c79c65e00b35163b`; 수정 코드 `2d9e263939f4ce9f27506e775a674d741cd17571`; Sol 통합 테스트 merge `cdd778e8c1e3ef5ca8505d3feb84c7a282641cf9`.
- 판정: **코드 수용, 실제 전체 적재 검증으로 진행**. PR #11은 실제 적재 결과 확인 전까지 draft/미병합을 유지한다. agy §7의 ‘머지 후 운영’ 순서는 이 조율 결정으로 정정한다.
- 근거: immutable 코드·diff·테스트·V4·계약 및 운영 문서를 직접 대조했다. `BackfillCollector`, `CollectionRuns`, `ParsedPage`, 요청 파라미터와 세대 경계를 확인했다. 종료 조건 미전송, 모든 raw 행 검증 후 로컬 상한 선별, raw 총수와 applied SN 집합의 분리, 다른 세대 및 legacy 완료 배제, 첫 미완료 이후 전 페이지 신규 요청, 완료 scan 자체 집합의 0 HTTP 재사용이 구현됐다.
- 거부 페이지가 진행 집계를 바꾸기 전에 차단되고, 재개는 같은 generation의 연속 완료 페이지에 한정된다. V4는 nullable 열 4개만 추가하며 V1~V3/기존 공고·예산 이력을 보존한다. M2는 V5부터다.
- 회귀 사례는 목표 밖 malformed/중복, raw 1001 중 적용 1, 같은 totalCount의 세대 혼합, crash/checkpoint, legacy 동일 기간, 완료 재실행과 공식 하한 fixture를 포함한다. 공식 fixture에서 totalCount만 바꾼 단일 페이지 완결 시험과 원본 실측을 구분한다.
- 독립 리뷰: [agy §7](pr-11-agy.md)도 같은 head를 수용했다. root가 GitHub connector로 [43710583244](https://github.com/Lcjam/gongchae-bell/actions/runs/43710583244)의 `test/check bootJar` 및 `secrets` 성공을 직접 확인했다. 로컬 96 tests/0 failures/errors/skipped와 변이 red 4경로는 구현자 실행 보고이며 root가 중복 실행하지 않았다.

실행 조건은 [구현 결정](../2026-11-15-backfill/implementation-decision.md)을 따른다. 기존 22,733공고/76회차/예산43을 보존하고 고정 기간 2023-11-15~2026-11-15를 새 슬롯에서 적재한다. 실제 raw/applied/DB 수, 월 걸침·목표일 뒤 마감·미래 시작 제외, 품질·오래된 코드, 재실행 0 HTTP를 확인한 뒤 병합한다. 원천은 스냅숏 토큰이 없어 같은 총수의 조용한 교체까지 증명하지 못하는 한계가 명시돼 있다. 공개 배포와 M2/M3 완료는 별도다.
