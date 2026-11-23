# PR #33 4차 검수(Codex · Sol) 수정 결과 (`agy`)

- **작성자 / 일자**: `agy` (단독 작성, 추가 에이전트 미생성) / 2026-11-23 KST
- **근거 문서**: `collab/reviews/pr-33-sol-round4.md`, `collab/reviews/pr-33-codex-round4.md` (이전 1~3차 검수·결과·실패 기록 무수정 보존)
- **작업 위치 / 브랜치**: `C:/Users/lcj/orca/workspaces/my-project/issue-15-collector-drill` / `ops/#15-collector-drill`
- **최신 `origin/main` 정합 확인**: `2090c4c14930e9b57f6fdcf01ba04a9d06bb12d4` (PR #38 병합 이후 `git merge-tree` 충돌 0건, GitHub PR #33 `mergeable=MERGEABLE`, `mergeStateStatus=CLEAN`, PR #38이 추가한 `CI / pc-pull-verify` 포함 8잡 CI 통과 확인)
- **직전 4차 검수 고정 HEAD SHA**: `c1e77a3633c191825b813e9171becc419c3b6b26` (소스 `0370ced21ae5f1b2481f0c17f6e9ff8910840eb7`)
- **이번 4차 수정 소스 커밋 SHA**: `c14f0b0d8719c3e7d4e425f2fdb2ffaff0779377` (`fix: propagate metadata publish errors and guard partial RESTORED state (Refs #15)`)
- **최종 PR #33 HEAD**: 본 결과 문서 추가 커밋(`c14f0b0d8719c3e7d4e425f2fdb2ffaff0779377` 직후 1커밋, 코드/스크립트/테스트 추가 변경 없음). 최종 HEAD SHA 및 최종 HEAD 기준 8/8 CI 결과는 Draft PR #33 본문에 함께 갱신.
- **Draft PR**: [#33](https://github.com/Lcjam/gongchae-bell/pull/33) (Draft 유지, `#15` 미종료)

---

## 1. 수정 내역 (P2 1건 — metadata 발행 실패 명시 전파 및 부분 RESTORED 보호)

1. **`write_meta_files` 실패 명시 전파 (`deploy/monitoring/collector-alert-drill.sh`)**:
   - `mkdir -p "$BACKUP_DIR" "$STATE_DIR"`와 세 파일(`meta.env`, `state`, `drill-runtime.owner`) 각각의 임시 파일 쓰기(`printf > *.tmp.$$`) 및 `mv -f` 교체를 모두 명시적으로 검사해, 어느 단계에서든 실패하면 남은 임시 파일을 정리하고 `META_PUBLISH_FAILED step=mkdir` 또는 `META_PUBLISH_FAILED target=<meta.env|state|owner>`를 stderr에 남긴 뒤 즉시 `return 1`을 반환하도록 수정했다.
   - **원자성 범위 명시**: 각 파일은 동일 디렉터리 임시 파일+`mv -f`로 **단일 파일 단위 atomic replace**(빈/잘린 파일 읽기 방지)만 수행하며, 세 파일의 동시 commit이나 전면 `fsync` 트랜잭션을 보장하지 않는다.
2. **모든 호출자 오류 확인 (`restore_and_verify`, `ACTION=run`)**:
   - `ACTION=run`의 두 `write_meta_files "ARMED"` 호출에 `|| fail "META_PUBLISH_FAILED: ..."`를 명시했다.
   - `set +e` 문맥에서 실행되는 `restore_and_verify` 완료 분기에서 `if write_meta_files "RESTORED"; then`이 성공한 경우에만 `RESTORE_DONE=1`과 `DRILL_RESTORE_VERIFIED_OK`를 기록한다.
   - `write_meta_files "RESTORED"`가 실패하면 `rrc=1`로 전환하고 `RESTORE_DONE=1`·`DRILL_RESTORE_VERIFIED_OK`·`exit 0`을 내지 않으며, 후속 best-effort `write_meta_files "RESTORE_FAILED"`가 함께 실패하더라도 원래 실패(`rrc=1`, `DRILL_RESTORE_FAILED`)를 절대 성공으로 덮지 않는다.
3. **부분 `RESTORED` 불일치 시 `ALREADY_RESTORED_NOOP` 차단 (`restore_and_verify`)**:
   - `backup/meta.env`, `backup/state`, `deploy/state/drill-runtime.owner` 세 파일이 **모두 `RESTORED`로 일치할 때만** 읽기 전용 `DRILL_RESTORE_ALREADY_RESTORED_NOOP` 경로로 진입한다.
   - `meta.env`만 `RESTORED`로 바뀌고 뒤 발행(`state` 또는 `owner`)이 실패해 불일치가 남은 경우에는 no-op을 거부하고 재검증 및 재발행 경로를 실행해, 일시 오류 해제 후 재시도에서 세 기록을 `RESTORED`로 수렴시킨다(이미 모두 `RESTORED`인 정상 완료 후 newer config 보존 no-op 동작은 유지).
4. **회귀 테스트 추가 (`deploy/monitoring/tests/collector-alert-drill-test.sh` Case 5c)**:
   - 테스트 전용 임시 `mv` shim(`$WORK/meta-fail-bin/mv`)을 사용해 실제 `bash "$DRILL_SH" restore "${COMMON_ARGS[@]}"` 호출 문맥에서 아래를 검증했다(단, 합성 fault sub-step에서만 `STUB_RULES_ADVANCE=1`로 30초 규칙 평가 대기를 단축했고, 최종 수렴 retry와 Cases 1~4·5·6·7은 모두 비스텁 실제 Docker/Prometheus/Postgres로 실행):
     - `meta.env` / `state` / `drill-runtime.owner` 각 발행 단계 실패 시 비정상 종료, `META_PUBLISH_FAILED target=...`, `DRILL_RESTORE_FAILED`, `DRILL_RESTORE_VERIFIED_OK`·`DRILL_RESTORE_ALREADY_RESTORED_NOOP` 부재, `*.tmp.*` 미잔존 확인.
     - 부분 `RESTORED` 불일치(`meta.env=RESTORED` + `state=ARMED` 및 `meta.env/state=RESTORED` + `owner=ARMED`) 상태에서 `restore`가 no-op으로 성공하지 않음 확인.
     - 이중 실패(malformed TSDB 오류 + `RESTORE_FAILED` metadata 발행 실패)에서도 비정상 종료 및 `DRILL_RESTORE_FAILED` 유지 확인.
     - 부분 `RESTORED` 불일치 상태에서 fault 해제 후 **실제 비스텁 `restore` 재시도**가 `DRILL_RESTORE_VERIFIED_OK`와 함께 세 기록(`meta.env`, `state`, `owner`)을 모두 `RESTORED`로 수렴시킴을 확인.

---

## 2. 직접 실행 검증 · 보고 인용 · 미실행 및 한계

### 직접 실행 검증
- **로컬 직접 실행 (Windows 11 + Git Bash + Gradle)**:
  - `bash -n deploy/monitoring/collector-alert-drill.sh deploy/monitoring/tests/collector-alert-drill-test.sh` 및 `git diff --check`: `exit 0`.
  - `gitleaks dir --verbose deploy/monitoring`: `no leaks found`.
  - `./gradlew.bat test --rerun --tests kr.gongchae.bell.ops.MonitoringContractIntegrationTest`: `BUILD SUCCESSFUL in 27s`.
  - 고정 함수 추출 `set +e` fault probe (`mkdir`, `meta.env`, `state`, `owner` 각 발행 실패 `rc=1` + 임시 파일 정리 + clean retry 3파일 수렴): 5/5 통과 (`ALL_ROUND4_LOCAL_PROBES_PASSED`).
- **원격 ARM64 Linux CI 검증 (4차 수정 소스 커밋 `c14f0b0d8719c3e7d4e425f2fdb2ffaff0779377`, PR #38 병합된 `main` 기준 8/8 `SUCCESS`)**:
  - **CI** ([`#45032401397`](https://github.com/Lcjam/gongchae-bell/actions/runs/45032401397)): `test` (`2m37s`), `deploy-verify` (`1m34s`), `backup-verify` (`1m1s`), `pc-pull-verify` (`6m16s`), `secrets` (`7s`) — **5/5 `pass`**.
  - **Monitoring** ([`#45032401382`](https://github.com/Lcjam/gongchae-bell/actions/runs/45032401382)): `rules` (`17s`), `compose-integration` ([`job/137238637126`](https://github.com/Lcjam/gongchae-bell/actions/runs/45032401382/job/137238637126), `11m37s`), `alert-e2e` (`7m52s`) — **3/3 `pass`**.
  - `compose-integration` 실제 로그에서 Case 5c(`Metadata publish failure propagation (meta.env/state/owner), partial-RESTORED guard, double-failure non-masking, and 3-record retry convergence verified`), Case 4(`92s < 180s`), Case 5b, Case 6, Case 7 및 `COLLECTOR_ALERT_DRILL_TEST_PASSED` 확인.

### 보고 인용 및 보존 기록
- 직전 3차 검수 기준 `c1e77a3633c191825b813e9171becc419c3b6b26`의 7/7 CI(`CI #45030373707`, `Monitoring #45030373768`) 및 과거 실패 이력(`fa662fe` `#45003677085`, `a1604d6` `#45027229617`, `76307c2` `#45028568503`)과 1~4차 비작성자 검수 기록(`pr-33-codex*.md`, `pr-33-sol*.md`)은 모두 보존하며 본 4차 수정의 대체 근거로 쓰지 않는다.

### 미실행 및 보존된 한계
- 로컬 Windows에서 전체 `collector-alert-drill-test.sh`를 중복 재실행하지 않았으며, 로컬에서는 `bash -n`·`git diff --check`·`gitleaks`·`MonitoringContractIntegrationTest`·5건 `write_meta_files` fault probe를 직접 실행하고 전체 Docker/Linux `flock`/stale-inode 통합 시험은 원격 ARM64 Linux CI(`compose-integration`)에서 실실행 확인했다.
- 실제 운영 SSH · OCI · 계정 · `.env` · 비밀키 · 운영 DB · 공개 앱 · 외부 공공 API · 운영 Discord · Resend에는 접근/변경하지 않았으며, 실제 10분 운영 훈련은 재검수·병합 이후 별도 배정한다.
- 호스트 전원 단절·Docker 데몬 불능·감독자 자체 `SIGKILL` 시 자동 복구 완료 불가 한계와, `write_meta_files`가 파일별 atomic replace이며 세 파일 동시 commit/`fsync` 트랜잭션이 아니라는 한계를 그대로 명시·보존한다.
