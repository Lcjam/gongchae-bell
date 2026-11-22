# PR30 최종 차분 검수 · Codex

2026-11-20, 비작성자 root Codex. 실제 code/doc `e93a8c5872e13778da14dd0268653dc9f9a70992`와 결과만 추가한 최종 `d4985adb2bf93afb1a2b2f669793ae3487d16172`를 확인했다. **source/문서 수용**, 최종 CI 전부 성공을 확인하기 전 병합하지 않는다.

D1 문서는 제거된 BACKUP_KEEP_DAILY를 env 표에서 없애고 기존파일 줄 삭제·잔존시 거부를 설명한다. 실행시각14일초과·정각유지·최후0개허용과 선만료→매번prune0→재조회→dump/upload 순서가 source와 같다. 자격/잠금/네트워크/prune 오류와 PC오프라인 동안의 물리 잔류를 준수/삭제완료로 기록하지 않는다.

D2는 ADR0017과 운영문서§2/후속항목에 모두 반영됐다. 네트워크 copy 전에 로컬 만료·prune·재조회, copy 뒤 원래snapshot시각 기준 재정리/확인으로 copy실패일의 잔류와 오래된 사본 재보관을 구별한다. PC 구현이나 실제 운영 설치가 이미 됐다고 쓰지 않는다.

backup.sh source 차분3줄은 snapshots/select 실패를 retention state unverified, forget 실패를 snapshot deletion state unverified라고 기록한다. 삭제 전·후 같은함수의 오류에서도 앞서 이뤄진 삭제나 부분 성공을 부정하지 않는다. 정책/동작/오류코드는 바뀌지 않았다. 원 selector와 시험/초기source는 보존됐다. PR27의 격리복원·권한·M1 3조건·전환직전 최신대장 재적용은 통합됐고 그 뒤에는 보관 관련부분만 수정했다.

root 직접 확인: 22507a 문서수정, 29ac2aa..e93a8c5 전부3파일 차분, 최종 e93a8c5..d4985ad가 결과MD1개만 +32줄임을 git diff로 확인했다. 새 결과파일의 수치/범위를 원래 보고/실제 조회와 대조했다. Sol R2도 독립 수용했다. 작성자가 돌린12/12와 원격e93CI는 보고 인용이며 root의 최종tip CI 조회를 대신하지 않는다.

기존 flags=false를 웹해지 제공으로 읽히게 하는 설명·배포경쟁/barrier·권한·CLI coverage·복원 sender/item 범위는 이미 recovery07/08 및 별도 #17 구현으로 넘긴 기존조건이다. 이 PR의 선만료와 오류문구 보완에서 전체 복원안전이 완성됐다고 주장하지 않는다. 실서버/PC/키/provider를 접근하거나 변경하지 않았다.
