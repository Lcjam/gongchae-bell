# PR20 2차 검수

2026-11-18 root Codex가 head `24b15eb365e617c9bf89a220183a0db14192a4df`를 검수했다. 이전 검수 head `ede7ce1cd7fc4d0db384c378e81ed4579585b54b` 이후 코드·회귀 시험·Runbook 변경을 확인했다. GitHub PR20과 로컬 HEAD, clean 상태가 일치한다.

## 판정

소스 수정은 수용한다. 이전 네 결함의 동작과 해당 회귀 시험을 대조했다. 실제 운영 설치·G4 측정은 #14의 후속 실행으로 남는다.

| 이전 결함 | 확인한 수정과 검증 |
|---|---|
| 중단된 finalize 뒤 abort의 active 불일치 | pending에 이전 active ID·prev 메타데이터·대상 색의 기존 태그를 보존한다. pending ID가 완성 표식으로 마지막에 기록되고, abort는 앱·upstream을 복구한 뒤 active/prev/태그를 복원한다. finalize 재시도는 보존한 이전 ID를 사용한다. active_color·active_deploy_id 교체 후 중단, abort·finalize 재시도와 다음 배포를 대역에서 검증한다. |
| Caddy error 로그 토큰 노출 | 같은 redacted_log_format을 사이트 access와 전역 runtime logger에 적용한다. 통합 시험이 실제 upstream을 정지시켜 502와 http.log.error를 확인하고 stdout/stderr 전체에서 합성 token·Referer·인증·Cookie 값 제거를 검사한다. 일반 keep 파라미터와 기존 정상 경로 시험도 유지한다. |
| full 경로 외부 G4 생략 | bluegreen·사후 rollback의 full 실행은 전환 전에 실행 가능한 G4_HOOK을 확인한다. 실패 시 abort, 성공 뒤 finalize다. pending rollback은 기존 앱으로 복구하고 bootstrap은 별도 설치 경로다. GitHub의 switch→외부 probe→finalize/abort 흐름을 유지한다. |
| 구 앱 stop/rm 실패 무시 | 컨테이너 존재·실행·제거를 확인하고 실패를 전파한다. finalize/abort의 실패 결과와 pending을 보존한다. stop 오류·정지되지 않는 성공 응답·rm 오류·이미 제거된 경우와 재시도를 검증한다. |

## 근거

최종 head의 CI `44225651081`에서 `test`, `secrets`, `deploy-verify`와 배포 대역·Compose·실제 Caddy 통합·ARM64 빌드 단계의 success를 직접 확인했다. 로컬 전체 배포 시험은 CI 결과를 근거로 검수했다. [CI 실행](https://github.com/Lcjam/gongchae-bell/actions/runs/44225651081)

Claude의 별도 2차 검수도 같은 head를 수용했다. [Claude 검수](pr-20-claude.md)

G4의 실제 성공 배포와 실패 롤백, 공개 HTTPS, 외부 포트·자원 측정, 운영 비밀값과 해지 대장 준비는 실제 서버 적용 단계에서 검증한다. 본 판정은 해당 환경 검증과 함께 #14의 최종 완료 근거가 된다.
