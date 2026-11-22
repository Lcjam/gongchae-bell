# PR40 검수 수정 결과 (#14·#18) · Claude

2026-11-22 root Codex dispatch `msg_b4c8e3ad71a9`(PR40 검수 수정)를 수행했다. 대상은 `nov22-runtime` / `docs/#14-nov22-runtime`, 고정 source `76f8408aa83dd8c2d4a90dac711b558d4cc0228e`이고, 근거는 `collab/reviews/pr-40-sol.md`와 root 지시다. 문서와 기존 안전 원자료만 바꿨다. 원자료 숫자, 배포, 앱·시험·설정, 사용자 frontend 원본, 계정·키·env는 바꾸지 않았다. 새 부하, 재배포, sampler, 공공 API, 실제 발송도 실행하지 않았다.

## 수정 대응

**P2(필수): TLS 오류의 시간 귀속을 좁혔다.** 요약표 "관찰"·"호스트 자원" 행과 §3 본문을 고쳤다.
- 전: "CPU가 포화된 시점과 겹치며", "호스트 CPU가 98.5%였던 표본과 겹친다"
- 후: 기록된 UTC 08:13:36Z는 두 앱 컨테이너가 함께 관측된 기동 구간(표본 28~33, 08:13:30.133~08:13:55.133Z) 안이고 Caddy reload 전이다.
  - 그 시각을 포함하는 5초 구간 08:13:35.133~40.133Z의 평균 CPU는 **65.1%(65.12563%)**다.
  - 직전 5초 구간 평균은 **98.5%(98.49699%)**다.
  - 둘 다 `/proc/stat` 차이로 낸 5초 평균이라 오류 순간의 CPU가 아니다. 내 PC와 서버의 시계 차이도 재지 않았다. 그래서 CPU 포화를 원인으로 보지 않으며, 오류 순간 CPU와 원인은 미확인이다.
- 참고 프로브 115요청 중 1건(rc=35)은 CI G4 28/28, S1·S2 오류 0과 구분해 모두 그대로 남겼다.
- 두 CPU 값은 원자료로 다시 계산했다. Sol·root 재계산과 같다.

**P3: CPU 범위 규칙, 시계 근거, 15.6ms 표현을 고쳤다.**
- S1·S2 CPU 범위는 **각 측정 단계에 완전히 들어가는 5초 구간만** 센다(측정 시작 = `finished_utc − measure_elapsed_sec`, 워밍업과 경계 구간 제외).
  - S1: 25.1%·25.0%. 처음 기록 "24~25%"는 경계 구간 24.4%를 넣은 값이라 고쳤다.
  - S2: 56.7~72.9%. 처음 기록 "57~73%"와 반올림이 같다.
- load1 최대 0.89, MemAvailable 최소 10,490MiB, 디스크 18%는 sampler **전체 창**(08:16:53~08:18:23Z) 기준이라고 명시했다.
- "±15.6ms 단위 근사치"는 "15.625ms 해상도로 양자화된 값, 검증된 정확도 아님"으로 바꿨다.
- 시계 근거는 두 갈래로 나눠 적었다.
  - **측정 당시**(S1과 S2 사이, 같은 PC·같은 `python`)의 터미널 출력 원문: `monotonic GetTickCount64() 0.015625`, `perf_counter QueryPerformanceCounter() 1e-07`, `distinct elapsed values: 13`. 파일로 저장하지 않았고 작성자 세션 기록에서 옮겼다고 명시했다.
  - **사후 조회**(2026-11-22T08:37:29Z): `python 3.12.10`, `Windows-11-10.0.26300-SP0`, 같은 clock 출력. 측정 당시 원문이라고 쓰지 않았다.
- root가 이번 검수 시점에 직접 확인한 값은 참고일 뿐이라 결과 원문으로 옮기지 않았다.

## 추가 원자료와 미보존 범위
- **추가 보존**: `collab/2026-11-22-perf/local-reference-probe.csv`. 배포 중 로컬 참고 프로브 원자료 115행이다(UTC·경로·HTTP 코드·curl `time_total`·curl rc, 비정상 1행 `08:13:36Z,/,000,0.114589,35`). scratchpad 원본과 바이트가 같다. 내부 경로·비밀값·개인정보는 없다.
- **미보존**: standalone preflight-only의 원문 JSON. 실행 때 요약만 출력하도록 걸러 저장하지 않았다. 남은 것은 요약값 `PREFLIGHT_OK` 17/17, RTT 47/63/234ms, 첫 요청 뒤 재사용이다. S1·S2 각 실행의 preflight는 각 `summary.json`에 있다.
- `claude-main-e020-deploy-result.md`의 "기록 PR" 절은 처음에 "커밋 뒤 덧붙인 절"이었다. 이번에 첫 제출 head·CI 기록과 이 파일 링크로 바꿔 함께 커밋했다.

## 변경 파일
- `collab/2026-11-19-resume/claude-main-e020-deploy-result.md`(문구 수정, 원자료 숫자 불변)
- `collab/2026-11-22-perf/local-reference-probe.csv`(신규 원자료)
- `collab/2026-11-19-resume/claude-pr40-review-fixes-result.md`(이 파일)

## 최종 HEAD·CI
(push 뒤 확인해 worktree와 root 사본에 적는다.)
