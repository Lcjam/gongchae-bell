# #4 M1 문서 마감 · claude

> 2026-11-15 · 조율: codex(PR #11 M1 문서 마감 라운드) · 작성자: claude(#4 단독 작성자)
> 기준: `007c1dd`(코드 `2d9e263`). 앱 Java·테스트·SQL·Gradle·설정·fixture는 바꾸지 않았다. 실제 API·DB·발송·OCI·DNS도 실행하지 않았다. PR #11은 draft로 두었고 머지하지 않았다. root가 검수 후 머지한다.
> 읽은 것: CLAUDE.md, root `collab/2026-11-15-backfill/agy-start-scan-result.md`·`codex-start-scan-review.md`, root `collab/reviews/pr-11-codex.md`(START_SCAN 최종 절)·`pr-11-agy.md`(§7).

## 커밋

| 커밋 | 내용 | CI |
|---|---|---|
| `f5b93dc6d6d37ca205e19a8047bea83299f42013` | 문서 마감과 실측 증거 15파일(아래) | [43714663957](https://github.com/Lcjam/gongchae-bell/actions/runs/43714663957) test(ARM64)·secrets 성공 |
| (이 문서) | 마감 기록 | 문서만 |

## 바꾼 파일 (`f5b93dc`, 모두 Markdown)

| 파일 | 내용 |
|---|---|
| `README.md` | 수집 실행기 구현과 로컬 개발 DB 최초 적재 36,768건 실측(기록 링크), 알림·운영·공개 배포 미제공. 테스트 절의 "일부 테스트 컨텍스트는 아직 고정하지 않는다" 경고는 Sol 합본(`cdd778e`)으로 공용 테스트 기반이 `collector.*=false`를 고정했으므로 사실에 맞게 고쳤다 |
| `docs/implementation-status.md` | FR-C1: 로컬 개발 DB 실측 완료로 바꾸고 수치는 보고서로 링크했다. 앞선 22,733건은 부분 적재로 정정했다. FR-C2~C8: PR #11 반영. 상세 `DONE` 보존은 fixture·통합 테스트 결과이며 실제 DB 관측이 아니라고 적었다. 공개 출시 행: 실제 적재는 개발 DB만. M2·M3·PRD §7 미완료 문장 추가 |
| `docs/ops/collector.md` | 운영 순서 1에 `initial-003` 완료와 `initial-004` 재실행 0 HTTP를 링크했다. "3년 최초 적재 실측" 게이트는 개발 DB 기준으로 닫고 운영 서버 적재 때 다시 기록한다고 적었다. 예시 명령이 실측 명령과 같다는 점과 사용한 슬롯을 적었다 |
| `docs/sprints/sprint-1.md` (신규) | Sprint 1 리뷰: 목표, 완료(PR #8~#12), 미완료(M2·M3·PRD §7), 데모 링크, KPT. 수치는 링크한 기록에서만 가져왔다. root 검수 전 초안 |
| `collab/2026-11-15-backfill/*.md` 9개 (신규) | root 기록 그대로: `agy-result`, `codex-review`, `claude-correction-plan`, `sol-correction-plan`, `codex-plan-review`, `agy-query-contract`, `implementation-decision`, `agy-start-scan-result`, `codex-start-scan-review` |
| `collab/reviews/pr-11-codex.md`, `pr-11-agy.md` (갱신) | root 기록 그대로. 브랜치의 이전 판과 비교하면 줄 삭제 0, 추가만 있다(codex 11줄: START_SCAN 최종 절, agy 53줄: §7). root 원본은 그대로 두었다 |

포함하지 않은 것: M2 준비 기록(`collab/2026-11-15-m2-readiness/`), 배포 조사, 환경 파일, 실행 로그, raw DB·백업(`.tools/`).

## 검증

- **문서만 바뀌었다**: `git show --name-only f5b93dc`에서 `.md`가 아닌 파일 0개.
- **증거 사본**: root 원본은 CRLF이고 저장소 `.gitattributes`는 `* text=auto eol=lf`다. 작업 트리 사본은 `cmp`로 원본과 바이트가 같다. 커밋된 blob은 원본에서 CR만 지운 것과 SHA-256이 11개 모두 같다(`git show HEAD:<경로>` 대 `tr -d '\r'`).
- **커밋 자체의 README·상태 문서 diff**: `git show f5b93dc -- README.md docs/implementation-status.md`로 의도한 두 파일의 변경이 커밋에 들어 있음을 직접 확인했다. `dc8d016` 때처럼 빠진 파일이 없다. 커밋 뒤 작업 트리 변경도 0이다.
- **링크**: 바꾼 문서 4개의 상대 링크가 모두 브랜치 안 파일을 가리킨다(스크립트 확인).
- **비밀값**: 커밋 전 대상 경로마다 `gitleaks dir --redact` → no leaks. 커밋 뒤 `gitleaks git` `007c1dd..HEAD`와 `origin/main..HEAD` → no leaks. 복사한 11개 파일에는 저장소 `.env`의 키(원문·디코딩·인코딩 표기)와 DB 비밀번호가 0건이다(값 출력 없이 확인). 이메일 주소 형식도 0건이다. `serviceKey=`·`password=` 문자열은 점검 항목 이름과 테스트 전용 `fixture` 키뿐이다.
- 전체 `check`는 다시 돌리지 않았다(문서만 변경). 원격 CI는 기존 workflow대로 돌았다: [43714663957](https://github.com/Lcjam/gongchae-bell/actions/runs/43714663957) `test`(ubuntu-24.04-arm, `check bootJar`)·`secrets` 성공.

## 실측 요약 (근거는 링크한 보고서와 root 검수)

[실행 기록](../2026-11-15-backfill/agy-start-scan-result.md) · [root DB 검수](../2026-11-15-backfill/codex-start-scan-review.md):
- 범위: 2023-11-15~2026-11-15 시작.
- 수신과 반영: raw 36,772, 반영 36,768(미래 시작 4건 306937~306940 제외), 신규 14,035, 월 걸침 13,693.
- 호출: HTTP 37회, 일 예산 43→80. 새 슬롯 재실행은 0회.
- 그 밖: 기존 76회차와 예산 이력 보존. 연도별·코드·품질·오래된 기간 표본은 보고서에 있다.

sn 307032는 실행 전 DB에 없었고, 이번에 상세 없이(`NONE`) 새로 적재됐다. `DONE` 보존은 테스트 경계이며 실제 DB에서 관측한 것이 아니다.

## 남은 범위 (이번 PR로 완료가 아님)

- M2 알림(#6, #7), M3 운영(배포, 모니터링, 백업·복원 리허설, 500 경보 실제 수신), PRD §7 공개 출시 조건.
- 운영 서버 DB 적재, 정기 수집을 켠 운영 관측, 실제 상세 수집, 두 앱 동시 운영, 성능 기준선.
- 원천에 스냅숏 토큰이 없어, 같은 총수를 유지한 조용한 교체는 증명하지 못한다(root 검수에 명시).
- Sprint 1 리뷰의 KPT는 claude 초안이다. root 검수 뒤 확정한다.
