# 앱·빌드 의존성 보안 갱신 (2026-11-23, #45)

[Issue #45](https://github.com/Lcjam/gongchae-bell/issues/45)의 기록이다. 공개 출시 전 감사(2026-11-22)가 찾은 앱 런타임·빌드 도구 공지를 반영했고, Actions 참조를 commit SHA로 고정했다.
- 기준 main: `c22a9d2abe5a2820de391bfe653170081d282ea3`
- 1차 출처 조회: 2026-11-23 06:25~06:29 UTC
- **이 문서는 실제 침해를 관측한 기록이 아니다.** 운영 서버 배포 기록도 아니다. 운영 반영은 별도 배포에서 한다.

## 바꾼 것

| 대상 | 전 | 후 | 방법 |
|---|---|---|---|
| Spring Boot(플러그인과 BOM) | 4.1.2 | **4.1.3** | `build.gradle.kts`의 두 버전 |
| Tomcat(`org.apache.tomcat.embed`) | 11.0.27(BOM 관리값) | **11.0.29** | 아래 "Tomcat override" |
| Gradle wrapper | 9.9.1 | **9.9.2** | `gradle-wrapper.properties`의 URL과 `distributionSha256Sum` |
| GitHub Actions 참조 | 태그 | **commit SHA + 버전 주석** | `.github/workflows/*.yml`의 `uses:` 줄만 |

**Boot 4.1.3 BOM:** [공식 POM](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.3/spring-boot-dependencies-4.1.3.pom)의 property 값이다.

| property | 값 |
|---|---|
| `spring-framework.version` | 7.0.11 |
| `jackson-bom.version` | 3.1.8 |
| `jackson-2-bom.version` | 2.21.8 |
| `thymeleaf.version` | 3.1.7.RELEASE |
| `hibernate.version` | 7.4.15.Final |
| `micrometer.version` | 1.17.3 |
| `tomcat.version` | **11.0.27** |

Tomcat만 BOM이 아직 옛 값이라 따로 올렸다.

**Tomcat override:**
- 이 저장소는 `io.spring.dependency-management` 플러그인이 아니라 Gradle 기본 BOM 지원(`platform(...)`)을 쓴다.
- [Boot Gradle 플러그인 문서](https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html)에 따르면 이 방식에서는 BOM property로 버전을 바꿀 수 없고, Gradle의 resolution strategy를 쓴다.
- 그래서 `configurations.all { resolutionStrategy.eachDependency { … "org.apache.tomcat.embed" → 11.0.29 } }`를 넣었다.
- Boot BOM이 11.0.29 이상을 관리하게 되면 지운다(코드 주석의 `shortcut:`).

**바꾸지 않은 것:**
- Dockerfile: builder는 `./gradlew`를 실행하므로 wrapper만 바꾸면 Docker builder와 CI가 함께 9.9.2를 쓴다. runtime base(`eclipse-temurin:21-jre-noble`)는 아래 "남은 gate" 때문에 그대로 둔다.
- `gradle-wrapper.jar`와 `gradlew` 스크립트: 다시 만들지 않았다.
- Actions의 major 버전, 이벤트, 권한, Secrets, Variables, 자동 배포 설정

## 1차 출처 대조 (버전 범위)

| 구성요소 | 1차 출처 | 이번 대조 결과 |
|---|---|---|
| Tomcat | [security-11.html](https://tomcat.apache.org/security-11.html) | "Fixed in 11.0.29"(2026-11-13) 절의 10건이 모두 11.0.27을 영향 범위에 넣는다. 미출시 11.0.30 절의 CVE-2026-133325(Critical, cluster TLS 1.3 + TLS-PSK)는 영향이 **11.0.28~11.0.29**이므로 11.0.29에 해당한다. 이 앱은 Tomcat cluster를 쓰지 않는다. Maven Central의 `tomcat-embed-core`·`-el`·`-websocket` 최신 release는 11.0.29다 |
| Spring Framework | [CVE-2026-59374](https://spring.io/security/cve-2026-59374/) 외 2026-11-19 공지 | 영향 7.0.0~7.0.10, OSS 수정 7.0.11 |
| Jackson databind | [GHSA-m8xf-2cr7-77ph](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph) (CVE-2026-110879, Critical) | `tools.jackson.core` 3.0.0~3.1.7 → 3.1.8. 이 JAR에는 2.x databind가 없다(2.x는 `jackson-annotations`만 있다) |
| Thymeleaf | 저장소 advisory(감사 2026-11-22 조회) | 3.1.5 대상 공지는 3.1.6·3.1.7에서 고쳤다. 이번에 다시 조회하지 않았다 |
| Gradle | [GHSA-99wf-vhq9-wm2x](https://github.com/gradle/gradle/security/advisories/GHSA-99wf-vhq9-wm2x) 외 2026-11-19 공지 3건(GHSA-7f7r, -4mf6, -35pj) | 모두 High이고 `>= 9.0.0, <= 9.9.1` → 9.9.2 |

**공지 전제(코드상 사용 조건):** 버전 범위 대조와 따로 본다.
- 감사 문서(2026-11-22)의 코드 읽기 결과, 공개 경로 공지의 전제는 대부분 이 앱에서 충족되지 않는다. 예를 들어 다형 type id, DataBinder 필드 패턴, unescaped 식, HTTP/2, WebSocket·cluster가 없다.
- 이번에 `c22a9d2`의 `src/main`을 다시 grep했다. 다음은 모두 0건이다.
  - `@InitBinder`·`setAllowedFields`
  - `@JsonTypeInfo`·default typing·`readValue`·`@RequestBody`
  - `th:utext`·`th:inline`·`__${`·`TemplateMode.TEXT`
  - `http2`
  - WebSocket
- 그래도 전제를 모두 검토한 것은 아니다. 업그레이드는 전제 판단과 상관없이 영향 버전을 벗어나려고 한 것이다.

## 검증

**Gradle 9.9.2 binary zip SHA256 `971fcd7150ca4ba04852c0b2ca0d2d0ab9468e14b03473eab19528809914c76b`:**
- 다음 세 값이 같다.
  - `services.gradle.org/distributions/gradle-9.9.2-bin.zip.sha256`
  - [gradle.org/release-checksums](https://gradle.org/release-checksums/)의 9.9.2 값
  - 같은 zip(134,970,550 bytes)을 직접 받아 계산한 값
- `services.gradle.org/versions/all`에서 9.9.2는 2026-11-19 release다.

**Actions:** 각 원본 저장소에서 `git/ref/tags/<tag>`를 조회했다. annotated tag는 tag 객체를 풀어 commit을 얻었고, 같은 저장소의 `commits/<sha>`로 그 commit이 있음을 확인했다.

| action | 태그 | commit SHA |
|---|---|---|
| `actions/checkout` | v7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| `actions/setup-java` | v6.3.0 | `d1374f8ac74474200d16ef8ecee22c7ae7a51880` |
| `gradle/actions/setup-gradle` (저장소 `gradle/actions`) | v6.4.1 (annotated tag) | `8da46b539cd8fe6ab27ca75128919a18d757604c` |
| `actions/upload-artifact` | v7.0.2 | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |
| `gitleaks/gitleaks-action` | v3.0.0 | `e0c47f4f8be36e29cdc102c57e68cb5cbf0e8d1e` |
| `docker/login-action` | v3(조회 시점 v3.7.0과 같은 commit) | `c94ce9fb468520275223c153574b00df6fe4bcc9` |

`docker/login-action`의 최신 release는 v4.8.0이다. major 변경은 이번 범위가 아니라서 기존 v3 라인의 commit으로 고정했다.

**빌드 결과(Linux 컨테이너, `eclipse-temurin:21-jdk-noble`, Gradle 9.9.2):**
- `runtimeClasspath` 기준 Tomcat 세 모듈은 `11.0.27 -> 11.0.29`로 해결됐다.
- boot JAR의 `BOOT-INF/lib` 94개:

  | 구성요소 | 버전 |
  |---|---|
  | `Spring-Boot-Version` | 4.1.3 |
  | Boot jar 25개 | 모두 4.1.3 |
  | Spring Framework jar 11개 | 모두 7.0.11 |
  | `tomcat-embed-core`·`-el`·`-websocket` | 11.0.29 |
  | `jackson-core`·`-databind` | 3.1.8 |
  | `thymeleaf`·`thymeleaf-spring6` | 3.1.7.RELEASE |
  | `hibernate-core` | 7.4.15.Final |
  | `micrometer-core` | 1.17.3 |

- `./gradlew check bootJar`: 191개 시험, 실패 0. 실제 포트로 Tomcat을 띄우는 `RANDOM_PORT` 통합 시험이 포함된다. 로컬은 root로 실행해 비root 전용 2개가 건너뛰어졌다.
- 실제 JAR smoke(`deploy/tests/journal-tool-smoke.sh`)는 통과했다. 일반 기동은 `Spring Boot :: (v4.1.3)` 배너를 보였다.
- 최종 PR CI 결과는 PR에 남긴다.

## 남은 gate (이번에 해결하지 않음)

- **런타임 JRE:**
  - Docker Hub 조회(06:28Z) 기준 `eclipse-temurin:21-jre-noble`의 **arm64는 아직 21.0.13+9**다(`21.0.13_9-jre-noble`과 같은 digest `5e730d47…`).
  - `21.0.13.1_2-jre-noble`에는 arm64가 없다.
  - 운영(ARM64)으로 이미지를 다시 빌드해도 JRE는 바뀌지 않는다. OpenJDK 2026-11-17 공지의 21.0.13 대상 10건은 남는다.
  - Temurin 21.0.13.1+2에 어떤 수정이 들어갔는지, 그리고 arm64 이미지가 언제 나오는지 확인한 뒤 runtime base를 정한다. 추측으로 바꾸지 않는다.
- **이미지 OS 패키지:** Ubuntu noble과 `curl`은 스캔하지 않았다.
- **운영 DB·프록시:** `postgres:17`·`caddy:2-alpine` 부동 태그의 실행 버전은 확인하지 않았다.
- **Grafana:** 별도 작업이다.
- **지속 점검:** Dependabot alerts·security updates와 의존성 스캔 잡이 없다. 이 설정은 사용자 결정이다.
- **잠금 파일:** dependency lock·verification metadata는 이번 범위가 아니다.
- **운영 반영:** 이 변경은 운영 서버에 배포하기 전까지 운영 앱(E020)에 반영되지 않는다.
- 이 기록은 전체 보안 안전이나 전수 스캔 완료를 뜻하지 않는다.
