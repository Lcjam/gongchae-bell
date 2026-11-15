# 구현 검수 및 연동 대기 · codex

사용자 지시: 구현은 claude / agy / GPT-6.1 Sol(high), 조율자는 완료 산출물 검수와 수정 지시만 한다.

## 확인한 현재 상태

- 실행 기반 PR #8은 `94020156e983a9e1c8617290f32b63275157ce33`으로 머지됐다. 재개 역할 규칙도 CLAUDE.md에 포함됐다.
- #3 claude: `C:/Users/lcj/orca/workspaces/my-project/issue-3-ingestion-core`
- #4 sol: `C:/Users/lcj/orca/workspaces/my-project/issue-4-collector-runtime`
- #5 agy: `C:/Users/lcj/orca/workspaces/my-project/issue-5-catalog-web`
- #4에 V3 번호를 예약했다. 기존 V1/V2는 변경하지 않는다.

## #3 공개 계약 1차 검수 (구현 완료 후 수정 요청 예정)

근거: #3 워크트리 `collab/2026-11-15-implementation/claude-ingestion-contract.md` (2026-11-15 08:16 KST).

1. `pref`의 U+001F 연결 형식은 원본 데이터에 구분자가 있으면 정상 행을 거부하고, 웹 담당자에게 별도 파싱 계약도 요구한다. 두 우대 필드 보존 취지는 유지하되 화면에서 바로 읽을 수 있는 안정된 라벨/줄바꿈 표현 또는 별도 구조로 수정할 필요가 있다. 임의 제어문자 금지는 원천 API의 검증된 제약이 아니다. 실제 구현과 단위 테스트를 본 뒤 담당자에게 수정 지시한다.
2. Sol의 #4 연동 검토: `upsertPage`의 Counts만으로는 최초 적재 월의 고유 SN 수와 페이지 간 중복을 정확히 검증할 수 없다. `inserted`는 이미 DB에 있던 공고를 제외하므로 고유 수로 대신할 수 없다. #3가 검증한 SN 집합을 결과로 제공해 #4가 메타데이터에 기록하게 하는 방식을 우선한다. 같은 응답을 #4가 별도로 파싱하는 구현은 아직 승인하지 않았다.
3. `applyRegular`은 공고와 회차 COMPLETE를 같은 트랜잭션으로 마감한다. #4는 HTTP/예산 실패만 `WHERE result IS NULL` 조건으로 마감한다. 예외를 이중 마감하거나 부분 성공으로 표시하면 안 된다.

## #4 설계 검수 대기

회차 UNIQUE INSERT는 기존 ADR-0003을 유지한다. 실패한 최초 적재는 별도 수동 슬롯으로 재시작하고 완료된 월/페이지를 기록으로 확인한다. 살아 있는 서로 다른 수동 슬롯을 막기 위한 추가 advisory lock 제안은 연결 수명/프로세스 종료/재개 테스트 및 ADR 초안을 받은 후 두 교차 리뷰로 검토한다. 아직 채택 결정이 아니다.

## #5 준비안 검수 완료

연도별 모집 예정 인원과 비숫자 제외 건수, 다중 NCS의 직무별 DISTINCT 집계, 시작일 기준 오늘 공고, 전체 3년 이력 페이지네이션, 봇/내부 점검/시험 요청 분리 지적을 agy가 준비안에 반영했다. 실제 구현·브라우저·테스트 근거는 아직 검수하지 않았다.

전체 PRD 수집/웹/알림/운영과 공개 출시의 완료를 뜻하지 않는다. 구현 중인 담당자는 결과 제출까지 기다린다.

## 사용자 환경 설정 확인

2026-11-15 사용자: 원본 프로젝트 `.env`에 공공데이터포털 키를 넣었다고 알렸다. root는 `COLLECTOR_SERVICE_KEY` 이름의 비어 있지 않은 값이 있음과 `git check-ignore .env` 결과만 확인했다. 키 값은 출력하거나 이 문서에 기록하지 않았다.

수집 수정 완료 후 담당 구현자가 환경변수를 안전하게 주입하고 실제 RecruitmentClient/영속 예산 경로로 공식 목록·상세·빈 기간 응답을 제한된 횟수로 검증한다. 실제 API 호출을 대역 테스트와 구분하고 fixture에는 키/요청 URL/자격증명을 넣지 않는다. 전체 3년 적재는 실제 응답 형태와 재개 정책 검수를 마친 뒤 진행한다. 현재는 키 준비만 확인했으며 API 호출 성공이나 적재 완료를 주장하지 않는다.

### 실제 API 첫 시도 및 후속 검증

agy는 분리된 `docs/#4-official-fixtures`에서 2026-11-15 14:35 KST에 실제 `RecruitmentClient`/영속 `ApiBudget` 경로로 목록을 한 번 호출했다. 로컬 PostgreSQL의 `api_attempt.id=1`, `outcome=HTTP_STATUS`, 당일 예산 1이 보고됐다. 상세와 빈 기간은 호출하지 않았다. 공식 JSON 응답은 아직 확보하지 못했다. 원본 보고: `C:/Users/lcj/orca/workspaces/my-project/official-fixtures/collab/2026-11-15-api-verification/agy-result.md`.

root는 도우미와 클라이언트 소스를 읽어 입력 키를 클라이언트가 한 번 인코딩하는 경로를 확인했다. 저장된 키가 이미 인코딩됐다는 관측에 따라, agy에게 원본 `.env` 변경 없이 메모리에서 한 번만 디코딩해 재검증하도록 배정했다. 이는 실제 API 사용 범위 안의 입력 형식 교정이다. 사용자 재승인은 요구하지 않는다. 이전 1회를 포함해 최대 9 HTTP 시도, 모든 시도의 같은 영속 예산 기록, 오류 반복 금지, 원격 push 및 전체 적재 금지를 유지한다. 인코딩이 거절의 원인인지 여부는 후속 관측 전까지 추정이다.

### #3 v3 및 #5 재검수

root는 #3 `aab6d5b2a9b64fe4b767f8deb415c77417e4dd86`의 `parsePage` → 호출자 의미 검증 → `upsertPage` 계약과 null 본문 실패 처리를 수용했다. Sol의 독립 검수(`collab/reviews/pr-9-sol.md`)도 지정 head에서 필수 결함 없음, CI `43681733201` 성공을 확인했다. 실제 공식 응답 및 #4 호출자 선검증은 별도 게이트다.

#5 `4334af809e3d8ce97205e29dcf9a22d28fcddaa6`은 root와 Sol의 재검수에서 요청한 M1/M2/L1~L3 수정을 수용했다. CI `43656050416` 성공. #3/#4/#5 합본 검증과 공식 fixture 적용 전에는 머지 완료로 간주하지 않는다. 구현자는 계속 각자 제출 결과의 작성권을 유지한다.
