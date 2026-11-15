# API Fixtures

이 디렉터리는 외부 API를 반복 호출하지 않고 수집·정제·보안 로직을 검증하기 위한 응답 원본 및 테스트용 fixture를 보관한다 (CLAUDE.md DoD, PRD §6 테스트).

## 파일 목록

| 파일 | 출처 | 취득일시 (KST) | 설명 |
|---|---|---|---|
| [`alio-public-list-sample.json`](alio-public-list-sample.json) | `https://opendata.alio.go.kr/new/odaApiMng/recrutInquiryAjaxList.do?ongoingYn=Y&pageNo=1&numOfRows=10` | 2026-11-14 17:17:18+09:00 | 인증키 없이 접근 가능한 ALIO 공식 공개 채용조회 엔드포인트에서 1회 취득한 원본 JSON 응답 (`resultCode=200`, `totalCount=497`, 표본 10건) |
| [`alio-public-list-quality-sample.json`](alio-public-list-quality-sample.json) | 같은 경로, `ongoingYn=Y&pageNo=1&numOfRows=1000` | 2026-11-14 22:22:25+09:00 | 1회 받은 497건 응답에서 **깨짐 의심 행 2건(`307009`, `306968`)만 원문 그대로 발췌**했다. 봉투의 `resultCode`, `resultMsg`, `totalCount=497`도 원본 값이다(행 수와 다르다). 두 행은 `「」`가 `?`로 바뀐 형태(`?병역법?` 등)이고, `306968`의 `srcUrl`은 스킴 없는 `www.kohom.or.kr`이다 |
| [`alio-official-list-sample.json`](alio-official-list-sample.json) | `https://apis.data.go.kr/1051000/recruitment/list?serviceKey=[REDACTED]&resultType=json&ongoingYn=Y&numOfRows=1000&pageNo=1` | 2026-11-15 14:41:45+09:00 | 공공데이터포털 공식 OpenAPI에서 1회 취득한 실제 전체 응답 본문 (`resultCode=200`, `totalCount=497`, `result` 497건, `2,019,335` bytes). 공개 AJAX 경로와 달리 `"data"`/`"pathParam"` 래퍼가 최상위에 없고 `result` 원소도 `"item"` 래퍼 없이 객체 직접 배열이다. 비밀값 0건 점검 완료 |
| [`alio-official-detail-sample.json`](alio-official-detail-sample.json) | `https://apis.data.go.kr/1051000/recruitment/detail?serviceKey=[REDACTED]&resultType=json&sn=307032` | 2026-11-15 14:41:45+09:00 | 공식 OpenAPI `detail` 1회 실제 응답 본문 (`resultCode=200`, `sn=307032`, `2,583` bytes). `result`는 단일 객체이며 `steps`(4건)·`files`(3건) 원소에 `"step"`/`"file"` 래퍼가 없다. 비밀값 0건 점검 완료 |
| [`alio-official-list-empty-sample.json`](alio-official-list-empty-sample.json) | `https://apis.data.go.kr/1051000/recruitment/list?serviceKey=[REDACTED]&resultType=json&pbancBgngYmd=2010-01-01&pbancEndYmd=2010-01-31&numOfRows=1000&pageNo=1` | 2026-11-15 14:41:46+09:00 | 공식 OpenAPI 빈 기간 조회 1회 실제 응답 본문 (`resultCode=200`, `totalCount=0`, `result=[]`, `79` bytes). 비밀값 0건 점검 완료 |
| [`alio-official-list-lower-bound-sample.json`](alio-official-list-lower-bound-sample.json) | `https://apis.data.go.kr/1051000/recruitment/list?serviceKey=[REDACTED]&resultType=json&pbancBgngYmd=2023-11-15&numOfRows=1000&pageNo=1` (`pbancEndYmd` 없음) | 2026-11-15 20:11:36+09:00 | 최초 적재 조회 계약 검증에서 agy가 1회 받은 실제 1페이지 본문(`api_attempt.id=43`, `resultCode=200`, `totalCount=36,772`, `result` 1,000건, 4,442,918 bytes = 2,329,935자, SHA-256 `3698d868…6b83`). SN 내림차순 307032~305961, 시작일 2025-07-22~2027-01-18. 2026-11-15보다 늦게 시작하는 행 4건(306937~306940), 시작월≠마감월 374건, 2026-11-15 이후 마감 381건. claude가 agy의 ignored 보관본을 바꾸지 않고 복사했고, 저장소 `.env`의 API 키(원문·디코딩·인코딩·대소문자 16진수 표기 9종)와 DB 비밀번호·사용자명이 본문에 0건임을 값 출력 없이 확인했다 |

같은 497건 응답의 실측(분석만 하고 전체는 저장하지 않음, Python `urlsplit`으로 계산해서 Java `URI`와 경계 사례가 다를 수 있음): 필수 필드 누락과 날짜 형식 오류 0건, `sn` 중복 0건, `broken_text` 규칙에 걸리는 공고 10건(U+FFFD는 0건), `srcUrl`이 http(s)로 파싱되지 않는 값 19건. http(s) 478건 중 같은 기관 안에서 URL을 공유하는 공고 210건, 루트 경로 142건, 둘 중 하나에 해당해 `src_url_generic`이 되는 공고 249건(497건 중 50%).

수집 테스트(`ListFixture`)는 이 두 파일을 읽어 `"data"` 래퍼만 벗기고, 마감일·코드 순서·`totalCount` 같은 값을 코드에서 바꿔 사례를 만든다. 손으로 쓴 응답 파일은 두지 않는다. 예외: 게이트웨이 오류 응답(비 JSON)은 실물을 확보하지 못해 테스트 안의 합성 문자열로 둔다.

## 스키마 및 파싱 주의사항 (실측 확인)

1. **래퍼(Wrapper) 구조 차이**
   - 공개 AJAX 경로(`opendata.alio.go.kr/.../recrutInquiryAjaxList.do`): 최상위에 `{"data": {"result": [...], "resultCode": 200, "totalCount": 497, "resultMsg": "성공했습니다."}, "pathParam": "recrut"}` 형태로 `"data"` 래퍼가 있다.
   - 공공데이터포털 OpenAPI(`apis.data.go.kr/1051000/recruitment/list`, `detail`): 2026-11-15 실제 응답 확보 결과, **`"data"`·`"pathParam"` 래퍼 없이** 최상위에 `{"resultCode": 200, "resultMsg": "성공했습니다.", "totalCount": ..., "result": ...}` 형태로 내려온다. `list`의 `result` 배열 원소와 `detail`의 `steps`/`files` 원소 모두 `"item"`/`"step"`/`"file"` 래퍼가 없다.
   - 빈 기간 조회(`2010-01-01~2010-01-31`)는 `{"resultCode":200,"resultMsg":"성공했습니다.","totalCount":0,"result":[]}`를 반환한다.

2. **설명 필드 매핑 (`result[*]` 내부 30개 필드)**
   - 자격: `aplyQlfcCn` (결격사유 `disqlfcRsn` 별도 존재)
   - 우대: `prefCondCn`(우대조건 요약)과 `prefCn`(우대내용 상세) **두 필드가 모두 존재**함 (`recrutPblntSn=307027`, `307028` 등 확인)
   - 전형 설명: `scrnprcdrMthdExpln`
   - 다중 코드 및 명칭: `ncsCdLst`/`ncsCdNmLst`, `hireTypeLst`/`hireTypeNmLst`, `workRgnLst`/`workRgnNmLst`, `acbgCondLst`/`acbgCondNmLst` (쉼표 `,` 구분, NCS 명칭 내부 구분자는 마침표 `.` 사용)

3. **원문 링크(`srcUrl`) 실제 사례**
   - 비표준 HTTPS 포트를 사용하는 정상 공고 존재: `recrutPblntSn=307028` (`https://www.knuch.kr:442/content/04info/11_01.asp?...`)
   - 기관 채용 메인/목록으로만 연결되는 공고 존재: `recrutPblntSn=307027` (`https://foodinfo.career.co.kr/`), `307032`·`307022` (`https://www.knuh.kr/content/04information/02_01.asp`)

## 공식 OpenAPI 검증 상태 (`2026-11-15`)

- **1차 시도 (`14:35:22 KST`)**: `.env` 원문 그대로 호출 시 `LIST_FAILURE=HTTP_STATUS` (`api_attempt.id=1`, `outcome=HTTP_STATUS`)로 종료되었다.
- **2차 시도 (`14:41:41~14:41:46 KST`)**: 원본 `.env` 수정 없이 메모리에서 유효 `%XX` 시퀀스만 1회 UTF-8 디코딩(평문 `+` 유지)해 `CollectorProperties`에 주입한 뒤 `RecruitmentClient` + `ApiBudget`으로 `list` (`id=2`), `detail(307032)` (`id=3`), 빈 기간 `2010-01-01~2010-01-31` (`id=4`) 모두 `HTTP 200` (`resultCode=200`, `outcome=SUCCESS`)을 확인하고 본문을 `alio-official-*.json`으로 저장했다.
- **날짜 조건 검증 (`20:11 KST`, 2회)**: `pbancBgngYmd=2023-11-15&pbancEndYmd=2026-11-15` 1페이지는 `totalCount=36,277`, 2026-11-15 이후 마감 0건이다. `pbancEndYmd`를 뺀 1페이지는 `totalCount=36,772`로 그런 공고 381건을 포함한다. `pbancEndYmd`가 마감일 상한처럼 동작하므로 최초 적재는 시작일 하한만 보낸다(root 작업 경로 기록 `collab/2026-11-15-backfill/agy-query-contract.md`). 뒤쪽 본문만 `alio-official-list-lower-bound-sample.json`으로 저장했다.
- 상세 기록 및 공개 AJAX vs 공식 OpenAPI provenance 구분: [`collab/2026-11-15-api-verification/agy-result.md`](../collab/2026-11-15-api-verification/agy-result.md).
