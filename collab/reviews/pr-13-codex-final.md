# PR13 최종 소스 검수

2026-11-18 root Codex가 최종 통합 head `97c7415ffb67ff47fc2643ff8b1323716ce78767`를 검수했다. 기존 C1·C2·C3·C5·C6 수용 기록과 C4 재검수에 이어, `7cb0068` 이후 응답 모델·풀 오류 처리·main 통합 차분을 확인했다.

## 판정

소스 수정은 수용한다. #6의 실제 고지·발송·운영 검증은 후속 단계에서 추적하며, #7은 이 소스 통합 뒤 구현한다.

## 마지막 수정 확인

- 정상 이메일·디스코드 신청과 이메일 관리 링크 재발송의 controller가 같은 요청 시작 시각을 응답 모델의 기준 시각 조회까지 전달한다. FreshnessModelInterceptor는 해당 요청의 supplier를 쓰고, 나머지 화면은 기존 기준 시각 조회 경로를 유지한다.
- 기준 시각 조회는 풀 진입 전에 남은 몫을 확인하고 실제 SQL에 정리 마감과 요청 마감 중 짧은 제한을 적용한다. 부족·풀·DB 오류는 unavailable 표시로 처리한다. 이미 끝난 발송·상태 기록을 보존한다.
- collect_run DDL 잠금 아래 정상 신청·입력 오류·관리 링크 재발송과 예산 소진 응답을 실제 MVC로 검증한다. Future 관측 상한과 finally 잠금 해제가 있고, 확인 메일 한 통과 잠금 해제 뒤 정상 표시도 확인한다.
- 풀 연결을 받지 못할 때의 CannotCreateTransactionException을 busy 503으로 처리하고 예외 클래스만 기록한다. 발송 A/R 연결을 얻지 못한 경우에도 같은 예산에서 작업의 DEADLINE 종결을 시도한다. 정리가 계속 실패하면 기존 durable 기록·회수 경로를 따른다.
- main의 배포 구성과 통합한 application.yml에 Hikari 5000ms 상한과 native forward headers가 함께 보존돼 있다.

## 검증 근거

GitHub Actions run `44280281080`의 최종 head에 대해 `test`, `deploy-verify`, `secrets`의 completed/success를 직접 조회했다. [CI 실행](https://github.com/Lcjam/gongchae-bell/actions/runs/44280281080)

agy는 불변 919cf703을 격리 추출해 DeliveryLedgerTest 19건·SubscriptionFlowIntegrationTest 14건을 재실행했고, 이후 c005ee11과 97c7415f 차분 및 최종 CI도 추가 검수해 수용했다. root는 해당 소스·회귀 시험과 결과를 대조했다. [agy 독립 검수](pr-13-agy-render-budget.md)

## 후속 항목

503 예외 처리 응답의 푸터는 현재 기본 미수집 문구를 사용한다. 공통 모델 보강을 거치지 않는 예외 경로에서 unavailable 표현을 채우는 작은 표시 보완은 공개 전 화면 점검에 남긴다. 요청의 예산·발송·저장 동작은 검수한 범위에서 충족한다.

실제 Resend·Discord 발송, 수신함 인증 결과, HTTPS 브라우저·Origin, 공개 처리방침 운영 값, 해지 대장의 운영·복원 검증은 #6과 공개 출시 기준에서 계속 추적한다.

## 최종 문서 head 확인

2026-11-18 19:11 KST, 최종 head `2b152930dc145a377f40cb8d01a7b7cd6c4a175b`를 다시 조회했다. `97c7415` 이후 변경은 구현 결과 Markdown 한 파일뿐이다. CI `44280872190`의 test·deploy-verify·secrets 전 잡이 completed/success이고 GitHub는 MERGEABLE/CLEAN이다. 구현 1명과 독립 검수 2명의 수용 근거를 충족해 root가 main 통합을 진행한다. #6의 실제 환경 후속 항목은 열린 상태로 유지한다. [최종 CI](https://github.com/Lcjam/gongchae-bell/actions/runs/44280872190)
