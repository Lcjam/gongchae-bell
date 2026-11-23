# PR38 Windows PC pull source 검수 · root Codex

2026-11-22 비작성자. 고정 소스 **e807a342cea1d3094a4e717af56dc2e2a3d847d0**(초기f8b3171후시험보완), 9파일. **판정: 수정 요청.** 작성자 최종 결과/이후 tip·최종8CI는 별도 확인한다. 실제 PC 설치나 OCI 연동을 승인하는 검수가 아니다.

## P2 R1: 상위 junction을 통해 동기화 경로 차단을 우회한다

`pc-pull.ps1:359`의 `Assert-SafeLocalDestination`은 leaf 자체의 reparse만 거부한다. 이후 OneDrive/Google Drive·DriveInfo 검사는 정규화한 문자열 경로 기준이다. 따라서 정상 NTFS 경로처럼 보이는 `alias/replica`에서 **alias가 동기화 루트로 연결되는 junction**이면 leaf는 regular directory로 보이고 OneDrive prefix 검사도 통과한다. 실제 목표가 금지된 동기화 폴더여도 local prune/copy 대상으로 허용된다.

root는 실제 4개 함수의 AST 본문을 추출해 Windows 임시 NTFS 폴더에서 독립 probe를 실행했다. 원본 gitblob은 **46a7b25c4dfb44b856af87ddcc08def75b8038a6**으로 고정e807과일치한다. 임시 OneDrive 환경 경로를 target로 두고 alias junction 아래의 replica를 검사한 결과 parent_reparse=true, leaf_reparse=false, **accepted=true**였다. 실제 동기화/백업/삭제는 수행하지 않았고 모든 fixture는 정해진 임시 폴더 안에 만들고 junction부터 제거한 뒤 범위를 검증해 정리했다. 첫 Git marker 사례는 alias/.git 탐색으로 정상 차단됐으므로 Git 경로 검사까지 전부 실패한다고 확대하지 않는다.

수정: 기존 leaf만이 아니라 경로의 기존 조상과 실제 목표를 검증한다. 최소하게 조상의 reparse를 거부하는 방법도 가능하다. 존재하지 않는 leaf에서도 가장 가까운 기존 부모를 확인해야 한다. 같은 방식으로 drive/NTFS·sync exclusion이 우회되지 않게 하고 native Windows parent junction 회귀를 넣는다. 공개 설정에서 임의로 PC_ALLOW_CUSTOM_PATHS를 켜는 해결책은 아니다.

## P2 R2: 복구 문서가 이미 확정된 14일 초과 사본 예외를 다시 열어 둔다

`docs/ops/pc-pull-backup.md` §5 OCI 소실 복원1은 14일 초과 사본이 남아 있으면 `만료 정리 또는 복원 허용 여부 확인`이라고 안내한다. 사용자04는 보유기간 우선·다음 실행에서 **전부 삭제·0개도 허용**을 이미 선택했다. 운영자가 그 결정을 다시 확인하거나 오래된 사본을 복원 후보로 취급하는 흐름은 현재 정책과 맞지 않는다.

초과 사본은 먼저 선만료/prune/검증하고 유효 사본만 복원하는 절차로 통일한다. OCI 소실로 source 단계가 실패해도 로컬 선·후정리의 확인 근거를 구별해 안내한다. 새 사용자 정책 변경이 없는 현재 runbook에 임의 예외를 넣지 않는다. 새 복원 도구/설치 작업을 추가할 필요는 없다.

## 읽은 범위와 검증 경계

script 전반의 설정/ACL·Win32 argv 처리/child env 분리/lock·sharedselector·init/run/snapshots/heartbeat/실패 흐름, helper kept 모드, 문서/CI와 주요 native 시험 시나리오를 읽었다. local pre→source 자격 파일 검증/조회→kept fullID copy→성공/실패 뒤 post→heartbeat 순서, 매회 explicit forget+physical prune 및 재조회, 별도 source/dest password·source no-lock/destination append lock, 로그 secret 등록/redaction은 합의 방향이다. source password file을 pre 이전에 열지 않는다.

직접 실행한 것은 위 한정 경로 probe와 source blob 대조다. 작성자의 전체900행 Windows 시험·실제restic 복사/ACL·원본불변/pack삭제·scheduler·S3IAM·실서버/PC영구설치는 반복하거나 수행하지 않았다. 첫 CI조회는8잡 중 일부 진행 중이었으므로 통과를 선결론내리지 않는다. PS7 지원 문구는 실제검증된5.1 CI와 구별해 후속검수에서 확인한다. 제품 source/test/config와 작성자 branch는 수정하지 않았다.
