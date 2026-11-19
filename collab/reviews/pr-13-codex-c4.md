# PR13 C4 재검수

2026-11-18 root Codex가 head `7cb0068e1704247111e4d325faefb6a4f1b584c5`를 검수했다. 기준은 `7fa3f385773b3bcf2cb76b7a8f2766c1867cc432` 이후 변경과 13·14 구현 계약이다. PR13의 원격 head, 로컬 `issue-6-budget-fix` HEAD, clean 상태가 일치한다.

## 판정

기존 C4 세 항목과 테스트 종료 문제는 수정된 소스에서 수용한다. 응답 공통 모델을 채우는 조회 한 곳이 동기 요청 예산 밖에 남아 있어 이 경로의 수정과 검증이 필요하다.

## 수용한 변경

- 이메일·디스코드 신청 POST가 조건 옵션 SQL 전에 단조 시계를 시작한다. 옵션은 예산 안에서 한 번 조회하고 잘못된 입력의 재표시에 재사용한다.
- R의 notification 갱신·전체 발송량 예약·attempt INSERT, 신청·복구·디스코드 결과 저장은 다음 SQL 직전에 남은 시간을 다시 잰다. 정리 쓰기는 한 정리 마감과 원래 요청 마감을 공유한다.
- 풀 진입 전에 설정된 Hikari 상한이 들어갈 자리를 확인한다. 일반 단계는 최종 연결과 정리 몫을 남긴다. 무제한 풀 설정은 예산 안 새 연결을 시작하지 않는다.
- 재시도는 대기 전에 다음 시도의 몫을 확인하고, 디스코드 재시도는 sleep 뒤에도 확인한다. 이미 시행한 테스트의 예산 부족은 실패 결과로 끝나고 첫 진입의 예산 부족은 503이다.
- 잠금을 쥐는 회귀 시험은 Future 관측 상한을 두고 finally에서 잠금을 해제한 뒤 executor를 닫는다. 비활성 시험의 요청 전후 count 비교는 공유 시험 DB에서 새 쓰기가 없다는 원래 검증 의미를 보존한다.

## P2 응답 공통 모델의 기준 시각 SQL이 동기 예산 밖에서 실행됨

위치는 `src/main/java/kr/gongchae/bell/catalog/FreshnessModelInterceptor.java`의 `postHandle`과 `FreshnessService.java`의 `currentStatus`다.

신청·관리 링크 재발송의 controller가 notice 또는 입력 오류 뷰를 반환하면 `postHandle`이 모델의 `freshness` 유무를 검사한다. 현재 동기 POST controller는 이 값을 넣지 않으므로 `currentStatusSafe()`가 새 연결을 받아 `collect_run`의 마지막 완전 수집 시각을 조회한다. 이 쿼리는 신청·발송 트랜잭션 밖이고, 요청 시작 시각·남은 예산·statement/lock 제한을 받지 않는다. 앞 단계에서 `SET LOCAL`로 걸었던 제한도 이미 끝난 트랜잭션의 값이다.

따라서 발송·정리까지 25초 예산 안에 끝낸 뒤에도 정상 MVC 응답 경로가 풀을 다시 기다리거나 `collect_run`의 DDL 잠금에 계속 막힐 수 있다. SQL이 끝날 때까지 `currentStatusSafe`의 예외 폴백에도 도달하지 않는다. 기존 조건 조회 DDL 잠금 시험은 `code_name`을 잠그므로 이 응답 후반 경로를 다루지 않는다. 14 계약 §4와 SyncSender의 요청 전체 설명이 포함하는 제어 가능한 DB·풀 대기에 해당한다.

공통 데이터 기준 시각 표시를 보존하면서 동기 신청·확인 메일 재발송 응답의 해당 조회에도 같은 남은 예산을 적용한다. 예산이 부족하거나 조회가 실패하면 기존 unavailable 표현으로 응답을 끝낼 수 있다. 발송이 성공한 뒤 기준 시각 표시 때문에 중복 발송이나 상태 반전을 만들지 않아야 한다.

회귀 시험은 별도 연결에서 `collect_run`의 ACCESS EXCLUSIVE 잠금을 잡고 실제 MVC 동기 POST를 실행한다. 성공·입력 오류·예산 소진 응답의 공통 모델 단계가 제한 시간 안에 끝나는지 확인하고, Future 관측 상한과 finally 잠금 해제를 유지한다. 실제 provider는 대역을 사용한다.

이 발견은 제출된 정상 MVC 호출 경로와 SQL의 제한 설정을 직접 대조한 정적 검수다. 검수자가 별도 잠금 재현을 실행하지는 않았다. 구현자가 위 경로를 재현해 수정·회귀 근거를 남긴다.

## 확인한 CI와 후속 범위

현재 head에 연결된 CI `44226346648`의 `test`와 `secrets`, `check bootJar` 단계 success를 직접 확인했다. [CI 실행](https://github.com/Lcjam/gongchae-bell/actions/runs/44226346648)

실제 Resend·Discord 발송, 공개 HTTPS·고지·운영 검증은 기존 실행 단계에서 계속 추적한다. 위 결함은 현재 요청 경로에서 다룰 수 있는 소스 수정이며, 해결 뒤 새 head를 다시 검수한다.
