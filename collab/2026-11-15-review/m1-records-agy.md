# M1 합본 L2 보완 · PR #12(`m1-review-records`) 검수 · `agy`

> **작성일**: 2026-11-15 · 읽기 전용 검수(앱·테스트·설정·`.env`·브랜치 수정, 추가 테스트·API 호출·push/merge 없음).

---

## 1. Sol 격리 `m1-integration` L2 보완 (`b740072..fc752ad7e78c776ac14448c42662dd1b8fffa1cd`)

- **판정: 수용 (적절한 최소 보완)**.
- **diff 확인**:
  - `src/test/java/kr/gongchae/bell/IntegrationTestSupport.java`와 `src/test/java/kr/gongchae/bell/catalog/CatalogIntegrationTest.java` 두 파일의 `@SpringBootTest(properties = ...)`에만 `"collector.enabled=false"`, `"collector.backfill=false"`를 추가했고, 프로덕션 코드·설정·새 fixture 테스트 3건(`OfficialFixtureIntegrationTest`)은 변경하지 않았다.
  - `fc752ad` 기준 `@SpringBootTest`·상속 테스트 전체를 대조한 결과, 이 두 기반(및 이를 상속하는 `FoundationIntegrationTest`, `PostingIngestorIntegrationTest`, `CollectorWiringIntegrationTest`, `OfficialFixtureIntegrationTest`)과 `BackfillCommandIntegrationTest`(이미 CLI 인자 `enabled=false, backfill=true` + `@Primary` 대역 사용) 외에 누락된 통합 테스트 컨텍스트가 없다.
  - `@SpringBootTest` 인라인 테스트 속성은 OS 셸 환경변수(`COLLECTOR_ENABLED`, `COLLECTOR_BACKFILL`)보다 우선하므로, 개발 셸에 `.env`를 올린 채 테스트를 실행해도 자동 수집·실 API 호출이 일어나지 않게 하는 Claude L2 위험 보완으로 적절하다(`sol-m1-integration-result.md` 보고 기준 94 tests 통과, `CollectorWiringIntegrationTest` 2건 포함).

---

## 2. `m1-review-records` immutable `4388106f6bd5e0ab324ddb83acabd0539682fd7f` (PR #12) 검수

- **판정: 수용 (원본 기록 완전 일치 · 사실·게이트·보안 기준 충족)**.
- **범위 및 원본 대조**:
  - `main`(`9402015`) 대비 변경 파일은 `collab/2026-11-15-review/` 11개, `collab/reviews/` 9개 총 **20개 collab Markdown 파일(+829/-0)**뿐이며 코드·설정·테스트·스크립트 변경이 없다.
  - 20개 파일 모두 git blob 해시가 원본 root `C:/Users/lcj/orca/projects/my-project`의 완료 기록 파일과 **20/20 바이트 일치**한다.
- **`final.md` 사실·수치·게이트 대조**:
  - **PR heads 및 CI (`gh pr list` / `gh run view` 대조)**:
    - `#9`: `6c9ef7b2fc220728b9553a32e44b29df8124483a`(코드 `aab6d5b`), CI [`43682527088`](https://github.com/Lcjam/gongchae-bell/actions/runs/43682527088) 성공 — 일치.
    - `#10`: `4334af809e3d8ce97205e29dcf9a22d28fcddaa6`, CI [`43656050416`](https://github.com/Lcjam/gongchae-bell/actions/runs/43656050416) 성공 — 일치.
    - `#11`: `56faf0510c30d2b7cecc00a40e2757c2d509128e`, CI [`43684804047`](https://github.com/Lcjam/gongchae-bell/actions/runs/43684804047) 성공 — 일치.
  - **공식 API 4회·합본 94건·미완료 게이트 구분**: API 4회(`HTTP_STATUS` 1회 + `200` 3회, `.env` 무변경, `official-fixtures-sol-review.md` 교차 확인), 합본 head(`f5474a8`, `b740072`, `fc752ad`) 94건 및 외부 API·브라우저·배포 미수행 한정, 남은 게이트(부모 PR 병합 후 저장소 CI, 3년 최초 적재·표본 검수 `FR-C1`, M3 500 경보, M2 알림·운영·공개 출시 조건)가 과장 없이 분리되어 있다.
- **비밀값·개인정보 비노출**:
  - 20개 파일 전체 검색에서 실제 API 키, 토큰 포함 URL, 자격증명, 실제 이메일·개인 이름 등 불필요한 개인정보 노출이 없음을 확인했다.
