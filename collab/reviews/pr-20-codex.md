# PR20 검수 결과

2026-11-18 root Codex가 검수했다. 대상 head는 `ede7ce1cd7fc4d0db384c378e81ed4579585b54b`, base는 `000dce555e9eb69ef3184dd5f80aa1d499a1f602`다. GitHub PR20과 로컬 HEAD의 일치, 검수 전후 clean 상태를 확인했다.

## 판정

수정 후 재검수가 필요하다. 자동 배포의 main push CI 성공 조건, 해당 SHA 이미지 전달, 내부 포트 구성, 외부 G4 뒤 구 앱 종료 순서는 GitHub 경로에서 확인했다. 정상 경로 CI가 통과한 뒤에도 오류 로그와 중단 복구 경로에 아래 문제가 있다.

## 독립 검수에서 재현한 오류 로그 노출

### P1 Caddy의 오류 로그에 관리 토큰과 Referer가 남음

위치는 `deploy/caddy/Caddyfile:26`이다. 사이트의 access logger에만 필터가 연결돼 있어 upstream 연결 실패 시 기본 error logger가 원래 URI와 Referer를 기록한다. 구독 관리 토큰이 포함된 요청에서 발생하므로 PRD FR-A6과 §6 로그 보호를 충족해야 한다.

공식 Caddy v2.11.9 Windows amd64 배포본의 SHA512를 확인한 뒤, 제출된 Caddyfile의 필터와 헤더를 그대로 사용해 격리 재현했다. 변경한 진단 환경은 loopback HTTP 수신 주소와 연결이 거부되는 loopback upstream, 로컬 import/storage 경로다. 진단용 토큰과 Referer를 한 요청에 넣었고 HTTP 502를 받았다. 자식 프로세스는 finally에서 종료했다.

| logger | t 값 | Referer의 토큰 | Authorization·Cookie의 시험 값 |
|---|---|---|---|
| `http.log.access.log0` | 제거됨 | 제거됨 | 제거됨 |
| `http.log.error.log0` | 남음 | 남음 | 제거됨 |

진단 결과는 ignored `.tools/pr20-caddy-review/result.json`에 있다. 이 파일의 sourceHead 조회는 자식 프로세스의 Git 소유권 검사로 실패했으며, 별도 Git 조회로 제출 head와 원본 clean 상태를 확인했다. 재현은 공식 Windows 실행 파일로 수행했고, ARM64 컨테이너 오류 경로 시험은 구현자 회귀 검증에 포함해야 한다.

기본/error logger도 같은 비밀정보 제거 정책을 적용하고, 실제 Caddy upstream 중지 후 합성 토큰과 Referer로 요청하는 회귀 시험을 추가한다. stdout과 stderr 전체에서 민감값이 사라지고 일반 접근 로그·헤더·프록시 IP 시험이 통과해야 한다. Caddy는 사이트 `log`와 전역 runtime logging을 구분한다. [공식 로그 지시자](https://caddyserver.com/docs/caddyfile/directives/log), [공식 전역 로그 설정](https://caddyserver.com/docs/caddyfile/options#log)

## 독립 Sol 리뷰와 교차 확인한 복구 문제

아래 세 항목은 Sol의 격리 대역 재현 결과를 읽은 뒤 해당 함수와 호출자를 다시 대조했다. 재현 조건과 전체 근거는 [Sol 독립 검수](pr-20-sol.md)에 있다.

### P1 finalize 중간 중단 후 abort가 active 메타데이터를 복구하지 않음

`deploy/deploy.sh:492`에서 active 파일들이 순차 교체된다. 이 구간에서 중단되면 pending과 새 active가 함께 남는다. `abort_pending_internal`은 이전 앱과 upstream으로 돌아가면서 이미 바뀐 active 파일을 되돌리지 않아, 다음 배포가 `STATE_MISMATCH`로 차단된다. 이전 deploy ID도 pending에 보존해 finalize 재시도와 abort가 일관된 active/prev/upstream/실행 컨테이너 상태로 수렴하도록 수정한다. 각 active 파일 갱신과 pending 정리 도중 중단을 포함해 검증한다.

### P2 full 기본 진입점에서 외부 G4가 선택적으로 실행됨

`deploy/deploy.sh:592,643`은 `G4_HOOK`가 없으면 finalize로 진행한다. 정상 CLI 진입점과 Runbook의 서버 단독 rollback이 외부 판정 없이 이전 앱을 종료할 수 있다. full 실행에도 외부 판정 수단을 필수로 연결하고, 누락·실패 시 이전 앱이 유지되거나 복구되며 성공 뒤에만 종료되도록 검증한다. 최초 bootstrap의 설치 경로는 별도 계약을 따른다.

### P2 구 앱 종료 실패를 완료로 기록함

`deploy/deploy.sh:197`의 stop/rm 오류가 무시돼 finalize가 `DEPLOY_OK`와 exit 0을 반환하고 pending을 지운다. 실제 구 앱이 계속 실행되는 경우를 감지하고 실패와 재시도 가능한 상태를 남긴다. 이미 종료·제거된 경우의 멱등성을 함께 유지한다. stop 실패, rm 실패, 모두 실패, 이미 제거됨과 재시도를 검증한다.

## 확인한 검증 범위

- 변경된 workflow, Dockerfile, Compose, Caddy 설정, 배포/프로브/대역·통합 시험, MVC 설정 시험과 Runbook을 PRD·ADR에 대조했다.
- 배포/프로브/시험 스크립트 네 파일의 bash 구문 검사가 통과했다. Git 파일 모드는 실행 가능으로 기록돼 있다.
- 해당 head에 연결된 CI run `44219390472`에서 `test`, `secrets`, `deploy-verify`와 그 단계의 success를 직접 조회했다. PR 병합 시험의 checkout과 구체 로그는 Sol이 별도로 확인했다. [CI 실행](https://github.com/Lcjam/gongchae-bell/actions/runs/44219390472)
- 정상 Caddy 시험은 token query, Referer, 인증 헤더, Cookie를 지우고 위조 XFF를 차단한다. 기존 정적 자산은 같은 호스트의 CSS·JS 경로를 사용한다.
- 실제 운영 서버의 bootstrap·HTTPS·main 자동 배포·실패 롤백 G4, 포트 차단과 자원 실측은 후속 운영 검증으로 남아 있다.

## 수정 요청

agy가 위 네 결함과 해당 회귀 시험을 같은 #14 브랜치에서 수정한 뒤 새 불변 head·CI·결과 파일을 제출한다. root와 비작성자 리뷰어가 변경 부분을 다시 확인한다. 실제 서버 적용 전에는 후속 환경검증도 수행해야 한다.
