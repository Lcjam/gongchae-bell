# PR #20 독립 검수 · 1라운드

- 검수일은 2026-11-18이며 구현에 참여하지 않은 Sol 리뷰어가 작성했다.
- 판정은 **수정 요청**이다. 아래 소스 결함을 수정하고 재검수해야 한다.
- 대상은 `Lcjam/gongchae-bell` PR #20, 브랜치 `ops/#14-bluegreen-deploy`다.
- 불변 head는 `ede7ce1cd7fc4d0db384c378e81ed4579585b54b`, base는 `000dce555e9eb69ef3184dd5f80aa1d499a1f602`다. PR API와 로컬 HEAD, 원격 추적 ref가 일치한다. 검수 시작과 종료 시 작업 트리는 clean이었다.
- `base..head`의 변경 파일 20개를 검수했다. 다른 리뷰는 참조하지 않았다.

## 발견 사항

### 1. [P1] finalize 중단 뒤 abort가 활성 메타데이터를 이전 버전으로 복원하지 않는다

위치는 `deploy/deploy.sh:492`와 `deploy/deploy.sh:557`이다.

`finalize_pending_internal`은 `active_sha`, `active_color`, `active_deploy_id`를 차례로 바꾸고 마지막에 pending을 지운다. `active_color` 교체 직후 프로세스가 종료되면 pending과 새 active 상태가 함께 남는다. Runbook의 중단 복구 명령인 `abort --deploy-id <pending_id>`는 이전 앱을 다시 띄우고 Caddy를 이전 색으로 돌린 뒤 새 앱과 pending을 지우지만, 이미 바뀐 `active_*`는 복구하지 않는다.

격리 대역에서 `active_color` 파일의 실제 rename을 마친 직후 `mv` 대역이 exit 99를 반환하도록 주입했다. 동일한 상태는 해당 rename 직후 프로세스 중단으로도 발생한다. 이어 같은 ID로 abort를 실행한 결과는 다음과 같다.

```text
finalize_rc=99 active_before_abort=green
after_abort_active=green sha=sha-222 upstream=app-blue:8080
blue_running=yes green_running=no
next_deploy_rc=1
STATE_MISMATCH active_color='green' disagrees with upstream.caddy='blue'
```

복구 명령은 성공을 출력하지만 활성 기록은 정지된 새 앱을 가리키고, 다음 배포가 차단된다. `active_deploy_id` 갱신 뒤 finalize를 재시도하면 이전 ID를 현재 ID에서 다시 읽는 동작도 확인해야 한다.

수정 시 pending에 복구에 필요한 이전 상태를 보존하고, abort가 이전 active 메타데이터까지 일관되게 복구하도록 해야 한다. finalize 재시도와 각 상태 파일 교체 사이 중단도 동일한 결과로 수렴해야 한다. 현재 테스트의 중단 시나리오는 switch가 정상 완료된 뒤 pending이 남는 경우만 다룬다.

검증에는 `active_sha`, `active_color`, `active_deploy_id` 갱신 전후와 pending 정리 중단을 주입하고, finalize 재시도 또는 abort 후 `active_*`, `prev_*`, upstream, 실행 컨테이너 및 다음 배포를 함께 확인해야 한다.

### 2. [P1] Caddy 오류 로그에는 토큰·Referer 제거 필터가 적용되지 않는다

위치는 `deploy/caddy/Caddyfile:26`이다.

현재 필터는 사이트 블록의 `log`에만 설정돼 있다. Caddy의 해당 지시자는 access logger를 설정하며, 공식 adapter 구현도 이 설정의 include를 `http.log.access.<name>`으로 한정한다. HTTP handler가 upstream 연결 실패로 502를 반환할 때 사용하는 error logger에는 원본 request가 첨부된다. 따라서 `/subscriptions/manage?t=FAKE_TOKEN` 요청 시 upstream 연결이 거부되면 access 로그의 `t`는 제거돼도 기본 `http.log.error` 기록의 URI와 Referer에는 값이 남는다. PRD §6과 FR-A6의 프록시 로그 보호 요구에 해당한다. [Caddy 전역 로그 문서](https://caddyserver.com/docs/caddyfile/options#log), [공식 adapter 소스](https://github.com/caddyserver/caddy/blob/master/caddyconfig/httpcaddyfile/builtins.go#L1139-L1141), [공식 HTTP 오류 기록 소스](https://github.com/caddyserver/caddy/blob/master/modules/caddyhttp/server.go#L653-L684)

이는 구성과 upstream 소스를 대조한 정적 판정이다. 로컬 Docker daemon이 없어 이번 검수에서 실제 Caddy 502를 발생시키지는 못했다. 현재 CI의 Caddy 시험은 정상 응답 upstream에 합성 토큰을 보내고 로그를 확인하므로 이 실패 경로를 포함하지 않는다.

수정 시 기본/error logger에도 필요한 마스킹을 적용해야 한다. 격리 Caddy 시험에서 실제 upstream을 중지한 뒤 합성 `t`와 토큰이 든 Referer로 요청하고, stdout과 stderr 전체에서 두 값이 사라지는지 확인해야 한다. 정상 access 로그 시험과 기존 헤더·IP 신뢰 시험도 유지해야 한다.

### 3. [P2] full 기본 경로가 외부 G4를 생략하고 구 버전을 종료한다

위치는 `deploy/deploy.sh:592`와 `deploy/deploy.sh:643`이다. `deploy.sh rollback`의 기본 phase는 full이며, Runbook `docs/runbook.md:175`가 이 실행을 서버 단독 복구 명령으로 안내한다.

`G4_HOOK`가 설정된 경우에만 검증하고, 미설정이면 바로 finalize를 호출한다. 따라서 축약 배포 명령, 기본 full 배포와 사후 rollback 모두 로컬 loopback sanity만 통과하면 이전 앱을 종료한다. ADR-0005의 외부 G4 통과 후 종료 순서가 이 정상 진입점들에서 보장되지 않는다.

격리 대역에서 bootstrap 후 `G4_HOOK`를 미설정한 상태로 다음 명령을 실행했다.

```bash
deploy.sh deploy sha-222 --mode bluegreen --phase full --deploy-id no-g4
```

```text
active=green old_running=no hook=absent
```

GitHub Actions의 명시적 switch → 외부 G4 → finalize 경로는 이 조건을 피한다. CLI 정상 진입점도 외부 판정이 필수로 연결되도록 수정하고 Runbook 명령을 맞춰야 한다. full에서 판정 수단이 없을 때 구 앱이 계속 실행되는지, G4 실패 시 abort하는지, 성공 후에만 구 앱을 종료하는지 검증해야 한다. bootstrap의 별도 경로는 검수 범위의 설치 계약을 따른다.

### 4. [P2] 구 앱 종료 실패를 무시한 채 배포 완료 상태를 커밋한다

위치는 `deploy/deploy.sh:197`과 `deploy/deploy.sh:484`다.

`stop_and_remove_color`가 `compose stop`과 `compose rm`의 실패를 모두 `|| true`로 삼킨다. finalize는 이 함수 뒤에 종료 상태를 확인하지 않고 active/prev를 커밋하고 pending을 지운다. Docker daemon 오류 등으로 종료·제거가 실패하면 구 앱이 계속 실행되는 상태에서도 `DEPLOY_OK`와 exit 0을 반환한다. ADR-0005와 아키텍처에 있는 구 앱 종료 확인이 빠져 있다.

격리 대역에서 switch를 마친 뒤 docker 대역의 stop과 rm만 exit 42로 실패시킨 결과는 다음과 같다.

```text
finalize_rc=0 active=green old_running=yes pending=absent
```

평상시 앱 하나라는 운영 상태와 완료 기록이 달라지고, 종료 실패를 같은 pending 작업으로 재시도할 근거도 사라진다. finalize에서 종료 실패를 전파하고, 이미 종료·제거된 경우의 멱등성과 실제 실행 중인 경우를 구분해야 한다. 실패 시 복구 가능한 상태를 남겨야 한다. stop 실패, rm 실패, 둘 다 실패, 이미 제거된 컨테이너 및 재시도를 검증해야 한다.

## 확인한 요구사항과 검증 근거

- `CLAUDE.md`, PRD §6·§7, ADR-0005·0006·0010·0012·0013·0014·0015, 아키텍처, 배포 계약·결과 문서를 읽고 소스와 대조했다.
- 자동 배포는 main push의 CI 성공을 조건으로 `workflow_run.head_sha`를 checkout하고 같은 SHA 태그 이미지를 빌드·푸시하며 서버에 해당 태그를 전달한다. GitHub concurrency와 서버 flock, pending ID 검증이 있다. GitHub 경로는 외부 G4 뒤 finalize를 호출한다.
- Compose의 공개 host port는 Caddy 80/443이다. 앱 8080/8081과 PostgreSQL 5432의 host port 게시가 없고 DB는 internal backend 망에만 연결된다. 외부 차단 실측은 후속 환경검증으로 남는다.
- env 예시는 빈 값의 키 이름 목록이며 실제 자격증명을 포함하지 않는다. 컬렉터·catalog 비즈니스 코드와 Flyway 파일은 변경되지 않았다. 기존 웹 정적 자산은 self 경로를 사용하므로 추가 CSP와 직접 충돌하는 CDN 의존성을 찾지 못했다.
- 검수 중 `bash -n`으로 배포·프로브·대역·Caddy 시험 스크립트 4개의 구문을 확인했다.

### 해당 head에 연결된 CI

GitHub connector로 CI run `44219390472`의 완료·success와 세 잡의 success를 확인했다. `deploy-verify` job `134517192700` 로그에서 다음 항목을 직접 확인했다. [CI 실행](https://github.com/Lcjam/gongchae-bell/actions/runs/44219390472)

```text
HEAD is now at ca72b29 Merge ede7ce1cd7fc4d0db384c378e81ed4579585b54b into 000dce555e9eb69ef3184dd5f80aa1d499a1f602
ALL_DEPLOY_TESTS_PASSED
CADDY_INTEGRATION_TEST_PASSED
docker build --platform linux/arm64 -t gongchae-bell:ci-ca72b29... .
BUILD SUCCESSFUL
```

PR CI가 시험한 checkout은 위 head와 base로 구성된 병합 커밋 `ca72b29bef1d643a4d054f9db1baa8404a7ed730`이다. Gradle 잡, Gitleaks 잡, Compose 검증, ARM64 이미지 빌드와 실제 Caddy 정상 경로 시험이 통과했다. 이 CI는 이미지 boot부터 실제 DB 연결까지의 운영 배포 성공을 증명하지 않는다.

### 격리 재현의 조건

- 원본 `deploy.sh`와 기존 `deploy-test.sh`의 setup 대역을 재사용했다. 원본 소스·테스트·설정을 편집하지 않았다.
- 임시 디렉터리의 합성 env, docker/curl 대역 및 실행 상태 파일로 순차 실행했다. 실제 SSH, OCI, DNS, provider API, 메일, 운영·개발 DB와 자격증명은 사용하지 않았다.
- Windows Git Bash에 flock 실행 파일이 없어 이 추가 재현에서만 성공 반환 대역을 사용했다. 추가 재현 결과는 단일 실행의 상태 전이와 실패 처리에 한정한다. 실제 flock 동시성 시험은 위 Linux CI 로그로 확인했다.
- Windows sandbox의 MSYS mkdir 오류로 첫 격리 시도가 진행되지 않아, 동일한 합성 대역 재현을 승인된 sandbox escalation으로 실행했다. 임시 파일은 정리했다.

## 후속 환경검증

실서버 bootstrap, 공개 HTTPS, main 자동 배포와 실제 실패 롤백의 G4 실측, 외부 포트 차단·자원 실측은 작성자도 미검증으로 명시했다. 이 항목들은 후속 운영 게이트로 유지한다. 이번 리뷰에서 발견한 소스 결함을 수정한 뒤에도 해당 환경검증이 필요하다.

이 검수의 Caddy 오류 로그 판정은 공식 구성·구현 소스에 근거하며, 실제 이미지의 502 로그 재현은 미실행이다. 새 배포 상태 로직의 회귀 검증에는 위 3개 대역 재현과 Caddy 오류 경로 시험을 포함해야 한다.
