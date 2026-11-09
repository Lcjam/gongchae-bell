# ADR-0005 무중단 배포: Caddy upstream 명시 전환

- **상태**: 채택 (2026-11-08)
- **맥락**: PRD G4는 배포 중 고정 외부 시험에서 실패 0건을 요구한다. 서버는 단일 VM이다.
- **선택지**: 두 upstream을 상시 등록하고 헬스체크로 전환 / **upstream 명시 전환 + reload** / Docker Swarm
- **결정**: `deploy.sh`(flock 잠금)가 다음을 수행한다.
  1. 비활성 색 기동
  2. 내부 readiness와 핵심 화면 확인 (실패하면 새 색 중지 = 롤백)
  3. Caddy upstream을 새 색으로 바꾸고 `caddy reload`
  4. G4 외부 시험 (실패하면 upstream을 옛 색으로 복귀)
  5. 옛 색 SIGTERM (graceful shutdown)
  6. 활성 색 기록
- **결과**
  - 전환 시점이 명확하다. reload가 실패하면 Caddy는 기존 설정을 유지한다([공식](https://caddyserver.com/docs/api#post-load))
  - 헬스체크 방식은 커뮤니티에 "배포 직후 첫 요청 실패" 사례가 있어서 쓰지 않는다 (claude 초안 철회)
  - 프록시 무중단이 서비스 전체의 무중단을 뜻하지는 않는다. 진행 중 요청과 DB 호환성은 graceful shutdown과 확장 방향의 Flyway 변경(이전 버전 호환 테스트)으로 지킨다
  - 고가용성은 범위 밖이다
