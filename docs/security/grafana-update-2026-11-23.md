# Grafana OSS 보안 갱신 기록 (`13.0.2` → `13.1.10`, 2026-11-23)

이 문서는 [#46](https://github.com/Lcjam/gongchae-bell/issues/46) 및 의존성 보안 감사(`collab/2026-11-22-release-audit/03-claude-dependencies.md` §2.3)에 따라 `compose.monitoring.yml`의 Grafana OSS 이미지를 갱신한 근거, 1차 레지스트리·공지 검증 결과, minor DB 마이그레이션 및 rollback 제약, 실제 운영 반영 전 별도 운영 gate를 기록한다.

## 1. 변경 요약과 경계 유지

- **변경 파일:** `compose.monitoring.yml` (`services.grafana.image`)
  - 기존: `grafana/grafana-oss:13.0.2`
  - 변경: `grafana/grafana:13.1.10@sha256:467fa061e60b09f4011c6f84620b9ec8805b077f85dfb2b76c5bdbc35b1b1a8f`
- **저장소 전환 근거 (OSS 유지):**
  - Grafana 공식 Docker 설치 문서(<https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/>)는 12.4.0부터 `grafana/grafana-oss` Docker Hub 저장소 갱신을 중단하고, 동일한 OSS 이미지를 제공하는 `grafana/grafana` 사용을 권고한다.
  - 2026-11-23 Docker Hub API 직접 조회 결과 `grafana/grafana-oss`는 `13.0.2`(2026-06-02) 이후 갱신이 중단돼 있다.
  - Grafana Enterprise(`grafana/grafana-enterprise`) 전환, 유료 기능 활성화, 새 요금제 추가는 하지 않는다.
- **보안·기능 경계 유지:**
  - `127.0.0.1:3000:3000` loopback 바인딩(SSH tunnel 전용), `GF_USERS_ALLOW_SIGN_UP=false`, `GF_AUTH_ANONYMOUS_ENABLED=false`, 단일 `admin` 계정, 단일 Prometheus datasource, 외부 분석·업데이트·뉴스 비활성화, `OPS_DISCORD_WEBHOOK_URL` 기본값 `http://127.0.0.1:9/alerts-off`, 프로비저닝 경보 규칙(`expr`/`for`) 및 대시보드 구성을 그대로 유지한다.

## 2. 1차 레지스트리 검증 및 ARM64 지원

2026-11-23 Docker Hub 1차 API 직접 조회 결과:

- **조회 URL:** `https://hub.docker.com/v2/repositories/grafana/grafana/tags/13.1.10` (`HTTP 200`)
- **태그 갱신 시각:** `2026-11-10T17:25:00.823297Z` (`13.1.x` 최신 릴리스)
- **Multi-arch manifest index digest (고정값):**
  - `sha256:467fa061e60b09f4011c6f84620b9ec8805b077f85dfb2b76c5bdbc35b1b1a8f`
- **포함 플랫폼별 active manifest digest:**

| OS / Arch | Digest | 용도 |
|---|---|---|
| `linux/amd64` | `sha256:e6f6f89c2c67fcaa7682ab3aa0e35a075af750eb61953f81fb5e03a309868b1d` | x86_64 환경 호환 |
| `linux/arm64` | `sha256:bd1242059b1842187da339f523b46a79326618558bf281a5db370368c2f6e229` | 운영 OCI ARM64 호스트 및 CI `ubuntu-24.04-arm` |
| `linux/arm/v7` | `sha256:98fcb631e9af19fa7ee1421602305068a77c18dc32819a6474785d3e77f94ed2` | 32-bit ARM |

- 단일 플랫폼 digest 대신 multi-arch manifest index digest로 고정해 `linux/arm64`와 `linux/amd64` 모두에서 동일한 불변 릴리스를 풀한다.

## 3. 보안 공지 대조 결과 및 잔여 한계

2026-11-23 기준 Grafana 공식 보안 공지 피드(<https://grafana.com/security/security-advisories/index.xml>)와 각 CVE 페이지를 직접 대조했다.

### 3.1 `13.1.10` 선택 근거 (`13.0.13`·`13.1.9` 대비)

- **`CVE-2026-17033` (Medium, CVSS 6.8, 2026-08-24):** *Grafana OSS: Stored XSS via external Alertmanager generatorURL* (<https://grafana.com/security/security-advisories/cve-2026-17033/>)
  - `Fixed Versions`: `>=13.1.0`
  - `13.0.13`만으로는 이 공지가 해소되지 않으므로 `13.0.13`만으로 모든 알려진 지적을 해소했다고 주장할 수 없다.
- **`CVE-2026-111024` (High, CVSS 7.1, 2026-11-10):** *Folder permission bypass in the Git Sync repository files API* (<https://grafana.com/security/security-advisories/cve-2026-111024/>)
  - `Fixed Versions`: `<13.0.2 >=13.0.13 <13.1.0 >=13.1.10 <13.2.0 >=13.2.6 <13.3.0 >=13.3.1`
  - `13.1` 라인의 수정 버전은 **`13.1.10`**이며 `13.1.9`는 부족하다. 같은 날(2026-11-10) 공개된 `CVE-2026-14242`, `CVE-2026-102589`, `CVE-2026-13721`, `CVE-2026-110289`, `CVE-2026-107509`, `CVE-2026-110992`, `CVE-2026-106472`, `CVE-2026-107365` 역시 `13.1` 라인 수정 기준이 `>=13.1.10`이다.

### 3.2 감사 항목별 해소 현황과 미판정·전제 미충족·미검사 항목

아래 표는 `13.1.10`에 대한 **전수 안전 판정이 아니다.** 1차 공지 메타데이터에 `13.1` 수정 버전이 명시된 항목과, `13.x` 수정 토큰이 없거나(`>=` 나열·`13.0` 이전/단독 표기) 별도 plugin 버전 확인이 필요한 항목을 구분해 기록한다.

| 구분 | 대상 CVE | `13.1.10` 기준 상태 | 비고 |
|---|---|---|---|
| 감사(`03-claude-dependencies.md`) 범위 내 26건 중 High 8건 | `CVE-2026-111024`, `-111000`, `-102589`, `-88967`, `-100105`, `-76154`, `-17183`, `-9703` | **수정됨 (1차 공지 기준)** | `13.1` 라인 최대 요구 수정판 `13.1.10` 충족 |
| 감사 범위 내 26건 중 Medium 18건 (`CVE-2026-17033` 포함) | `CVE-2026-14242`, `-13721`, `-110992`, `-106472`, `-82014`, `-107365`, `-88858`, `-93294`, `-15577`, `-95502`, `-33374`, `-13719`, `-13720`, `-81842`, `-81841`, `-17033`, `-19197`, `-11817` | **수정됨 (1차 공지 기준)** | `CVE-2026-17033`은 `>=13.1.0`으로 해소 |
| 2026-11-23 1차 피드 대조에서 추가 확인된 `13.0.2` 범위 내 1건 | `CVE-2026-15815` (High, CVSS 8.8, 2026-09-15, Plugin Extraction Zip Slip) | **수정됨 (1차 공지 기준)** | `Fixed Versions: >=12.4.11 >=13.0.9 >=13.1.6 >=13.2.2` |
| `13.1.0–13.1.9` 구간 전용 공지 2건 | `CVE-2026-110289`, `CVE-2026-107509` (Medium, 2026-11-10) | **수정됨 (1차 공지 기준)** | `Fixed Versions: <13.1.0 >=13.1.10 ...` |
| 감사에서 미판정 제외된 `13.0` 이전 게시 4건 중 1건 | `CVE-2025-12141` (Low, 2025-12-16) | **수정됨 (1차 공지 기준)** | `Fixed Versions: >12.3.0` |
| `13.x` 미표기 본체 공지 (13.0 GA 이후 포함) | `CVE-2026-21723` (Medium, CVSS 5.3, 2026-07-22, Templates Test endpoint OOM) | **불명 (`13.x` 미표기)** | `Fixed Versions: >=11.6.11 >=12.0.10 >=12.1.7 >=12.2.5 >=12.3.3`. 원문상 anonymous 활성화 시 익스플로잇 가능(`GF_AUTH_ANONYMOUS_ENABLED=false`·단일 `admin`·loopback 배치) |
| `13.x` plugin 분리 공지 2건 (Elasticsearch datasource) | `CVE-2026-106475` (High, CVSS 7.1, 2026-11-10), `CVE-2026-81843` (Medium, CVSS 6.5, 2026-11-10) | **`13.x` plugin 버전 불명 / 이 배치 전제 미충족** | 두 공지 모두 `Fixed Versions: >=12.4.15`이며 원문상 Grafana 13.0+에서는 별도 Elasticsearch plugin `12.9.6`에서 수정. `13.1.10` 이미지 내부 plugin 버전은 미확인이나, 이 배치는 Prometheus 단일 datasource만 사용해 ES datasource 미사용 |
| `13.0` 이전 게시 `13.x` 미표기 공지 5건 | `CVE-2026-21724` (Medium, 2026-03-25), `CVE-2026-21721` (High, 2026-01-27), `CVE-2026-21720` (High, 2026-01-27), `CVE-2026-21725` (Low, 2026-02-25), `CVE-2026-21727` (Low, 2026-01-27) | **불명 (`13.x` 미표기)** | 공지 메타데이터가 `11.6–12.4` 브랜치 범위만 표기해 `13.x` 수정 토큰이 없음. 공식 근거 없이 `13.1.10` 포함으로 단정하지 않음 |
| 2026-05~06 게시 `13.0` 라인 단독 표기 공지 (`13.0.1+security-01`, `13.0.2` 등) | 예: `CVE-2026-33382` 등 | **메타데이터상 `13.1` 미표기 (불명 한계)** | `13.0` 라인 수정판만 적혀 있어 `13.1` 토큰 직접 표기 없음. 1차 공지 표기만으로는 전수 수정으로 단정하지 않음 |
| 2026-11-10 이후 2026-11-23까지 `13.1.10` 영향 신규 본체 공지 | 없음 | **0건** | 2026-11-23 공식 `index.xml` 기준(11-18 건은 Strava plugin) |
| 컨테이너 내부 OS 패키지(Alpine 등) | 미스캔 | **미검사** | 별도 SBOM/OS 스캐너 실행은 이번 범위 밖 |

## 4. Minor 업그레이드(`13.0.2 → 13.1.10`) DB 마이그레이션 위험과 Rollback 제약

공식 업그레이드 문서(<https://grafana.com/docs/grafana/latest/upgrade-guide/upgrade-v13/upgrade-v13.1/>)와 Docker 문서(<https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/>)를 확인했다.

- **내장 SQLite DB 위치:** 이 프로젝트의 Grafana는 named volume `grafana_data`(`/var/lib/grafana`)에 내장 SQLite 3 데이터베이스(`grafana.db`)를 저장한다.
- **공식 13.1 업그레이드 문서 확인 사항:**
  - 필요 시 이전 버전으로 롤백할 수 있도록 업그레이드 전 Grafana 데이터베이스를 백업할 것을 권고한다.
  - SQLite(`grafana.db`) 데이터 무결성을 위해 백업 전 Grafana 서비스를 중지해야 한다.
  - 가능하면 테스트 또는 개발 환경에서 업그레이드 절차를 먼저 검증할 것을 권고한다.
- **Rollback 제약 (프로젝트 운영 판단):**
  - 위 공식 13.1 업그레이드 문서에는 `13.1`에서 `13.0`으로의 DB 다운그레이드 절차가 별도로 제시돼 있지 않으며, 이 프로젝트는 `13.1.10`으로 한 번 기동·마이그레이션된 `grafana_data` 볼륨을 `13.0.2` 이미지로 되돌렸을 때의 하위 버전 호환을 검증하지 않았다.
  - **따라서 `13.1.10`으로 기동된 `grafana_data` 볼륨 위에 컨테이너 이미지만 `grafana/grafana-oss:13.0.2`로 되돌리는 행위를 안전한 DB rollback으로 취급하지 않는다.**
  - 안전한 rollback은 반드시 **업그레이드 전 Grafana 서비스를 중지한 상태에서 백업해 둔 `grafana_data`/`grafana.db` 사본을 복원한 뒤** 이전 이미지를 기동하는 절차로만 수행한다.

## 5. 검증 범위와 실제 운영 Rollout 전 필수 별도 Gate

### 5.1 PR 자동 통합 검증 (격리된 신규 볼륨·`ubuntu-24.04-arm`)

기존 통합 시험으로 `grafana/grafana:13.1.10@sha256:467fa061...`의 실행 호환성을 검증한다(새 버전 문자열만 복제하는 단위 시험은 추가하지 않음):

- `deploy/monitoring/tests/compose-integration.sh`: `compose.prod.yml` + `compose.monitoring.yml` 병합, loopback 포트(`127.0.0.1:3000`) 및 `monitoring` 네트워크 격리, `deploy.sh monitoring up/restart grafana`, Prometheus `SIGHUP` 재적재 검증.
- `deploy/monitoring/tests/alert-e2e.sh`: 기본 off 주소(`127.0.0.1:9/alerts-off`) 부팅, webhook stub 전환 후 `ops-discord` 연락처·`gongchae-prometheus-forward` 규칙·`gongchae-ops` 대시보드·PromQL 쿼리·알림 발동/해제(`GongchaeApiBudgetStopped`, `GongchaeAlertsFailedHigh`, `GongchaeAlertsUncertain`)·PII 비노출 검증.

### 5.2 실제 운영 Rollout 전 별도 운영 Gate (이번 PR 미실행)

이번 PR은 운영 서버·SSH·실제 `grafana_data` 볼륨·실제 Discord에 접근하지 않는다. 병합 후 `deploy.yml`이 새 `compose.monitoring.yml`을 서버에 동기화한 상태에서는 평소의 `deploy/deploy.sh monitoring up -d grafana`(웹훅 활성화 포함)나 `up -d prometheus grafana` 실행만으로도 새 이미지 재생성과 `grafana_data` 마이그레이션이 일어날 수 있으므로, 아래 Gate 1·2를 마치기 전에는 기존 `grafana_data`를 대상으로 `grafana`를 `pull`·`up`·재생성하지 않는다([모니터링 운영 문서](../ops/monitoring.md) 주의문 참조). 실제 운영 반영 전 아래 3단계를 별도 운영 gate로 완료해야 한다.

1. **사전 `grafana_data` 백업 확보 (미실행):** 공식 SQLite 백업 지침에 따라 운영 Grafana 컨테이너를 중지한 상태에서 `grafana_data` 볼륨(`grafana.db` 포함)의 복구 가능한 백업 사본을 생성하고 보관한다.
2. **복구 사본 기반 격리 업그레이드 검증 (미실행):** 운영 볼륨을 직접 변경하지 않고, 위 백업 사본을 별도 격리 볼륨·컨테이너에 마운트해 `grafana/grafana:13.1.10@sha256:467fa061...` 기동 및 `13.0.2 → 13.1.10` 실제 SQLite 마이그레이션·관리자 로그인·대시보드·규칙 정합을 확인한다.
   - **최초 기동 전 네트워크 egress 차단:** 복원 사본 컨테이너는 처음 기동하기 전부터 외부 egress가 없는 전용 격리 네트워크(`internal` 네트워크 또는 `--network=none`)에만 두고, 외부 송신이 허용된 운영 `monitoring` 네트워크(`compose.monitoring.yml`의 non-internal 네트워크)에는 연결하지 않는다. 쿼리 확인이 필요하면 격리된 Prometheus 또는 mock만 연결한다.
   - **off 환경변수의 역할 구분:** `OPS_DISCORD_WEBHOOK_URL=http://127.0.0.1:9/alerts-off`는 Grafana 전역 발송 차단 설정이 아니라 `deploy/monitoring/grafana/provisioning/alerting/forward.yml`의 file provisioning 변수 치환 값이다(<https://grafana.com/docs/grafana/latest/alerting/set-up/provision-alerting-resources/file-provisioning/>, <https://grafana.com/docs/grafana/latest/setup-grafana/configure-grafana/>). 운영 DB 사본에 기존 contact point·알림 상태가 남아 있을 수 있으므로 이 환경변수만으로 외부 송신 차단을 보장할 수 없으며, 신뢰한 프로비저닝 재적용 및 실제 persisted 설정 확인을 위한 **보조 수단**으로만 사용한다.
   - **민감정보 비출력 및 실발송 경로 분리:** 리허설 로그·결과에는 계정·자격증명·contact point URL을 출력하지 않으며, 실제 발송 경로는 격리 검증 완료 후 운영계에서만 사용한다.
3. **운영 반영 및 SSH 터널 사후 확인 (미실행):** Gate 1·2 통과 후 `deploy/deploy.sh monitoring pull grafana && deploy/deploy.sh monitoring up -d grafana`로 반영하고, SSH 터널(`127.0.0.1:3000`)로 `/api/health` 버전(`13.1.10`), Prometheus datasource, `gongchae-ops` 대시보드, 경보 규칙 평가 상태를 확인한다. 이상 발생 시 이미지만 내리지 않고 Grafana 중지 → Gate 1 백업 복원 → `13.0.2` 재기동 순으로 복구한다.
