# #17 복원 대장 coverage 검사 구현 결과 · claude

2026-11-20 · 요청: root codex, `collab/2026-11-19-resume/claude-journal-coverage-request.md` · 근거: `collab/2026-11-19-recovery/08-final.md` 구현 1(검사와 CLI)

## 1. 위치·커밋·PR

| 항목 | 값 |
|---|---|
| Orca worktree | `C:/Users/lcj/orca/workspaces/my-project/issue-17-journal-coverage` (단독 작성) |
| 브랜치 | `fix/#17-journal-coverage` |
| 기준 main | `eb903017c4fb256a8ece665da795d7a94f6aeb25` |
| 코드·시험·문서 커밋 | `2d9af29` (`fix: gate restore deletes on bundled journal coverage (Refs #17)`) |
| 최종 HEAD | 이 결과 파일만 더한 문서 커밋(아래 §5) |
| PR | [#34](https://github.com/Lcjam/gongchae-bell/pull/34) (draft, `Refs #17`, 이슈를 닫지 않음) |

## 2. 변경

| 파일 | 내용 |
|---|---|
| `ErasureJournal.java` | 공통 strict 파서 `parse(bytes, code)`. 줄 문법, 정규 소문자 UUID, `Instant.parse`, 끝 줄바꿈 검사는 기존 그대로다. 이 파서가 줄마다 바이트 범위를 함께 돌려준다. 예약 표지 판별 `isMarker`(version 8, IETF variant, 상위 48비트 `67636862-6172`). 한 파일 안의 중복 표지는 `…_DUPLICATE_MARKER`로 거부한다. `readLatest()`는 L을 기존 OS 잠금 아래 한 번에 읽는다. `readBundle(path)`는 B를 잠금과 쓰기 권한 없이 읽어 읽기 전용 마운트에서 동작한다. `verifyCoverage(B, L)`: L 첫 줄이 표지가 아니면(S0/S1) B 전체가 L의 바이트 prefix여야 한다. L 첫 줄이 표지 H이면(S2) B에 H가 있고 B의 H부터 끝까지가 L의 prefix여야 한다. 시각 비교는 없다. `readStrict()`는 표지를 뺀 구독 ID를 돌려준다. 기존 호출자 의미는 그대로다 |
| `ErasureReapplyCommand.java` | `@Value("${alerts.erasure-bundle-log:}")` B를 생성자로 받는다. 순서: `reapply()`에서 B 설정 확인 → L 읽기 → B 읽기 → coverage 확인 → `ERASURE_COVERAGE_VERIFIED rule=…` 로그 → 표지를 뺀 ID로 DELETE → `run()`이 `ERASURE_REAPPLY_COMPLETE`. B가 없으면 `ERASURE_BUNDLE_REQUIRED`. 우회 옵션은 없다. `reapply()`를 직접 부르는 호출자도 같은 게이트를 거친다(게이트가 `reapply()` 안에 있음) |
| 시험 3개 | `ErasureJournalTest` +5(표지 판별, 표지 제외와 중복, B 문법과 읽기 전용, S0/S1, S2). `DeliveryLedgerTest`: 기존 재적용 시험을 B 필수 계약으로 갱신하고 +1(게이트가 DELETE보다 먼저, 표지 미삭제, 멱등). 신규 `ErasureReapplyCommandWiringTest`(조건부 빈 생성, `alerts.erasure-bundle-log` 바인딩, DB 접근 전 실패) |
| 문서 | `backup-restore.md` §7: 원칙 4·5, 분기 A의 B 역할, 공통 재적용과 6.2에 `"$RESTORE_WORK/bundle"`의 `:ro` 마운트와 `--alerts.erasure-bundle-log` 추가, 두 로그 확인. `alerts.md` 복원 절: 명령, 게이트 설명, 실패 코드. ADR-0013 "개정 2026-11-20": 표지, 게이트, 한계 |

**유지한 것:**
- 격리 PG, 권한 분리, 원본 L 보존, 전환 직전 재적용, M1 3조건 분기(runbook에만 있음).
- 기존 `append`의 잠금·fsync·디렉터리 fsync.
- PR30이 고치는 보유기간 절(§2)은 건드리지 않았다.

**하지 않은 것(08의 후속):**
- 초기 표지와 표지를 쓰는 정비 도구, 권한, barrier.
- PC pull, 운영 적용.
- SUB0이나 flags=false 같은 면제 사유.

## 3. 시험 대응표 (요청서 항목)

| 요청 항목 | 시험 |
|---|---|
| S0/S1/S2 정상 | `withoutAHeadMarker…`, `withAHeadMarker…` |
| 역전, B가 L보다 김, L 빈 재생성, 첫 표지 누락 | 같은 두 시험의 실패 단언(`ERASURE_COVERAGE_NOT_PREFIX` / `…_MARKER_ABSENT`) |
| 잘린 줄, 잘못된 날짜(2026-02-30), 비정규 UUID, 중복 표지 | `theBundleUsesTheSameStrictGrammarAndCanBeReadOnly`, `markersAreNever…`, 기존 `tornOrMalformed…` |
| 읽기 전용 B | `theBundleUsesTheSameStrictGrammarAndCanBeReadOnly`(`setWritable(false)`) |
| 표지는 삭제 대상이 아님 | `theRestoreGateRunsBeforeAnyDeleteAndMarkersAreNeverDeleted`(표지 ID를 가진 행이 남음) |
| **유효한 앞부분 + 뒤쪽 오류나 coverage 불일치에서 DB 행 0건 삭제** | 같은 시험(B 없음, B 미포함, L 2행 날짜 오류 → 3행 그대로, 두 로그 모두 없음) |
| 정상 재적용 멱등 | 같은 시험(재실행 `0`) |
| 기존 append·fsync·두 writer 회귀 | 기존 `concurrentAppends…`, `everyAppendForcesTheDirectory…` 통과 |
| 기존 `readStrict`·생성자·시험 호출자 | `ErasureJournalTest`, `DeliveryLedgerTest`, `SubscriptionFlowIntegrationTest`의 `readStrict` 호출이 모두 통과(전체 실행) |

## 4. 검증 (직접 측정 / 미실행 구분)

- **직접 실행(로컬 Windows, Docker Desktop):**
  - 대상 시험 `ErasureJournalTest`(9/9), `DeliveryLedgerTest`(21/21, Testcontainers `postgres:17`), `ErasureReapplyCommandWiringTest`(1/1).
  - 전체 `./gradlew --no-daemon check bootJar`: **26개 묶음, 176개 시험, 실패·오류·건너뜀 0**(`BUILD SUCCESSFUL in 2m 48s`). 기존 169개 + 신규 7개다.
  - `git diff --check` 통과. `gitleaks 8.30.1 dir`(alerts source·test, `docs/ops`, `docs/adr`): no leaks.
- **원격 CI(`2d9af29`):** §5에 적는다.
- **실행하지 않은 것:**
  - 실제 격리 복원 리허설에서 문서 명령을 그대로 실행하는 것(docker run과 `:ro` 마운트, UID 1000으로 읽기).
  - 실제 운영 서버, OCI, 키, provider. 접근하지 않았다.
- 참고: 로컬 시험은 `build.gradle`의 Java 21 toolchain으로 실행됐다(`languageVersion = 21`). 실제 배포 환경은 Linux ARM64 컨테이너이고, 로컬은 Windows다. Windows에서는 디렉터리 fsync를 건너뛴다.

## 5. 원격 CI와 최종 HEAD

- **코드 커밋 `2d9af29`의 원격 CI(직접 조회, `gh pr checks 34`):**
  - `CI` [44716775648](https://github.com/Lcjam/gongchae-bell/actions/runs/44716775648): `test`(2m28s), `deploy-verify`, `backup-verify`, `secrets` 4잡 모두 success.
  - `Monitoring` [44716776058](https://github.com/Lcjam/gongchae-bell/actions/runs/44716776058): `rules`, `compose-integration`, `alert-e2e`(7m58s) 3잡 모두 success.
- **최종 HEAD:** 이 결과 파일만 더한 문서 커밋이다. source·시험·설정 변경은 없다. 그 커밋의 CI와 실제 병합 가능 여부는 root가 최종 확인한다.
- 리뷰는 CLAUDE.md대로 구현하지 않은 두 에이전트가 맡는다. #17은 닫지 않는다(08의 2·3단계가 남음).
