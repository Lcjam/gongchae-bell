# API Fixtures

이 디렉터리는 외부 API를 반복 호출하지 않고 수집·정제·보안 로직을 검증하기 위한 응답 원본 및 테스트용 fixture를 보관한다 (CLAUDE.md DoD, PRD §6 테스트).

## 파일 목록

| 파일 | 출처 | 취득일시 (KST) | 설명 |
|---|---|---|---|
| [`alio-public-list-sample.json`](alio-public-list-sample.json) | `https://opendata.alio.go.kr/new/odaApiMng/recrutInquiryAjaxList.do?ongoingYn=Y&pageNo=1&numOfRows=10` | 2026-11-14 17:17:18+09:00 | 인증키 없이 접근 가능한 ALIO 공식 공개 채용조회 엔드포인트에서 1회 취득한 원본 JSON 응답 (`resultCode=200`, `totalCount=497`, 표본 10건) |

## 스키마 및 파싱 주의사항 (실측 확인)

1. **래퍼(Wrapper) 구조 차이**
   - 공개 AJAX 경로(`opendata.alio.go.kr/.../recrutInquiryAjaxList.do`): 최상위에 `{"data": {"result": [...], "resultCode": 200, "totalCount": 497, "resultMsg": "성공했습니다."}, "pathParam": "recrut"}` 형태로 `"data"` 래퍼가 있다.
   - 공공데이터포털 OpenAPI(`apis.data.go.kr/1051000/recruitment/list`)의 봉투 구조는 저장된 실제 원본이 없어 아직 검증하지 못했다. 이전 문서의 필드 일치 기록만으로 래퍼 유무까지 확정하지 않는다.
   - 단위 테스트에서 `"data"` 내부 객체를 추출한 파생 fixture는 변환 사실을 명시한다. 공식 API 봉투 검증은 키가 설정된 뒤 1회 응답 확보로 별도 수행한다.

2. **설명 필드 매핑 (`result[*]` 내부 30개 필드)**
   - 자격: `aplyQlfcCn` (결격사유 `disqlfcRsn` 별도 존재)
   - 우대: `prefCondCn`(우대조건 요약)과 `prefCn`(우대내용 상세) **두 필드가 모두 존재**함 (`recrutPblntSn=307027`, `307028` 등 확인)
   - 전형 설명: `scrnprcdrMthdExpln`
   - 다중 코드 및 명칭: `ncsCdLst`/`ncsCdNmLst`, `hireTypeLst`/`hireTypeNmLst`, `workRgnLst`/`workRgnNmLst`, `acbgCondLst`/`acbgCondNmLst` (쉼표 `,` 구분, NCS 명칭 내부 구분자는 마침표 `.` 사용)

3. **원문 링크(`srcUrl`) 실제 사례**
   - 비표준 HTTPS 포트를 사용하는 정상 공고 존재: `recrutPblntSn=307028` (`https://www.knuch.kr:442/content/04info/11_01.asp?...`)
   - 기관 채용 메인/목록으로만 연결되는 공고 존재: `recrutPblntSn=307027` (`https://foodinfo.career.co.kr/`), `307032`·`307022` (`https://www.knuh.kr/content/04information/02_01.asp`)
