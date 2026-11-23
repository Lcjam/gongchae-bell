# PR38 설치 전 후속 재검수 · root Codex · 3라운드

**판정: 수용.** 고정 최종 head `359e85c401ce0598a23c041ffddb46380de48f03`, 소스 `c174e4e4039aab3d5b08e87d3ccd09b9bf4131b3`. 이전 수용 head `d4ee65d4e116a8c6390e0ac12e80dbed3ef6d949` 이후 변경을 검수했다. 최종 head는 소스 head에 결과서 1파일만 추가했다. 실제 PC 설치·작업 스케줄러·OCI 연동·복원 완료 판정은 아니다.

## 수정 확인

- 운영 env에 시험 전용 시각·로컬 source 키가 있으면 `Load-Config`가 lock/선만료 전 거부한다. env 안의 harness 표지 자체도 거부한다. 프로세스 표지에만 의존하는 명시적 시험 경로이며 실제 사용처도 `$isTestHarness`를 확인한다. 운영 기본값은 실제 UTC와 S3 원본이다.
- 시험 helper는 상속된 프로세스 표지를 먼저 제거하고 명시적 `-TestHarness` 자식에만 추가한다. Case 1g는 실제 1시간 전 사본과 30일 뒤 시각을 준비하므로 게이트 퇴행 시 유효 사본이 삭제되어 실패하는 회귀다. snapshot 보존과 성공 heartbeat 부재 단언은 첫 now 사례에 있으며 나머지 사례는 각 거부 문구를 확인한다.
- 8.3 단축 구성요소는 환경변수 확장 뒤 원시 경로에서 먼저 거부하고 정규화 경로에서도 확인한다. `GetFullPath`가 별칭을 확장하는 runtime에서도 원시 검사가 먼저 적용된다. repo와 보조 경로는 기존 공통 검증을 유지한다. 실제 OS 별칭 repo/log 거부와 생성 부재, 결정적 lexical 사례, 정상 긴 경로 init을 새 Case 1h에서 확인한다.
- 시험 임시 경로 변경은 긴 LOCALAPPDATA Temp를 선호한다. `icacls /T`의 대상은 시험 소유 트리이며 deny 전 복원 목록에 등록한다. 기존 source 읽기 전용 copy·lock 6a/6b·선만료/후만료·14일 초과 전량 삭제/0개 허용·오류 시 성공 heartbeat 금지를 유지한다.

## 직접 확인한 증거

2026-11-22 14:09 UTC 전후의 직접 조회다. 작성자 결과서의 사후 CI 주장과 구분한다.

- 최종 359의 8개 check 모두 `COMPLETED/SUCCESS`, `MERGEABLE/CLEAN`. CI [45030119688](https://github.com/Lcjam/gongchae-bell/actions/runs/45030119688), Monitoring [45030119702](https://github.com/Lcjam/gongchae-bell/actions/runs/45030119702).
- 최종 [pc-pull job 137231646160](https://github.com/Lcjam/gongchae-bell/actions/runs/45030119688/job/137231646160)의 원본 로그에서 PS `5.1.26100.33822`와 `7.6.10` 각각 실제 8.3 OS 별칭 사례와 10개 전체 시험, `ALL_PC_PULL_TESTS_PASSED`를 직접 읽었다. 로컬 pwsh를 실행한 것은 아니다.
- ignored `.tools/review/nov22-pr38-path-round3.ps1`은 고정 359의 실제 경로 함수만 AST로 추출한다. 로컬 PS5.1에서 조상 junction(leaf 존재/부재), sync root로 향하는 junction, OneDrive 보조 env, UNC cache, repo/cache 중첩, ONEDRI~1·GOOGLE~1을 거부하고 정상 긴 경로를 생성 없이 허용했다(exit 0). 자기 임시 트리와 프로세스 OneDrive 값만 사용하고 junction을 비재귀 제거한 뒤 정리했다. sandbox 계정의 첫 `-File`은 실행 정책으로 차단되어 검증 미실행이었다. 정상 사용자 계정의 같은 명령을 정상 승인 경로로 실행해 성공했으며 실행 정책은 변경하지 않았다.

Claude 독립 R3 결과도 수용이다(`pr-38-claude-round3.md`, worker_done msg_20a59615d416). 정상 긴 이름 `backup~2024`도 거부하는 보수적 오탐, 의도적으로 영구 상속시킨 harness 환경의 로그 부재, 작성자 결과서가 자기 결과 커밋 CI를 미리 확인한 듯 적은 기록 문제는 비차단 한계로 유지한다. 최종 359의 CI 성공은 여기와 Claude의 사후 직접 조회로 입증한다. 근거 없는 과거 관측으로 소급하지 않는다.

## 미실행과 한계

root는 전체 restic/Gradle 시험을 중복 실행하지 않았다. 실제 PC 영구 설치·ACL·작업 스케줄러·OCI/IAM/버킷/키·외부 heartbeat·복원 리허설은 하지 않았다. `subst`와 관리자 지정 별칭 전수 방어도 검증하지 않았다. PC 오프라인/강제 종료 시 물리 삭제 지연, InRun 이전 실패의 fail ping 부재, heartbeat 미설정 동작은 남는다. 사용자 확정 14일 정책과 공개 출시의 나머지 gate는 그대로이며 #17을 닫지 않는다.
