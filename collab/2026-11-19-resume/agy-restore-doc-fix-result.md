# #17 복원 runbook 결함 우선 수정 결과 (`agy`)

- **작성자**: `agy` (단독 작성자)
- **요청**: `collab/2026-11-19-resume/restart-agy-restore-doc-fix.md` (근거: `01-claude.md` R1·R2·R5, `02-claude-review.md` X4)
- **작업 위치 / 브랜치**: `C:/Users/lcj/orca/workspaces/my-project/issue-17-restore-runbook` / `docs/#17-isolated-restore`
- **기준 `origin/main` SHA**: `517eb6b11b06a15aebeb7c0d3c94662eacd62ea7` (`Merge pull request #24 from Lcjam/docs/#19-review-records`, PR #21·#22·#24 반영)
- **문서 수정 커밋 SHA**: `eb49a2c69c8c8bdd33005997fe70f854b547400b` (`docs: isolate restore rehearsal DB and preserve live host erasure journal (Refs #17)`)
- **Draft PR**: [#27](https://github.com/Lcjam/gongchae-bell/pull/27) (`main` 머지 및 `#17` close 미수행, 앱 소스·테스트·구현 설정 무변경)

---

## 1. 수정한 문서와 변경 내용

1. **[`docs/ops/backup-restore.md`](../../docs/ops/backup-restore.md) (§1, §7)**
   - **전용 일회용 PostgreSQL 17 격리 복원 (R1 수정)**: 기존 §7 4단계의 운영 Compose 대상 명령(`docker compose -f /opt/gongchae-bell/compose.prod.yml ... exec -T postgres pg_restore ... --clean --if-exists`)을 제거하고, 고유한 컨테이너·볼륨·DB·비운영 계정·Docker `--internal` 네트워크를 쓰는 전용 일회용 `postgres:17` 복원 절차로 교체했습니다. `--clean --if-exists`는 동일 격리 DB 재사용 시에만 허용하고 운영 `postgres` 대상 적용을 금지했습니다.
   - **호스트 생존 시 살아 있는 최신 호스트 해지 대장 사용 (R2 수정)**: 호스트가 살아 있는 사고·리허설에서는 백업 시점 이후 해지까지 포함한 `/var/lib/gongchae-bell/erasure/erasure.log`를 재적용 원천으로 사용하도록 명시하고, 백업 안의 과거 `erasure.log`로 재적용하거나 살아 있는 호스트 대장을 백업 파일로 덮어쓰지 않도록 금지했습니다(리허설 중 운영 앱 잠금 간섭을 피하기 위해 `0700` 임시 디렉터리에 `0600`·`1000:1000` 사본을 떠서 검증). VM·호스트 자체를 영구 소실(또는 호스트 대장 유실)한 경우에만 OCI 또는 NAS/PC 사본 중 가장 최신 정상 사본을 선택하고, 마지막 성공 백업/pull 이후 유실 한계를 고지했습니다.
   - **운영 `.env` `source` 금지 및 부작용 차단**: `/opt/gongchae-bell/.env`를 `source`하지 않고 격리 `DB_URL`·`DB_USER`·`DB_PASSWORD`와 `ALERTS_ENABLED=false`·`COLLECTOR_ENABLED=false`·`HEARTBEAT_COLLECTOR_URL=`(빈 값)을 명시하며 `--internal` 네트워크에서 실행하도록 정리했습니다.
   - **아카이브 구조·순서 불변식·M1/M2 구분·게이트 분리**: `backup.sh`의 `stage_postgres_dump` → `stage_erasure_log` 순서 불변식(R5)과 상대 경로 아카이브 구조(`restic restore` 대상 루트의 `gongchae-bell.dump`, `backup-meta.txt`, `erasure.log`)를 명시했습니다. 처음부터 `ALERTS_ENABLED=false`로만 운영해 `erasure_included=false`인 M1 전용 백업과 M2 이후 대장 필수 조건을 구분하고, missing·손상 대장을 빈 파일로 대체하지 않도록 명시했습니다. `ERASURE_REAPPLY_COMPLETE deleted=N`·`subscription` 0건 확인과 발송 재개 전 키 정합성·운영 전환 게이트를 구분했습니다. 임시 작업 디렉터리(`0700`)와 격리 컨테이너·볼륨·네트워크 정리(`cleanup`) 명령을 구체화했습니다.

2. **[`docs/ops/alerts.md`](../../docs/ops/alerts.md) (`해지 대장과 복원`)**
   - `ALERTS_ERASURE_LOG` 설명에 남아 있던 "백업에서 되살린 대장 파일 지정" 문구를 수정해, 호스트 생존 시 살아 있는 최신 호스트 대장을 사용하고 VM 소실 시에만 최신 백업 사본을 쓰도록 `backup-restore.md` §7과 일치시켰습니다.
   - 격리 DB·부작용 차단 env·`:rw`/`1000:1000`/`0600` 권한 요구·M1/M2 구분·사후 0건 검증과 키/전환 후속 게이트를 함께 정리했습니다.

---

## 2. 소스 수정 없이 해결할 수 없는 한계 (root 보고)

1. **`ErasureJournal.readStrict()`의 `READ, WRITE` 열기 (`src/main/java/kr/gongchae/bell/alerts/ErasureJournal.java:79`)**:
   - `FileChannel.lock()` 배타 잠금 획득 때문에 읽기 전용 검증인 `readStrict()`도 파일을 `READ, WRITE`로 엽니다. 따라서 컨테이너에 `:ro` 마운트로 연결하면 실패하므로 문서에서는 `:rw` + `1000:1000` + `0600` 요구를 명시했고, 운영 앱이 떠 있는 리허설에서는 운영 파일 잠금 간섭을 피하기 위해 임시 사본으로 검증하도록 안내했습니다. 읽기 전용 잠금/검증을 분리하려면 후속 `#17` Java 소스 변경이 필요합니다.
2. **하한 시각(`dump_started_utc` / `TZ=UTC`)·연속성 `cmp`·워터마크·키 일관성 검증·보관 옵션**:
   - 현재 `backup.sh`의 `backup_utc`는 덤프 완료 뒤 기록(상한)이며, `cmp` prefix/하한 시각/`subscription` 키 복호화 검증/워터마크/온라인 정리/PC pull IAM 정책은 `#17` 3자 설계 종합 및 후속 코드·운영 단계에서 다뤄야 합니다.
3. **복원 시 `Flyway`·`notification` 중복 발송 경계**:
   - `--alerts.erasure-reapply=true` 실행 시 `spring.flyway.enabled`가 기본 `true`이므로 덤프와 스키마 호환성이 맞는 배포 이미지(`APP_IMAGE:TARGET_TAG`)를 써야 합니다. 백업 시점 `PENDING`/`RETRY` 비동기 작업의 복원 후 재발송 여부는 별도 `#17` 쟁점으로 남습니다.

---

## 3. 실행 검증과 미실행 범위

- **직접 실행한 검증**:
  - `bash -n deploy/backup/backup.sh deploy/tests/backup-test.sh deploy/tests/backup-integration-test.sh` 및 `deploy/tests/backup-integration-test.sh` (`BACKUP_INTEGRATION_TEST_PASSED`, 일회용 `postgres:17` + `restic/restic:latest`).
  - 로컬 합성 §7 격리 복원 흐름 검증 (`gongchae-bell.jar` + 일회용 `postgres:17` + `restic/restic:latest` + Docker `--internal` 네트워크):
    - `T1` 시점(`sub_a`, `sub_b` 활성, 빈 `erasure.log`) 백업 → `T2` 시점 `sub_b` 해지(호스트 `erasure.log` 기록) → 격리 `postgres:17` 복원 후 살아 있는 호스트 대장 사본으로 `--spring.main.web-application-type=none --alerts.erasure-reapply=true` 실행 → `ERASURE_REAPPLY_COMPLETE deleted=1`, `sub_a=1`, `sub_b=0`, 원본 호스트 `erasure.log` 보존 확인.
    - 누락 대장 전달 시 `ERASURE_LOG_MISSING`으로 비정상 종료(fail-closed) 확인.
  - `docs/ops/backup-restore.md`·`docs/ops/alerts.md` 상대 마크다운 링크 검사(모두 `True`) 및 `gitleaks dir --verbose docs` (`no leaks found`).
- **미실행 범위 (이번 PR 범위 밖)**:
  - 실제 OCI 서버·OCI Object Storage 버킷·운영 `.env`·실서버 DB·비밀키·외부 provider·사용자 PC 백업 저장소 접근 및 실제 서버 복원 리허설(`docs/ops/backup-restore.md` §8 실서버 리허설 표는 `*미실시 (#17 예정)*` 유지).
  - 최종 HEAD CI 및 PR `MERGEABLE` 상태는 푸시 직후 원격 GitHub Actions에서 확인해 PR #27 본문에 기록합니다.
