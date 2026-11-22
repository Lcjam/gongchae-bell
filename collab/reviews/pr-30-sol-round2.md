# PR #30 최종 차분 검수 · Sol 2라운드

- 검수일: 2026-11-20 KST · 비작성자 GPT-6.1 Sol(high), root Codex 요청.
- 고정 대상: **`e93a8c5872e13778da14dd0268653dc9f9a70992`**.
- 비교: 최초 검수 `ea9d0ca3bee5e433858a388d92272a0cafad763d` 이후 차분. PR27 보존 비교 기준은 `c4465f5f3029550168337408f77e7ee6960e18d8`.
- 방법: 자기 검수 worktree `pr30-sol-review`(HEAD는 ea9d0ca 유지)에서 `git show`·`git diff`로 고정 SHA를 읽었다. checkout·브랜치·원작성자 파일은 변경하지 않았다.

**판정: e93a8c5의 코드·문서 차분 수용. D1·D2·P3 모두 해소됐고 이번 차분에서 추가 수정이 필요한 결함은 발견하지 못했다.** 최종 CI와 이후 result-only HEAD의 무관 변경 여부는 root의 별도 확인 조건이다. 이 판정은 운영 배포·PC pull·M2 복구 전체 완료 승인이 아니다.

## 최초 지적 재확인

| 항목 | 판정 | 확인 근거 |
|---|---|---|
| D1 · 제거된 env와 이전 정책 | 해소 | `docs/ops/backup-restore.md` §2는 현재 UTC 기준 **14일 초과** 전체 ID 삭제, 정각 유지, 최후 사본 예외 없음·0개 허용을 설명한다. §4 env 표에서 BACKUP_KEEP_DAILY를 제거했고, 기존 비어 있지 않은 값은 실행을 거부하므로 기존 env의 해당 줄을 삭제하라는 이행 안내가 있다. |
| D1 · 순서·관측·잔류 | 해소 | §1·§2·§6은 저장소 확인→명시 ID forget→매 실행 prune --max-unused 0→동일 RUN_NOW_EPOCH 재조회→덤프→대장→업로드 순서와 일치한다. 덤프/업로드 실패일 선만료, 만료 실패일 덤프 차단, cmd_run 이전 실패의 미수신 경보를 구분한다. forget와 물리 prune를 구별하고 정상 실행 간격·자격/네트워크/잠금/prune 실패·PC 오프라인 잔류를 삭제 완료로 세지 않는다. |
| D2 · PC copy 우선 순서 | 해소 | ADR0017 마지막 PC 접점 및 backup-restore §2·§8 모두 **네트워크 copy 전에 로컬 만료·prune·재조회**, copy 뒤 원래 snapshot 시각 기준 재검사·정리, 실패 시 준수로 세지 않는 조건을 명시한다. PC 구현·오프라인 동안의 삭제 완료를 주장하지 않는다. |
| P3 · 삭제 결과를 잘못 단정한 오류 로그 | 해소 | backup.sh의 snapshots/select 실패는 `retention state unverified`, forget 실패는 `snapshot deletion state unverified and retention not met`으로 바뀌었다. 첫 조회와 삭제 후 재조회에서 공통으로 사용해도 이미 수행된 삭제를 부정하지 않는다. forget의 부분 성공 가능성도 “전부 그대로 남아 있음”으로 단정하지 않는다. |

backup.sh의 실제 실행 변경은 위 **오류 문구 3줄뿐**이다. 만료 선택·ID 전달·prune 옵션·재조회·덤프 순서·heartbeat 종료 조건을 바꾼 코드가 아니다. 최초 [검수](pr-30-sol.md)의 호스트 로직 판정과 제한 검증 결과를 유지한다.

## PR27 복원 안전 절차 보존

PR27 기준과 e93a8c5의 `docs/ops/alerts.md` **전체 바이트가 동일**하며, `docs/ops/backup-restore.md`의 **§7 전체도 바이트 동일**함을 직접 확인했다. 따라서 다음 계약이 통합 과정에서 삭제·완화되지 않았다.

- 운영 Compose postgres/postgres_data에 직접 복원하지 않고 고유 일회용 PG·볼륨·internal 네트워크에서 검증한다.
- 운영 .env를 통째로 source하지 않고 격리 DB 접속값·flags=false·빈 collector heartbeat를 컨테이너에 명시한다.
- 호스트 생존 시 살아 있는 최신 대장을 우선하며 오래된 백업 대장으로 호스트 원본을 덮지 않는다. VM·호스트 대장 소실 때만 가장 최신 정상 백업 대장을 분리 추출한다.
- M1 자동 생략은 **SUB_COUNT=0 AND erasure_included=false AND M2 미개시 이력(실제 호스트 대장도 부재)**의 세 조건이다. M2의 SUB_COUNT=0만으로 missing·손상 대장을 면제하지 않는다.
- 실제 운영 전환 직전에는 양색 운영 앱을 중지한 뒤 최신 호스트 대장 직접 마운트 재적용과 REMAINING_UNSUBS=0 대조를 다시 수행한다.
- 컨테이너가 쓸 수 있는 대장 전용 하위 디렉터리만 rw 마운트하고 덤프·메타 전체를 앱 컨테이너에 노출하지 않는다. M2 백업 읽기 권한은 후속 선행 게이트로 남아 있다.

**보존 확인과 절차 전체의 완성 판정은 다르다.** PR27에서 승계된 flags=false의 웹 해지 설명 오류, 운영 재시작/배포 배타성과 최종 복구 게이트·UNCERTAIN 처리 등은 별도 [복구 교차 검수 07](../2026-11-19-recovery/07-sol-review.md) 및 root 종합의 대상이다. 이 라운드는 해당 기존 문구를 새로 해결됐다고 표시하지 않으며, 이미 예정된 #17/M2 후속 작업을 PR30 오류 메시지 수정에 덧붙이지 않는다.

## 직접 확인과 미실행 범위

직접 수행한 제한 검증:

- `git diff ea9d0ca..e93a8c5`의 7파일 차분 검토. 새 결과 기록 및 PR27 통합 문서와 보관 관련 변경을 구분했다.
- `expire-select.py`, `backup.env.example`, 백업 단위/통합 시험 2개, systemd service/timer 2개의 **6파일이 최초 ea9d0ca와 바이트 동일**함을 git blob 조회로 확인했다.
- 위 PR27 문서 전체/§7의 바이트 동일성 확인.
- `git diff --check ea9d0ca..e93a8c5` **exit 0**.

전체 Gradle·Docker·restic 시험을 다시 실행하지 않았다. 실제 로직·시험의 변화가 없고 추가 제어 흐름 간극을 발견하지 않아 반복하지 않았다. e93a8c5 최종 CI는 root 담당이며 이 문서에서 success로 선결론 내리지 않는다. 최초 ea9d0ca 및 중간 22507a5의 CI 보고를 e93a8c5의 결과로 대신하지 않는다.

실제 OCI·서버·env·SSH키·Resend/Discord·PC 저장소에 접근하거나 변경하지 않았다. 기존 원보고서와 시험을 보존했고 이 검수 기록만 root와 자기 검수 worktree에 작성했다. 이후 result-only 최종 HEAD는 본 검수 대상에 포함되지 않는다.
