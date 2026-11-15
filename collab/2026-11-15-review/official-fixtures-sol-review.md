# 공식 API fixture 독립 검수 · sol

2026-11-15. 조율 후속 검수. 대상은 `C:/Users/lcj/orca/workspaces/my-project/official-fixtures`의 **`b95fa67dd7e17fd1a14d5d9aa0e93173865fae93`**이다. 원본 `CLAUDE.md`, `agy-result.md`, `fixtures/README.md`, 공식 fixture 3개와 ignored `.tools/CaptureOfficialFixtures.java`를 읽었다. 본문은 `git show b95fa67:<path>`로 고정하고, 작업 복사본 및 ignored captured 원본과도 바이트 일치를 확인했다.

**판정: 저장 본문 구조·건수, 영속 호출 기록과 fixture 비밀값 비노출 확인은 수용한다. 필수 정정은 보고서의 로컬 커밋 여부 1건이다.** 추가 API 호출, 앱·설정·테스트·env·브랜치 수정, push·merge는 수행하지 않았다. 작성한 파일은 이 리뷰 하나뿐이다.

## 보관 본문에서 직접 확인한 사실

| fixture | 직접 파싱한 결과 | 크기 |
|---|---|---:|
| `alio-official-list-sample.json` | 최상위 키는 resultCode/resultMsg/totalCount/result. resultCode=200, totalCount=497, result 배열 497건, 고유 SN 497개, SN 307032 포함. 모든 원소는 item 래퍼 없는 30필드 객체이고 data/pathParam 최상위 래퍼는 없다. 497건 모두 steps/files는 빈 배열이다. 필수 식별·기관·제목 및 두 날짜 형식 검사 오류 0건. | 2,019,335 bytes |
| `alio-official-detail-sample.json` | resultCode=200, result는 래퍼 없는 30필드 단일 객체, recrutPblntSn=307032, ongoingYn=null. steps 4건과 files 3건 모두 객체 직접 배열이며 step/file 래퍼가 없다. | 2,583 bytes |
| `alio-official-list-empty-sample.json` | resultCode=200, totalCount=0, result=[]이고 data/item 래퍼가 없다. | 79 bytes |

위 건수·크기는 보고서 숫자를 재인용한 것이 아니라 커밋된 본문을 읽어 다시 계산했다. 세 fixture는 작업 복사본 및 `.tools/captured/official-{list,detail,empty}.raw.json`과 각각 바이트가 같다.

본문 SHA-256:

- list: `cadbd3fc5d14893b3df85e42b0047ac576a488b2082c740a87e65885510e7c05`
- detail: `ebb8af2cf829bdacd326770c99893e6e8ad0389b17c3b5df91f44d5463e7aa7c`
- empty: `2d6c6be79d3a34eab379a2c60b26b6846157c761aaef30556d315810e6676140`

상세 표본의 steps는 sortNo=0 두 행, sortNo=1 두 행이다. 두 그룹에서 `recrutPbancTtl`은 각각 `진료교수`, `진료의사`이고, recrutNope/aplyNope/rsnOcrnYmd는 모두 null이다. 각 그룹은 cmpttRt=null 행과 cmpttRt=0.0 행을 갖는다. 파일 3건은 atchFileNm/url을 모두 갖고 URL은 HTTPS ALIO 첨부 다운로드 경로다. 보고서의 이 관측은 본문과 일치한다.

## 영속 DB에서 직접 확인한 기록

최초 sandbox 조회는 Docker 엔진 접근 권한으로 실패했다. 이후 허용된 읽기 실행에서 기존 컨테이너 `gongchae-bell-dev-postgres-1`의 psql을 이용해 SELECT만 수행했다. 비밀번호나 환경 전체를 출력하지 않았다.

- `api_budget`: `2026-11-15`, count=**4**.
- `flyway_schema_history`: V1/V2/V3 모두 success=true.
- `api_attempt`: 아래 **4건**, 모두 attempt_no=1이며 started_at/finished_at이 존재한다.

| id | kind | started_at (UTC) | finished_at (UTC) | outcome |
|---:|---|---|---|---|
| 1 | REGULAR | 2026-11-15 05:35:22.114541+00 | 2026-11-15 05:35:22.597067+00 | HTTP_STATUS |
| 2 | REGULAR | 2026-11-15 05:41:41.93117+00 | 2026-11-15 05:41:45.228328+00 | SUCCESS |
| 3 | DETAIL | 2026-11-15 05:41:45.383379+00 | 2026-11-15 05:41:45.602367+00 | SUCCESS |
| 4 | BACKFILL | 2026-11-15 05:41:45.64758+00 | 2026-11-15 05:41:46.008258+00 | SUCCESS |

보고된 시각과 결과가 모두 일치한다. helper는 RecruitmentClient/ApiBudget을 생성해 REGULAR list → SN 추출 → detail → 지정 빈 기간 BACKFILL list 순서로 호출한다. 기존 client 코드에서 SUCCESS는 HTTP status=200 분기에만 기록된다. 따라서 **보관 JSON의 resultCode=200**과 **DB의 SUCCESS 및 client 경로로 뒷받침되는 HTTP 200 측정 기록**은 서로 구분되는 근거다. raw HTTP 상태줄·응답 헤더 자체가 fixture에 저장된 것은 아니다. 첫 HTTP_STATUS의 정확한 상태 번호는 기록만으로 복원할 수 없다.

## 비밀값 검사

원본 root `.env`는 검사 프로세스 메모리에서만 읽었다. 값·접두사를 출력하거나 파일로 쓰지 않았다. 다음 형태를 실제 값으로 만들어 검색했고 결과에는 개수·범위만 남겼다.

- 서비스 키 원본, percent-decode 1회 결과, 원본/디코딩 값을 재인코딩한 형태, 각 형태의 16자 접두사.
- DB_PASSWORD 및 DB_USER의 정확한 값.

**공식 fixture 3개 및 ignored captured 본문 3개에서 위 모든 값 0건**이다. 커밋 b95fa67의 전체 추적 파일 140개와 커밋 메타데이터에서도 서비스 키 변형·접두사 및 DB_PASSWORD는 0건이다. 새 커밋의 5개 변경 파일에서도 동일하다. 따라서 fixture/커밋에 키나 DB 비밀번호가 들어갔다는 증거는 없다.

DB_USER와 같은 문자열은 기존 공개 프로젝트의 패키지·DB 식별자에도 쓰인다. 전체 트리의 단순 부분문자열 검색은 이 공개 식별자들을 잡으므로 **전체 트리에서 DB_USER 문자열 0건이라고 보고하지 않는다**. 새 변경 파일 중 일치한 위치는 `agy-result.md` 9·36행의 DB 이름 설명뿐이며 자격증명 할당문이나 fixture 내용이 아니다. 이 구분 없이 단순 검색 결과를 자격증명 유출로 판단하지 않는다.

`.tools/CaptureOfficialFixtures.java`는 git ignored이며 추적 파일이 아니다. helper는 env 값의 메모리 단일 percent-decode와 기존 client의 URLEncoder 경로를 사용하고, 키를 출력하는 코드나 원본 .env를 쓰는 코드는 없다. 본문을 저장하기 전 키의 세 형태를 REDACTED로 바꾸는 방어도 있다. 다만 현재 helper와 정적 검사는 과거 실행 당시 모든 프로세스 로그의 비노출 또는 원본 .env의 실행 전후 동일성을 소급 입증하지는 않는다.

## 필수 정정

`agy-result.md` §5의 **“커밋·푸시 없이 로컬 … 브랜치에만 보존한다”**는 현재 사실과 다르다. 실제로 로컬 커밋 b95fa67이 존재하며 AuthorDate/CommitDate는 **2026-11-15 14:44:33 KST**이다. 해당 커밋은 보고서·fixtures README·JSON 3개의 총 5파일 변경이다. “로컬 커밋 b95fa67로 보존”으로 고치고, 원격 push 여부는 구현자가 확인한 사실대로 별도로 적어야 한다. 이 검수자는 push를 수행하지 않았으며 과거 push 여부를 독립 입증하지 않았다.

## 남은 불확실성·사용 범위

- 공식 list 1개 시점, detail 1개 SN, 빈 기간 1개 구간의 보관 표본이다. 다른 상세 공고도 같은 단계 의미·순서·중복을 갖는다고 일반화하지 않는다. 특히 sortNo를 사람이 이해하는 단계 번호라고 단정하거나 recrutPbancTtl을 모든 공고의 전형명으로 바꾸는 것은 이 표본만으로 정하지 않는다.
- 키 교정 전 실패와 교정 후 성공 및 helper의 한 번 디코딩 구현은 각각 확인했다. 첫 실패 원인이 이중 인코딩이었다는 해석은 정확한 HTTP 상태·게이트웨이 오류 본문이 없어 여전히 추정이다.
- helper의 현재 소스는 ignored 파일이므로 b95fa67 커밋 자체에 고정된 실행 증거가 아니다. 커밋 본문·현재 helper·영속 측정 기록이 서로 일치함을 확인한 범위다.
- 앱 파서/수집기/웹에 이 실제 fixture를 연결한 자동 테스트나 합본 흐름은 이 검수에서 실행하지 않았다. 실제 API 추가 호출도 0회다.
