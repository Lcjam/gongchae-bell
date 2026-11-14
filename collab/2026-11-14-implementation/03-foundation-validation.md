# #2 검증 및 리뷰 반영

- 2026-11-14, Windows x64, Temurin 21.0.13+9, Docker Engine 29.9.2, PostgreSQL 17 Testcontainers.
- `gradlew.bat check bootJar`: 통과. 최초 테스트 3개에서 실제 HTTP 포트 분리 검증을 추가한 뒤 4개 모두 통과했다.
- 검증 범위: V1/V2 Flyway, 두 DB 연결의 동일 회차 INSERT 선점 1건, COMPLETE 이후 INCOMPLETE 시 표시 시각 유지, 홈/목록/robots/404, 앱 포트의 Actuator 비공개, 관리 포트 readiness UP.
- 별도 실행 앱에서 `http://127.0.0.1:8081/actuator/health/readiness` 및 `/actuator/prometheus` HTTP 200을 확인했다.
- Orca 브라우저 360×800 홈: 문구와 버튼을 확인했고 `document.documentElement.scrollWidth = innerWidth = 360`이었다. 아직 필터/알림 UI 검증이 아니다.
- 첫 로컬 실행 중 빌드 출력 JAR를 재생성하면 실행 JVM이 해당 JAR를 계속 읽어 클래스 로딩 문제가 생겼다. 개발 실행은 `bootRun`을 안내하고 검증 서버는 `.tools/runtime/`의 별도 사본으로 재시작했다.

## agy 리뷰
- 실제 HTTP readiness/관리 포트 분리 테스트 반영. 테스트에서 `management.server.port=0`으로 덮어쓰므로 별도 운영 환경변수는 추가하지 않았다.
- `gradlew` Git 모드 `100755` 확인.
- 마지막 완전 수집 부분 인덱스, code_name 유형 CHECK를 V2에 추가. 이미 로컬에서 적용한 V1은 변경하지 않았다.
- robots DB 쿼리 제거, Clock 시간대 재사용, 푸터 출처, CI 타임아웃 반영.
- posting_change CASCADE는 보류: 공고 삭제 기능이 없고 변경 이력을 묵시적으로 지울 이유가 없다.

## Claude 리뷰 반영
- 오래된 Node20 Actions를 공식 현재 릴리스로 갱신했다: checkout v7.0.1, setup-java v6.3.0, gradle/actions v6.4.1, upload-artifact v7.0.2, gitleaks-action v3.0.0. 최종 CI 검증은 PR 실행으로 확인한다.
- 공급 원본: 각 저장소의 GitHub release API 및 action.yml. Spring Boot/Gradle/JDK 버전은 두 에이전트가 공식 원본으로 교차 검증했다.
- R1: 실행 JAR 사본으로 재시작 후 readiness와 Prometheus를 다시 확인했다. R2: `error`는 100자 이내 내부 코드 전용으로 명시했다. 원본 예외를 저장하지 않는 규칙으로 비밀값 노출도 막는다. R3: `.env.example`에는 키 이름만 둔다는 CLAUDE.md를 유지하고 README에서 세 값 모두 입력하도록 명시했다. R4: Java 17 이상으로 Gradle 실행, 앱은 21 toolchain이라는 문구로 정정했다.
- 2026-11-14 17:35 KST: 체크섬을 검증한 Gitleaks 8.30.1로 `git . --redact --log-opts=HEAD` 실행, 누출 검출 0건. `.env`, `.tools`, 기존 사용자 미커밋 파일은 커밋 대상이 아니다.

전체 서비스 출시와 실제 수집/발송/운영 리허설은 미완료다. #2 완료가 PRD 전체 완료를 뜻하지 않는다.
