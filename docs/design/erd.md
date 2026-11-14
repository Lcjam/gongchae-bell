# ERD와 테이블

> 스키마의 원본은 Flyway 스크립트다. 이 문서는 그와 일치해야 한다. 시각은 `timestamptz`, 날짜는 `date`(KST 기준)로 저장한다.

구현 현황 (2026-11-14): `V1__catalog_and_ingestion.sql`은 공고 5개 테이블과 `collect_run`, `api_budget`을 만든다. V2는 마지막 완전 수집 조회용 부분 인덱스와 코드 유형 제약을 추가한다. 아래 `rate_limit`, 구독/발송 테이블은 아직 설계이며 후속 마이그레이션으로 구현한다.

```mermaid
erDiagram
    institution ||--o{ posting : "공시"
    posting ||--o{ posting_code : "다중값 코드"
    posting ||--o{ posting_change : "변경 관측"
    subscription ||--o{ notification : "발송 작업"
    subscription ||--o{ notification_item : "보낸 공고"
    notification |o--o{ notification_item : "묶음"
    posting ||--o{ notification_item : ""
```

## 공고
| 테이블 | 컬럼 | 제약·인덱스 |
|---|---|---|
| `institution` | `code`(원천 `pblntInstCd`), `name`, `updated_at` | PK `code` |
| `posting` | `sn`(원천 `recrutPblntSn`), `inst_code`, `title`, `bgng_date`, `end_date`, `recrut_se`, `recrut_nope_raw`, `recrut_nope`(숫자로 해석되면 값, 아니면 null), `aply_qlfc`, `pref`, `scrnprcdr`(목록 응답의 설명 필드), `src_url`, `src_url_generic`, `broken_text`, `status`(OPEN/CLOSED/CHECK), `ongoing_yn`, `from_backfill`, `content_hash`, `first_seen_at`, `last_seen_at`, `last_changed_at`, `detail_status`(NONE/DONE/FAILED), `steps`·`files`(jsonb), `detail_collected_at` | PK `sn`; FK `inst_code`; 인덱스 (`status`, `end_date`, `sn`), (`bgng_date`, `sn`), (`inst_code`, `bgng_date`) |
| `posting_code` | `sn`, `type`(NCS/REGION/HIRE/EDU), `code` | PK (`sn`, `type`, `code`); 인덱스 (`type`, `code`, `sn`) |
| `code_name` | `type`, `code`, `name` (응답의 `*NmLst`에서 채운다) | PK (`type`, `code`) |
| `posting_change` | `id`, `sn`, `observed_at`, `changed_fields`(jsonb: 필드별 이전값과 새값) | 인덱스 (`sn`, `observed_at`) |

- 해시와 변경 비교 대상: 제목, 시작일, 마감일, 채용구분, 고용형태, NCS, 지역, 모집 인원, 원문 링크, 자격, 우대, 전형 설명. `decimalDay`, 관측 시각, 배열 순서는 제외한다 (PRD FR-C4)
- 목록의 빈 `steps`/`files`는 저장된 상세를 덮어쓰지 않는다

### 공고 응답 매핑 (#3, [계약](../../collab/2026-11-15-implementation/claude-ingestion-contract.md))
| 응답 필드 | 컬럼 | 규칙 |
|---|---|---|
| `recrutPblntSn`, `pblntInstCd`, `instNm`, `recrutPbancTtl`, `pbancBgngYmd`, `pbancEndYmd` | `sn`, `inst_code`, `institution.name`, `title`, `bgng_date`, `end_date` | 필수. 하나라도 없거나 형식이 틀리면 응답 전체를 반영하지 않는다(`INVALID_ROW`) |
| `recrutNope` | `recrut_nope_raw`, `recrut_nope` | raw는 원문 문자열. 정수이거나 숫자만 있는 문자열일 때만 `recrut_nope` |
| `aplyQlfcCn`, `scrnprcdrMthdExpln`, `srcUrl` | `aply_qlfc`, `scrnprcdr`, `src_url` | 원문 그대로(공백 정리와 URL 정규화 없음) |
| `prefCondCn`, `prefCn` | `pref` | `<prefCondCn>` U+001F `<prefCn>`. null은 빈 문자열, 둘 다 없으면 null. 읽을 때 `split("\u001F", -1)` → [요약, 상세] |
| `ncsCdLst`·`workRgnLst`·`hireTypeLst`·`acbgCondLst` (+ `*NmLst`) | `posting_code`(NCS/REGION/HIRE/EDU), `code_name` | 쉼표로 나누고 정렬한다. 이름은 개수가 같을 때만 위치로 짝짓는다 |
| `ongoingYn` | `ongoing_yn` | 상태 판정에 쓴다 |

- `src_url_generic`: http(s) URL 중 경로가 비거나 `/`이고 쿼리가 없을 때, 또는 같은 응답에서 같은 기관의 다른 공고가 같은 URL 문자열을 쓸 때 true. 한 번 true면 URL이 바뀌기 전까지 유지한다. 스킴이 없거나 파싱이 안 되는 URL은 판별하지 않는다(화면이 무효 링크로 처리)
- `broken_text`: 제목, 기관명, 자격, 우대, 전형 설명에 U+FFFD가 있거나 `?` 바로 뒤에 한글 음절이 오면 true. **깨짐 의심 표시이고 복구하지 않는다**
- 비교 대상이 아닌 값(EDU 코드, `ongoing_yn`, 기관명, 품질 플래그)은 바뀌면 덮어쓰기만 하고 `posting_change`를 남기지 않는다

## 실행과 예산
| 테이블 | 컬럼 | 제약·인덱스 |
|---|---|---|
| `collect_run` | `id`, `kind`(BACKFILL/REGULAR/DETAIL/DIGEST/DEADLINE), `slot`(회차 키: 매시 시각, 날짜, 적재 월), `started_at`, `finished_at`, `result`(COMPLETE/INCOMPLETE/FAILED, 진행 중이면 null), `total_count`, `received`, `inserted`, `changed`, `closed`, `error` | **UNIQUE (`kind`, `slot`)** ← 회차 선점 ([ADR-0003](../adr/0003-schedule-dedup-by-slot.md)) |
| `api_budget` | `day`(KST), `count` | PK `day`. 요청 직전에 별도 커밋으로 증가시킨다(예약) |
| `rate_limit` | `scope`(SUBSCRIBE/RESEND/WEBHOOK_TEST/TOKEN_FAIL/SEND_TOTAL), `key_hmac`, `window_start`, `count` | PK (`scope`, `key_hmac`, `window_start`). 창이 끝나면 지운다 |

`collect_run.error`는 원본 예외 메시지나 요청 URL을 저장하지 않고 100자 이내 내부 오류 코드만 저장한다. API 키·개인정보 노출과 긴 오류 메시지로 결과 갱신이 실패하는 문제를 피한다. 수집 반영의 코드는 `NON_JSON`, `RESULT_CODE_NOT_OK`, `INVALID_ENVELOPE`(FAILED), `INVALID_ROW`, `DUPLICATE_SN`, `COUNT_MISMATCH`, `EMPTY_SNAPSHOT`(INCOMPLETE), `APPLY_ERROR`(FAILED)이다. 원천의 `resultCode` 값은 붙이지 않는다.

## 구독과 발송
| 테이블 | 컬럼 | 제약·인덱스 |
|---|---|---|
| `subscription` | `id`(UUID), `channel`(EMAIL/DISCORD), `status`(PENDING/ACTIVE/DISABLED), `email`, `webhook_enc`(AES-GCM), `webhook_key_id`, `dest_hmac`(정규화한 목적지의 HMAC), `conditions`(jsonb), `kinds`(NEW, DEADLINE), `token_version`, `confirm_token_hash`, `confirm_expires_at`, `consent_version`, `consented_at`, `age_confirmed`, `confirmed_at`, `disabled_reason`, `perm_fail_count`, `created_at`, `updated_at` | PK `id`; UNIQUE `confirm_token_hash`; 인덱스 (`channel`, `dest_hmac`), (`status`, `channel`) |
| `notification` | `id`, `subscription_id`, `kind`(EMAIL_CONFIRM/EMAIL_DIGEST/DISCORD_NEW/DISCORD_DEADLINE/DISCORD_TEST), `batch_key`(슬롯 또는 날짜), `status`(PENDING/SENDING/SENT/RETRY/FAILED/CANCELLED/UNCERTAIN), `attempts`, `next_attempt_at`, `claimed_at`, `sent_at`, `idempotency_key`, `last_error` | FK `subscription_id` **ON DELETE CASCADE**; **UNIQUE (`subscription_id`, `kind`, `batch_key`)**; 부분 인덱스 (`next_attempt_at`) WHERE `status` IN (PENDING, RETRY) |
| `notification_item` | `subscription_id`, `sn`, `kind`(NEW/DEADLINE), `notification_id` | **PK (`subscription_id`, `sn`, `kind`)** ← 공고당 한 번; FK `subscription_id` ON DELETE CASCADE; FK `notification_id` ON DELETE SET NULL |

## 상태 전이
- **공고**: `OPEN → CLOSED`(마감일 경과 또는 `ongoingYn=N`) · `OPEN → CHECK`(완전 수집에서 빠짐) · `CHECK → OPEN`(다시 나타남) · `CHECK → CLOSED`(마감일 경과) · `CLOSED → OPEN`(마감일이 오늘 이후로 바뀌고 `ongoingYn=Y`로 다시 나타남, 마감 연장). 마감일 경과는 KST 날짜로 `end_date < 오늘`이다(D-0은 진행 중). 불완전 수집과 최초 적재 페이지는 누락 판정을 하지 않는다
- **구독**: `PENDING → ACTIVE`(확인 POST, 디스코드는 테스트 메시지 성공) · `ACTIVE → DISABLED`(수신자 단위 영구 거부) · 해지와 미확인 만료는 **행 삭제**
- **발송**: `PENDING → SENDING`(선점) → `SENT` / `RETRY`(일시 실패, `next_attempt_at`) / `FAILED`(재시도 소진) · 대상이 아니게 되면 `CANCELLED` · 회수 시간이 지난 `SENDING`은 `UNCERTAIN`으로 기록하고 다시 `RETRY`

## 보존과 파기 ([ADR-0014](../adr/0014-initial-settings.md))
| 데이터 | 파기 시점 |
|---|---|
| 해지된 구독 | 즉시 삭제 (작업과 항목은 CASCADE). `erasure.log`에 구독 ID만 남는다 |
| 미확인 구독 | 확인 링크가 만료되면 삭제 |
| 비활성(DISABLED) 구독 | 비활성화 30일 뒤 삭제 (사용자가 사유를 볼 수 있는 기간) |
| `notification` | 완료 30일 뒤 삭제. `notification_item`(중복 키)은 구독이 살아 있는 동안 유지한다 |
| `rate_limit` | 창이 끝나면 삭제 |
| 백업 | 14일 (restic 보관 정책) |
