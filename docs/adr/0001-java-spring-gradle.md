# ADR-0001 Java 21 + Spring Boot 4.1 + Gradle

- **상태**: 채택 (2026-11-08)
- **맥락**: CLAUDE.md에 "Java 21+"로 적혀 있다. 이 PC에는 Java 17이 깔려 있다. 런타임 이미지는 `eclipse-temurin` 21과 25 모두 arm64를 지원한다(03에서 확인).
- **선택지**: Java 21 / Java 25 · Maven / Gradle
- **결정**: **Java 21(LTS)**, **Spring Boot 4.1.x**(안정판), **Gradle(Kotlin DSL) + toolchain**. 로컬 Java 17과 상관없이 Gradle이 21을 받아 쓴다.
- **결과**: 25에만 있는 기능은 쓰지 않으므로 잃는 것이 없다. 로컬, CI, 이미지를 21로 통일한다. (claude는 25를 제안했다가 철회했다)
