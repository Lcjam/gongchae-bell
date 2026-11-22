# PR30 검수 · Codex

2026-11-20, 비작성자 root Codex. 불변 대상 `ea9d0ca3bee5e433858a388d92272a0cafad763d`. **호스트 source는 수용 가능하며 예정된 문서 마무리 후 최종 HEAD 검수까지 병합 보류**다. 작성권은 agy2에게 실제 인계됐으며 원Claude는 재개하지 않는다.

직접 읽은 selector는 JSON 전체를 파싱하고 모든 항목의 full64hex ID·태그·시간대·미래 시각을 검증한 뒤에만 ID를 출력한다. 잘못된 뒷항목이 앞의 유효 ID를 먼저 내보내는 흐름이 없다. `now - snapshot_time > 14일`이며 정각 14일은 유지한다. 최신/마지막 사본 보호가 없다. null은 빈 목록으로 명시적으로 해석한다. 목록 조회는 gongchae-bell 태그에 한정하고 forget은 검증한 full ID만 받는다.

backup run은 한 번 읽은 실행 시각으로 만료→명시 ID forget→매 실행 prune --max-unused 0→같은 기준 재조회→덤프·대장·업로드 순서를 지킨다. 덤프/업로드 실패일에도 기존 만료는 먼저 수행하고 이전 prune 실패 뒤 다음 실행에 다시 물리 정리를 한다. 정리 실패는 성공 heartbeat를 막는다. 기존 pg_dump/TOC·대장 누락/잘린 행 거부·배타 잠금·마스킹 계약을 제거하지 않는다. 운영 실제 삭제는 실행하지 않았다.

Sol 독립 리뷰 `pr-30-sol.md`의 D1(제거된 BACKUP_KEEP_DAILY와 옛 keep-daily 문서)은 이미 agy2에게 맡긴 마무리 범위다. D2(PC에서 네트워크 copy 전에 로컬 만료·prune, copy 뒤에도 재정리/검증)는 사용자 결정04와 일치하는 필요한 문서 보완이다. PC source 구현을 이 PR에 추가하라는 뜻은 아니다. P3의 삭제 후 재조회 실패에서 'nothing was deleted'라는 로그는 부정확하므로 작은 표현 보완으로 전달할 수 있다. 아직 실행하지 않은 실패 주입을 재현했다고 쓰지 않는다.

작성자 12/12 단위·4개 변이·실제 restic/PG 격리 시험은 보고 인용이며 root가 이번에 다시 실행하지 않았다. Sol은 selector 제한 검증10/10을 직접 수행했다. 원래 HEAD 최종 CI44510882353·Monitoring44510882605의7잡 성공은 root가 조회했다. main27 통합·문서 보완 뒤 새 HEAD와 CI를 따로 확인해야 한다. 실제 OCI·PC 저장소/스케줄과 ubuntu1001의 대장 읽기 권한, M2 이후 복원 게이트는 이 PR만으로 완료되지 않는다.
