# #17 교차 검토 · Codex

2026-11-19 root Codex. 독립안 `01-codex.md`를 먼저 쓴 뒤 Claude·agy의 01·02와 추가 `02-agy-claude-review.md`를 읽었다. 아래는 source 구현 확정 전 검수다. 실제 계정·스토리지·키·운영 DB를 바꾸지 않았다.

## 합의와 자기 수정

- 프로젝트 내부 ignored 백업 저장소 제안은 철회한다. 코드 checkout 밖의 사용자 전용 비동기화 경로로 바꾸는 두 안을 수용한다. 실제 경로·ACL·가용 공간은 실행 전에 확인한다.
- 격리 복원, 살아 있는 최신 호스트 대장 우선, 그 대장을 오래된 백업으로 덮지 않는 방향에 동의한다. main517 문서의 실제 운영 Compose `pg_restore --clean`과 백업 대장 덮어쓰기를 직접 읽고 확인했다. 완료된 primary agy에게 별도 문서 수정 PR을 배정했다.
- 별도 메타와 대장 두 번 rename을 하나의 원자 변경으로 간주할 수 없다. 파일 하나 안의 명시적인 coverage 표현과 정지 정리를 우선 검토한다. 온라인 정리는 필수 첫 단계로 만들지 않는다. 고정 잠금·구버전 writer 전환·실제 실행 프로세스 검증이 필요하다는 지적은 유지한다.
- 관리 사본의 물리 파기 증거와 복원 허용 판정은 분리한다. 조회할 수 없는 꺼진 PC를 삭제 완료로 선언하지 않는다. 사용자에게 수동 사본 전면 금지나 새 유료 서비스를 요구하지 않는다.

## 직접 외부 근거 확인

- restic 0.19.1 `copy` source가 원본을 `openWithReadLock(...gopts.NoLock...)`, 대상을 `openWithAppendLock(...false...)`로 연다. 따라서 원본 readonly 권한에 기본 잠금 쓰기가 필요한 주장과 `--no-lock`의 다른 효과를 실제 고정 버전 source로 확인했다. 권한 선택은 원본 삭제/변조 범위를 최소화하되 실제 IAM 조건과 실패·충돌을 리허설해야 한다. 버킷 전체 manage를 자동 승격할 기본 대안으로 채택하지 않는다. [cmd_copy.go](https://raw.githubusercontent.com/restic/restic/v0.19.1/cmd/restic/cmd_copy.go), [secondary_repo.go](https://raw.githubusercontent.com/restic/restic/v0.19.1/internal/global/secondary_repo.go)
- keep-within 계열이 최신 snapshot 기준이고 미래 snapshot은 정책상 삭제에서 제외됨을 공식 문서로 확인했다. 옵션 변경만으로 wall-clock14일 물리 삭제를 주장할 수 없다. forget과 prune은 별도 단계다. [restic forget](https://restic.readthedocs.io/en/stable/060_forget.html)
- Linux POSIX 레코드 잠금은 같은 프로세스의 다른 fd close로도 풀릴 수 있으며, OFD와 POSIX의 충돌 잠금은 상호 충돌한다. 무조건 언어별로 호환/불호환이라고 넓히지 않고 실제 API와 파일시스템 조건을 적는다. [fcntl_locking(2)](https://man7.org/linux/man-pages/man2/fcntl_locking.2.html)

## 채택 전에 해소할 반례

1. **워터마크 문법 호환과 안전한 복원은 다르다.** 직접 읽은 기존 `readStrict`는 nil UUID 줄을 받고 reapply는 이를 DELETE 대상 UUID로만 쓴다. 기존 JAR는 coverage를 검사하지 않으므로 정상 종료가 안전한 시점 검증을 뜻하지 않는다. 새 restore gate가 강제되는 실행 경로·구버전 차단 증거가 필요하다. 실제 UUID nil 충돌을 DB 제약 없이 불가능이라고 쓰지 않는다. 앱 생성 경로 UUID.randomUUID와 기존 데이터/fixture를 각각 대조한다.
2. **초기 빈 대장 prefix는 연속성을 입증하지 않는다.** 빈 B와 사고 후 재생성된 빈 L은 동일하게 비교된다. nil 워터마크 도입만으로 이후 초기화·정상 형식 줄 통째 유실이 모두 탐지된다고 주장하지 않는다. 검증 가능한 범위와 운영 연속성 근거의 한계를 기록한다.
3. **시각 여유 m만으로 DB 커밋 순서를 증명할 수 없다.** source `SubscriptionService.unsubscribe`는 `journal.append(id, clock.instant())` 뒤 DELETE·TX commit이다. 그 메서드에 journal timestamp부터 commit까지의 절대 완료 상한은 보이지 않는다. 예컨대 timestamp t 뒤 fsync/프로세스/DB 대기 동안 snapshot이 행을 포함하고, t보다 훨씬 뒤 커밋됐다고 가정할 수 있다. t 기준 줄 정리 후 그 snapshot을 복원하면 D_lo가 t+m보다 커도 최신 L만 재적용하는 방식이 그 UUID를 놓칠 수 있다. 이것은 source 순서에서 도출한 반례이며 아직 실행 재현하지 않았다. 정리 전 quiescence·백업과의 배타·bundle 대장과 최신 대장 결합 또는 검증된 다른 coverage 규칙으로 해소할지 다음 검토가 필요하다. 임의 1시간을 안전 증명으로 채택하지 않는다.
4. **앞부분 정리만으로 시계 점프 전체가 해결되지는 않는다.** 앞으로 튄 dump 시각과 뒤로 튄 append 시각은 별개다. 숫자 순서 검사·NTP 상태는 보조 증거이며 실제 시간의 단조성을 자동 증명하지 않는다. clock rollback 합성 반례를 보존한다.
5. **복원된 과거 발송은 취소만으로 충분한지 검수해야 한다.** #7의 definite FAILED/CANCELLED 종료는 item을 반환할 수 있고 이후 재매칭으로 다시 생성될 수 있다. 미확정 작업을 provider가 보내지 않았다고 단정하지 않는다. item dedup 보존, 복원 후 첫 수집/오늘 digest catch-up, 백업 이후 외부 성공의 부재를 함께 처리할 운영 선택이 필요하다. 어제 슬롯을 자동 만회한다는 새 동작은 현재 #7 계약에 없다. 실제 구현은 오늘 날짜 미선점 슬롯만 만회한다.

## 종합 방향

즉시 수정 가능한 runbook 두 결함은 별도 PR로 고친다. source 정책은 아직 확정하지 않는다. PC pull·격리 리허설을 준비하되 삭제 보관 상한/최후 복원점, 복원 중복 발송의 처리, 대장 정리 게이트를 구체적으로 검수한 뒤 사용자에게 필요한 실질 선택만 묻는다. 구현 세부마다 승인을 추가하지 않는다. PRD의 공개 보유기간을 미정·무기한으로 바꾸거나 실제 검증 없이 #17을 완료 처리하지 않는다.
