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
