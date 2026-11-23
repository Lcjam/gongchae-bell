# PR38 재검수 · root Codex · 2라운드

**판정: root 검수 수용.** 고정 최종 head `d4ee65d4e116a8c6390e0ac12e80dbed3ef6d949`, 수정 source `96d54b175d217f01fcd4d3252cd1866db5decb35`, 이전 `6b9b7f80dcc5b1a686da87737e02136afba5c812` 대비5파일(+641/-142)이다. source·시험·CI·문서와 결과서 diff를 읽었다. 독립 두 번째 검수와 root의 병합 직전 exact-head 확인은 별도로 필요하다.

## 이전 지적의 대응

1. **경로 P1/P2 해결:** 공통 `Assert-SafeLocalPath`가 파일 읽기/로그 활성화/lock 생성/외부호출 전에 repository·env·PC password·cache·log·lock·selector를 검사한다. leaf뿐 아니라 모든 기존 조상의 reparse point를 거부하고, UNC/device path·네트워크/비NTFS·볼륨조회실패·sync/Git/경로중첩을 차단한다. source password의 경로/읽기는 로컬 선만료 뒤로 남겨 자격 문제 때문에 선만료를 건너뛰지 않는다. `PC_ALLOW_CUSTOM_PATHS`는 제거 및 설정 시 거부다. selector의 개발/CI Git 예외와 운영 runtime 복사 계약은 명시돼 있다.
2. **root의 기존 반례를 실제 Windows NTFS에서 재확인:** ignored `.tools/review/nov22-pr38-path-round2.ps1`는 고정 Git blob의 실제 함수만 AST로 추출해 실행했다. 기존 leaf/미생성 deep leaf 아래 조상junction, OneDrive 대상에 이르는 junction, 보조 env의 OneDrive, UNC cache, repo/cache중첩이 각각 기대한 오류로 차단됐다. junction먼저 비재귀 제거→절대 Temp target 검증→자기 fixture 정리 순서를 사용했다. 운영 PC 경로/ACL/사본은 건드리지 않았다. 이전 우회 성공 probe와 새 차단 probe는 별도로 보존했다.
3. **PS7 P2 해결:** Framework전용 File.GetAccessControl 대신 표준 `Get-Acl -LiteralPath`다. final-head CI45003458616의 pc-pull-verify job137149798492 원본로그에서 **5.1.26100.33822(Desktop)와7.6.10(Core) 각각10/10 ALL_PC_PULL_TESTS_PASSED**를 직접 읽었다. 두 단계 모두 기본ACL 검사이며 우회경로설정을 제거했다. root로컬에 PS7을 설치/재실행한 것으로 쓰지 않는다.
4. **copy-lock 시험 P2 해결:** 기존6a는 PRE 단계에서 실패함을 명시한다. 새6b는 실제PRE통과 후 SOURCE_COPY_START의 실제restic0.19.1 호출에만 투명shim으로 destination locks쓰기ACL을 잠깐 거부한다. 테스트는 copy단계도달·realrestic lock오류·실패·POST정리·사본0·/fail·성공heartbeat부재를 단언한다. source `--no-lock`과 destination append-lock을 구별한다. root는 이 테스트/포워딩shim을 읽고 finalCI 양쪽해당case 실행/전체성공 로그를 확인했으며 realrestic전체시험을 별도로 중복 실행하지 않았다.
5. **14일 문서 P2 해결:** OCI소실일 때도 먼저 로컬PRE/POST 정리·prune성공을 확인하고 남은14일이내사본만 복원한다. 남은0개허용/만료사본 예외복원불가는 확정사용자결정과 같다. source단계실패와local파기성공의 비정상종료/로그를 구분한다.

## CI 및 범위

최종 d4ee65d의 **CI45003458616(5잡)·Monitoring45003458589(3잡), 8/8 COMPLETED/SUCCESS·MERGEABLE/CLEAN**을 직접 조회했다. source96d의옛8CI만을 최종tip근거로 쓰지 않았다. `Load-Config`·`Invoke-CmdRun`·source자격검사/복사·POST정리·heartbeat·outercatch/finally 순서도 읽었다. 복사 실패후 POST정리와 failheartbeat, source전선만료 계약을 바꾸지 않았다.

실제 OCI/IAM·버킷·PC영구설치·TaskScheduler/ACL·deadman 체크·정기복사·격리복원은 이번소스 검수로 완료되지 않는다. PC오프라인/강제종료시 즉시파기·POST완료를 보장하지 못하며 다음실행선만료와외부미수신경보에 의존한다. 이 한계를 보존하고 #17을 PR소스병합만으로 닫지 않는다.
