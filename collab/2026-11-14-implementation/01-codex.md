# codex 구현 준비 기록

- 원본 PRD 전체를 완료 범위로 유지한다. 현재 저장소는 설계뿐이므로 실행 기반 → 수집/웹 → 알림 → 운영 기반 → 실제 출시 검증 순서로 진행한다.
- 첫 이슈 #2와 M1/M2/M3 마일스톤을 생성했다. 기존 미커밋 하네스 제안과 HTML 생성기는 변경하지 않는다.
- Maven Central 공식 메타데이터에서 2026-11-14에 안정 4.1 계열 마지막 항목 `4.1.2`를 직접 확인했다. `4.2.0-RC2`로 올리지 않는다. 출처: https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml
- Gradle 공식 메타데이터의 9.9.1 배포 ZIP과 Adoptium Java 21.0.13+9 ZIP을 받고 원본 API 제공 SHA-256과 비교해 일치했다. 출처: https://services.gradle.org/versions/current 및 https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows
- 도구는 gitignore된 `.tools/`와 `.gradle/`에 한정한다. Wrapper ZIP 체크섬도 고정한다. Docker 29.9.2 엔진 연결을 확인했다.
- agy가 확보한 공개 표본은 공식 게이트웨이의 외부 래퍼를 직접 증명하지 않는다. 원본은 보존하고 변환 fixture의 provenance를 명시하며 운영 API 테스트와 구분한다.
- agy의 원문 링크 포트 경고를 수용한다. 브라우저 이동 URL 허용과 서버 측 Discord SSRF 검증을 분리한다. 원클릭 해지는 Should이므로 Must를 먼저 완료하고 예외 정책은 별도 리뷰한다.
- ADR 변경 없이 기존 채택 설계를 구현한다. prefCondCn/prefCn 매핑 등 불명확한 부분은 구현 전 두 리뷰자와 합의하고 문서에 명시한다.
