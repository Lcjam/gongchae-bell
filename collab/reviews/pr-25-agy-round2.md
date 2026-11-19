# PR25 G4 기록 최종 차분 검수 (agy · 2라운드)

2026-11-19 root Codex 요청(`collab/2026-11-19-resume/agy-g4-record-review-request.md`)에 따른 차분 검수다. 기존 1·2라운드 검수(`collab/reviews/pr-25-agy.md`, `ffc9619`)는 보존하고, PR #25(`ops/#14-runtime-evidence`) 고정 HEAD `013ec5dc278f4cbe23f33d27e126654e1f9ba96b`의 차분(`ffc9619..013ec5d`)만 대조했다. 실제 서버·키·env·provider는 접근하지 않았고 앱·테스트·구현 설정·다른 작성자 파일은 변경하지 않았다.

## 판정

**고정 HEAD `013ec5d`의 G4·사후 롤백 기록 차분을 수용한다(차단 결함 없음).**

- 차분 `ffc9619..013ec5d`는 `collab/2026-11-19-resume/claude-g4-result.md` 1개 파일(+116줄)만 추가한 문서 전용 변경이며 root 사본과 바이트 일치한다.
- 외부 G4 blue/green(`44504907458`)·사후 롤백(`44505579286`) 로그·아티팩트 수치, `AUTO_DEPLOY_ENABLED` 미해소 구분, `rollback` `deploy_id` SHA와 실제 롤백 대상(`836a3c0`) 구분, runner region 출처가 모두 GitHub Actions 실로그 및 `deploy.yml`과 일치한다.
- **최종 병합 전 확인:** 검수 시점 `013ec5d`의 `CI` `44506128462` 4잡은 `success`이며, `Monitoring` `44506128474`는 `rules`·`compose-integration` `success`, `alert-e2e` `in_progress` 상태이므로 최종 CI 완료 확인은 root와 함께 마친 뒤 병합한다. 별도 `32e71e4` 실제 운영 배포는 이 PR 범위 밖이다.

## 차분 대조 결과

### 1. 차분 범위 · 사본 일치 · CI

- **변경 범위(`ffc9619..013ec5d`)**: `collab/2026-11-19-resume/claude-g4-result.md` 1개 파일(+116줄)만 추가. 기존 `claude-runtime-result.md`·`claude-runtime-round2.md`·앱·테스트·워크플로 변경 없음. `git diff --check` 통과, `MERGEABLE`.
- **root 사본 바이트 일치**: `013ec5d:collab/2026-11-19-resume/claude-g4-result.md`와 root 사본이 9,281 bytes로 동일하다.
- **실행 전 동일성 검증**: `git diff 517eb6b..48f6878 -- .github/workflows/deploy.yml deploy/deploy.sh deploy/g4-probe.sh` 및 `517eb6b..32e71e4` 모두 0바이트로 보고서 설명과 같다.

### 2. Deploy run 로그 · `g4-probe-report` 메타 대조

- **blue/green G4 (`44504907458`, `10:21:31Z`, `workflow_dispatch`, `INPUT_SHA=517eb6b11b06a15aebeb7c0d3c94662eacd62ea7`)**:
  - Build & Push(`135493861850`, `10:21:37Z~10:22:37Z`): `sha-517eb6b...`를 재빌드해 `sha256:8fbb95543b7bd532083f7839bdb341f701ddeceafdbc877454d9bab0622445ef`로 푸시(보고서 digest 일치).
  - Deploy(`135494441165`, `10:22:43Z~10:24:12Z`): `Runner Image Provisioner`에 `Region: us-west-2`, `Cloud: AWS` 출력 확인. `active_color=blue` → `mode=bluegreen`, `deploy_id=bluegreen-517eb6b11b06-44504907458-1`, `10:23:08Z` switch 시작, `10:23:48Z` `app-green` 내부 통과, `10:23:49Z` `SWITCH_OK ... active_kept=blue serving=green`, `10:24:04Z` `G4_PASS total=45 ok=45 failures=0 p95_ms=588 max_ms=633`(`runner_host=ip-10-0-137-45`), `10:24:06Z~10:24:07Z` 구 blue 정지(약 0.6초) 후 `DEPLOY_OK color=green sha=sha-517eb6b... prev_color=blue prev_sha=sha-836a3c0...`가 로그와 정확히 일치한다.
  - 아티팩트 `g4-probe-report`(`14519302271`, 443 bytes) 업로드 확인. 보고서 JSON 15개 필드가 로그에 출력된 JSON 전문과 완전히 일치한다.
- **사후 롤백 G4 (`44505579286`, `10:25:48Z`, `workflow_dispatch(action=rollback)`, `headSha=32e71e4bb56c9137cb12f9b49088a66d949f988a`)**:
  - Build & Push(`135496184786`) `skipped`, Deploy(`135496183077`, `10:25:53Z~10:26:47Z`) `Runner Image Provisioner`에 `Region: centralus`, `Cloud: Azure` 출력 확인.
  - `deploy_id=rollback-32e71e4bb56c-44505579286-1`, `10:26:10Z` rollback switch 시작(`active=green(517eb6b) -> target=blue(836a3c0)`), `10:26:24Z` `app-blue` 내부 통과(기동 후 ~13초) 및 `SWITCH_OK ... serving=blue`, `10:26:41Z` `G4_PASS total=24 ok=24 failures=0 p95_ms=1194 max_ms=1407`(`runner_host=runnervmeozm8`), `10:26:43Z~10:26:44Z` green 정지 후 `ROLLBACK_OK color=blue sha=sha-836a3c0... stopped_color=green`이 로그와 정확히 일치한다.
  - 아티팩트 `g4-probe-report`(`14518578388`, 442 bytes) 업로드 및 로그 JSON 전문 일치 확인.
- **자동 배포 비활성 교차 확인**: PR #23 병합 `44504564676`(`48f6878`)과 PR #26 병합 `44505638505`(`32e71e4`, Build & Push `10:26:52Z~10:27:57Z`, Deploy `10:27:58Z` `skipped`) 모두 배포 잡 `skipped`임을 직접 확인했다.

### 3. 경계 구분 · 외부 주장 검증 · 비밀값

- **단독 호출 허용 vs 스위치 거부 구분**: 지정된 단독 `workflow_dispatch` 두 건만 허용·실행됐으며 `AUTO_DEPLOY_ENABLED` 변경 거부는 해소되지 않았음을 명확히 적었다.
- **사후 `rollback` vs 실패 유도 `abort`**: 이번 롤백은 `action=rollback` 사후 롤백 경로이며 `G4_FAIL` → `abort` 경로는 미검증으로 분리했다.
- **`rollback` `deploy_id` SHA vs 실제 대상 SHA**: `.github/workflows/deploy.yml` 118·154행 구조상 `rollback`은 `github.sha`(`32e71e4bb56c`)로 `deploy_id`가 생성되지만 실제 전환 대상은 서버 `prev_sha`인 `836a3c0`이다. 보고서는 관찰 1번에서 이를 정확히 구분해 state와 혼동하지 않았다.
- **runner region 출처 확인**: `AWS us-west-2`·`Azure centralus`는 추측이 아니라 `44504907458`·`44505579286` 로그의 `Runner Image Provisioner` 항목에서 직접 확인된 사실이다. 각 1회 측정이므로 지연 차이의 원인을 단정하지 않고 유보한 서술과, PC 참고 측정(`144`/`56`회)을 실사용 성능 기준선이나 사용자 수로 확장하지 않은 점도 적절하다.
- **root 직접 확인 대조**: root가 직접 확인한 아티팩트 JSON(45/45·24/24·실패 0, 배포 588/633ms·롤백 1194/1407ms) 및 `10:30:27Z` 서버 상태(`blue 836a3c0`, `ROLLBACK_OK`, `pending` 없음, HTTPS 200)와 보고서 내용이 모순 없이 일치한다.
- **비밀값**: `claude-g4-result.md` 내 웹훅·개인키·민감값 노출 0건.

## 비차단 관찰

1. 관찰 1번(`rollback` `deploy_id`의 `github.sha` 표기)·2번(`workflow_dispatch` 재빌드 digest 변경 런북 반영 여부)은 워크플로·런북 개선 제안으로만 남아 있으며, 이번 PR #25에서 소스를 고치지 않은 점은 요청 범위와 일치한다.

## 검증 경계

- **agy 직접 확인**: PR #25 차분(`ffc9619..013ec5d`), `git diff --check`, root 사본 바이트 비교, `44504907458`·`44505579286`·`44504564676`·`44505638505`·`44506128462`·`44506128474` GitHub Actions 메타·로그·아티팩트 메타, `deploy.yml`·배포 파일 diff, 민감값 점검.
- **root 직접 확인**: 실제 아티팩트 JSON 파일과 `10:30:27Z` 롤백 후 서버 상태(`blue 836a3c0`, `ROLLBACK_OK`, `pending` 없음, HTTPS 200).
- **작성자(Claude) 보고 기준**: 서버 내부 `deploy.sh status`·`upstream.caddy`·RepoDigest·Prometheus 타깃 조회와 한국 가정망 PC `curl` 교차 측정(`144`·`56`회).
