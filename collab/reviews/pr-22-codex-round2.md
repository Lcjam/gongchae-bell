# PR22 백업 수정 2차 검수

2026-11-18 root Codex가 agy의 최종 head `8c5da2c247f232865c2ed20dfdbb6554ce83eeb4`를 검수했다. 핵심 수정 `8501539adf5c949b1c68d03c6c4d8ed726e6aec5` 뒤 main 배포 소스 통합과 결과 문서 변경이 이어졌다.

## 판정

소스 수정은 수용한다. 기존 root·Claude 검수의 보관 정리, 로그 마스킹, 해지 대장 설정 해석·설치 설명 요청을 반영했다. main 통합 후 운영 백업·복원은 #16·#17에서 계속 검증한다.

## 확인

- restic forget이 host,tags로 같은 정책을 적용하므로 기존 서로 다른 경로 snapshot도 정리 대상이 된다. 고정 stage는 flock 안에서 준비되고 기존 소유자·심볼릭 링크·권한을 검사한다. 작성자의 실제 PG/restic 통합 시험은 3→2→1 정리와 5행 복원을 확인한다.
- 마스킹은 ENVIRON의 고정 문자열로 입력의 미처리 부분만 진행한다. 치환 결과를 다시 검색하던 무한 반복과 awk 인자의 역슬래시 해석을 제거했다. root가 합성 R·역슬래시/메타문자·접두사 중복을 종료 상한으로 시험한 진단은 exit 0과 `REDACTION_REVIEW_PASSED`를 반환했다.
- ALERTS_ENABLED의 따옴표·대소문자와 해지 대장 부재 실패 회귀를 보강했다. 초기 실패 미수신 감지, Docker 그룹, 실제 파일 설치 경로가 문서·systemd 실행 경로와 맞는다.
- `8501539a` 이후 백업 source·test·운영 문서는 바뀌지 않았다. main 통합은 CI의 배포 검증 잡을 보존하고 Runbook의 실제 백업 문서 링크를 연결했다.
- 최종 head CI `44282472364`를 root가 직접 조회했다. test·deploy-verify·backup-verify·secrets 전 잡이 completed/success다. 새 main `ed3f28f`에 대해서도 GitHub MERGEABLE/CLEAN을 확인했다. [최종 CI](https://github.com/Lcjam/gongchae-bell/actions/runs/44282472364)

별도 Sol-high 비작성자가 최종 불변 head를 독립 검수했다. 전체 단위 대역, 실패 단계별 stage 정리, stage 소유자·링크·권한, 실제 restic 다중 경로 3→2→1과 합성 파일 복원을 격리 실행해 수용했다. root는 그 원문 결과와 직접 확인한 수정 차분을 대조했다. [독립 2차 검수](pr-22-sol-round2.md)

## 운영 후속

실서버 restic 설치·OCI 저장소·사용량·자동 타이머·성공/실패/미수신 경보는 아직 실행하지 않았다. 달력 14일과 keep-daily 14의 차이, OCI 밖 사본 및 해지 대장 정합성은 #17의 확정·복원 검증 항목이다. 이 PR의 소스 통합으로 실제 운영 검증 완료를 표시하지 않는다.
