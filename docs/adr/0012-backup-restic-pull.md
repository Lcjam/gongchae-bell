# ADR-0012 백업: restic + OCI Object Storage + NAS·PC pull

- **상태**: 채택 (2026-11-08, OCI 밖 위치는 사용자 결정)
- **맥락**: PRD는 일 1회 백업, OCI 밖 사본, 복원 리허설을 요구한다. 백업에는 이메일 주소가 들어 있다. OCI 밖 사본은 사용자의 NAS나 PC에 두기로 했다(U11).
- **선택지**: age + rclone / **restic** · 서버가 집 장비로 보내는 push(SFTP, 집 쪽 포트 개방 필요) / **집 장비가 가져가는 pull**
- **결정**
  - 서버(호스트 cron, 매일 03:00): `pg_dump -Fc` + `erasure.log`(ADR-0013) → **restic**으로 OCI Object Storage 저장소에 저장한다(S3 호환, Customer Secret Key). `restic forget --keep-daily 14 --prune`. 성공하면 Healthchecks 핑
  - **NAS·PC**(스케줄 작업): `restic copy`로 OCI 저장소에서 로컬 저장소로 가져온다. 집 쪽 포트를 열지 않는다. 성공하면 별도 Healthchecks 핑 → 며칠 동안 오지 않으면 경보
  - restic 저장소 비밀번호는 서버 환경변수와 사용자의 비밀번호 관리자에 따로 보관한다. 이 비밀번호가 없으면 복구할 수 없다
- **결과**: 도구 하나로 암호화, 중복 제거, 보관 정리를 처리한다. NAS·PC가 꺼져 있으면 OCI 밖 사본이 늦어진다(경보로 감지). OCI Object Storage는 Always Free 전용 상태 기준 합계 20GB, 월 요청 5만 회 안에서 쓴다(03). 실제 연동은 아직 시험하지 않았다 → 첫 복원 리허설에서 확인한다.
