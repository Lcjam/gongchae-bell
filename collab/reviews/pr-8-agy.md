# PR #8 리뷰 (이슈 #2: 실행 기반 및 빈 상태) · agy

- **대상**: PR #8 (이슈 #2 실행 기반·초기 스키마·빈 상태 화면·CI 구성)
- **참조 문서**: [`collab/2026-11-14-implementation/02-agy-foundation-review.md`](../2026-11-14-implementation/02-agy-foundation-review.md), [`collab/2026-11-14-implementation/03-foundation-validation.md`](../2026-11-14-implementation/03-foundation-validation.md)
- **판정**: **Approve (머지 가능 — 필수 및 권장 지적 사항 모두 해결 확인)**

---

## 1. 사전 리뷰 지적 사항 반영 확인

| 구분 | 항목 | 확인 결과 | 근거 파일 |
|---|---|---|---|
| 필수 2.1 | 실제 HTTP 기반 `/`, `/jobs`, `/robots.txt` 및 관리 포트 `/actuator/health/readiness` 분리·응답 검증, 고정 관리 포트 충돌 방지 | **해결** (`WebEnvironment.RANDOM_PORT` + `management.server.port=0` 적용, `realHttpReadinessIsAvailableOnlyOnManagementPort` 통과 확인) | [`FoundationIntegrationTest.java`](../../src/test/java/kr/gongchae/bell/FoundationIntegrationTest.java#L28-L64) |
| 필수 2.2 | `gradlew` Git 실행 권한(`100755`) 설정 | **해결** (`git ls-files -s gradlew` → `100755` 확인) | [`gradlew`](../../gradlew) |
| 권장 3.1 | `/robots.txt` 호출 시 불필요한 DB 쿼리 제거, `Clock` 빈 재사용, 최신 완전 수집 조회용 부분 인덱스 추가 | **해결** (`@ModelAttribute` 제거 후 뷰 핸들러에서만 `freshness(model)` 호출, `Clock` 주입, `V2` 마이그레이션 분리 추가) | [`CatalogController.java`](../../src/main/java/kr/gongchae/bell/catalog/CatalogController.java#L15-L49), [`V2__collection_freshness_index.sql`](../../src/main/resources/db/migration/V2__collection_freshness_index.sql#L1-L2) |
| 권장 3.2 | `code_name.type` CHECK 제약, 푸터 공공데이터포털 출처 표기, CI 잡 `timeout-minutes` 설정 | **해결** (`posting_change` FK의 CASCADE 미적용 사유—공고 삭제 기능이 없고 변경 이력을 묵시적으로 지우지 않음—도 타당하여 동의함) | [`V2__collection_freshness_index.sql`](../../src/main/resources/db/migration/V2__collection_freshness_index.sql#L3-L4), [`layout.html`](../../src/main/resources/templates/fragments/layout.html#L22), [`ci.yml`](../../.github/workflows/ci.yml#L11-L29) |

---

## 2. DoD (Definition of Done) 점검

- [x] 이슈 #2의 완료 기준(`./gradlew check bootJar` 통과, 실제 서버 `/`, `/jobs`, `/actuator/health/readiness` 응답 및 포트 분리 확인, DB·관리 포트 비공개, 비밀값 미포함) 충족
- [x] PostgreSQL 17 Testcontainers 기반 통합 테스트 4건 통과 및 원격 CI 검증
- [x] Flyway 마이그레이션(`V1`, `V2`)이 기존 적용 이력을 훼손하지 않고 확장 방향으로 작성됨
- [x] [`docs/design/erd.md`](../../docs/design/erd.md) 및 [`docs/implementation-status.md`](../../docs/implementation-status.md)에 현재 구현 범위가 정직하게 기록됨
- [x] 코드 및 커밋 내 비밀값 없음 (Gitleaks 로컬·원격 스캔 통과)
