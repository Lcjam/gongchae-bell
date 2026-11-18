# PR22 백업 검수

2026-11-18 root Codex가 PR22 head `c9225fa60107679b69395a8fed33a7ca6aa1b8bf`, base `000dce555e9eb69ef3184dd5f80aa1d499a1f602`를 검수했다. 제출된 스크립트·systemd 유닛·검증과 문서를 대조했다.

## 판정

수정 요청이다. Claude가 재현한 보관 정리 결함을 소스·공식 문서로 교차 확인했고, 로그 마스킹의 무한 반복을 추가로 재현했다.

## P1 무작위 snapshot 경로 때문에 보관 정리가 작동하지 않음

`deploy/backup/backup.sh:379,419,424`는 매 실행의 무작위 스테이징 경로를 snapshot 대상으로 저장하고 기본 grouping으로 `forget --keep-daily`를 실행한다. restic은 기본적으로 host와 paths별로 나눠 각 묶음에 정책을 적용한다. 경로가 달라지면 각 묶음에 snapshot 하나씩 남아 계속 보존된다. Claude의 실제 restic 0.19.1 재현과 일치한다. [restic 보관 정책 공식 문서](https://restic.readthedocs.io/en/stable/060_forget.html)

실행마다 같은 논리적 백업 묶음에 정책을 적용하고, 기존 무작위 경로 snapshot도 같은 정책으로 정리되도록 수정한다. 실제 restic을 쓰는 통합 시험에서 별도 경로의 백업을 여러 번 만든 뒤 keep 값 축소가 snapshot 수를 줄이는지 확인한다. 해당 값과 경로·태그 기준을 문서에 맞춘다.

## P2 로그 치환 결과를 다시 검색해 무한 반복함

위치는 `backup.sh:45-49`다. `mask`가 매번 문자열 처음부터 secret을 찾고 `[REDACTED]`로 바꾼다. secret이 치환 문자열에 포함되면 방금 만든 마스킹 결과가 다시 치환 대상이 된다. 예를 들어 합성 `RESTIC_PASSWORD=R`과 입력 한 글자 `R`만으로 종료되지 않는다.

검수자는 제출 파일의 `redact_stream` 함수만 추출하고 모든 자격증명을 합성 값/빈 값으로 덮어쓴 격리 진단을 실행했다. `timeout 3s`가 exit 124로 종료했다. 외부 서비스·DB·실제 비밀값은 사용하지 않았다. 진단 파일은 ignored `.tools/pr22-redaction-review.sh`다.

이미 처리한 출력 부분을 건너뛰는 고정 문자열 마스킹으로 수정한다. 일반 특수문자, 역슬래시와 치환 표식에 포함되는 합성 값을 검증하고 시험 자체에도 종료 상한을 둔다. 기존에 지원하는 비밀번호 값의 범위에서 처리해야 한다.

## 함께 반영할 Claude 검수 메모

- `ALERTS_ENABLED="true"`·단일 따옴표·대소문자처럼 앱이 수용하는 표현도 해지 대장 필수 여부에 반영한다. M2에서 실제 대장이 없을 때 백업 성공으로 처리하지 않도록 회귀 시험을 보강한다.
- 설정·도구 확인 전에 실패해 heartbeat URL을 읽을 수 없는 경우의 감지 방식은 미수신 경보로 운영 문서에 설명한다.
- systemd 실행 사용자에게 필요한 Docker 접근 점검을 설치 절차에 맞춘다.

## 기존 정책의 정합성 확인 항목

ADR-0012는 `--keep-daily 14`를 명시한다. PRD의 보관 14일과 처리방침의 사본 파기 표현은 별도로 맞춰야 한다. 공식 문서상 keep-daily는 백업이 존재하는 날짜 수를 세므로 실패로 건너뛴 기간이 있으면 더 오래된 snapshot도 남을 수 있다. 이 코드 수정에서는 이미 채택된 명령의 grouping 결함을 해결하고, 달력 기간·OCI 밖 사본·해지 대장 정책은 #17과 공개 고지 검증에서 함께 결정한다. [restic 날짜 기반 보관 설명](https://restic.readthedocs.io/en/stable/060_forget.html)

## 확인한 범위

덤프 뒤 해지 대장을 함께 저장하는 순서, TOC 검증, restic/OCI 환경 분리, 성공 후 heartbeat, 실패 처리, 타이머 03:00 KST와 30분 서비스 제한을 확인했다. 전체 백업 통합 시험의 로컬 재실행은 수행하지 않았다.

최종 head에 연결된 CI `44227990130`의 `test`, `backup-verify`, `secrets`와 세부 단계 success를 직접 조회했다. 이 시험은 다중 실행의 실제 보관 정리와 위 마스킹 반복을 포함하지 않는다. [CI 실행](https://github.com/Lcjam/gongchae-bell/actions/runs/44227990130)

OCI 공식 문서의 Always Free 전용 합산 20GB 및 월 API 50,000회, 유료·체험 상태의 티어별 10GB 표기가 작성자 문서와 일치한다. 실제 계정 사용량과 백업 크기 검증은 운영 적용 시 수행한다. [OCI Always Free 공식 문서](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)
