# 닫힌 검수·공개 화면 점검 기록 색인 (2026-11-22, #19)

병합된 PR38·PR42의 비작성자 검수와 root Codex의 공개 HTTPS 화면 점검을 저장소에 남긴다.
- 원문은 같은 날 조율 작업 공간에 있던 파일을 **같은 상대 경로에 바이트 그대로 복사**했다.
- 문장, 수치, 당시의 오류 기록은 고치지 않았다.
- 이 색인은 원문을 연결만 한다. 색인을 만들면서 새로 시험하거나 관측한 것은 없다.
- 각 원문은 '직접 확인'과 '작성자 보고'를 스스로 구분해 적었다. 색인은 그 구분을 바꾸지 않는다.

색인과 manifest는 Claude가 작성했다. 이 폴더의 새 파일은 이 `README.md`와 [`manifest.tsv`](manifest.tsv) 둘뿐이다.

## PR38 · Windows PC restic pull과 14일 보유 (#17)

- **최종:**
  - head `359e85c401ce0598a23c041ffddb46380de48f03`
  - merge [`2090c4c14930e9b57f6fdcf01ba04a9d06bb12d4`](https://github.com/Lcjam/gongchae-bell/commit/2090c4c14930e9b57f6fdcf01ba04a9d06bb12d4), **2026-11-22T14:12:30Z**
- **최종 8 check:** CI [45030119688](https://github.com/Lcjam/gongchae-bell/actions/runs/45030119688), Monitoring [45030119702](https://github.com/Lcjam/gongchae-bell/actions/runs/45030119702). root 3라운드가 14:09Z 전후에 직접 조회했다.

| 라운드 | 검수자 | 고정 대상 | 판정(당시) | 원문 |
|---|---|---|---|---|
| 1 | root Codex | `e807a34` | 수정 요청: P2 상위 junction 우회, P2 14일 초과 사본 문구 | [pr-38-codex.md](../reviews/pr-38-codex.md) |
| 1 | Sol | `e807a34` | 수정 요청: P1 경로 보호, P2 PS7 ACL, P2 case 6 | [pr-38-sol.md](../reviews/pr-38-sol.md) |
| 2 | root Codex | `d4ee65d` | 수용 | [pr-38-codex-round2.md](../reviews/pr-38-codex-round2.md) |
| 2 | Claude | `d4ee65d` | 수용(P3 셋, nit 하나) | [pr-38-claude-round2.md](../reviews/pr-38-claude-round2.md) |
| 3 | root Codex | `359e85c` | **수용** | [pr-38-codex-round3.md](../reviews/pr-38-codex-round3.md) |
| 3 | Claude | `359e85c` | **수용**(nit 셋) | [pr-38-claude-round3.md](../reviews/pr-38-claude-round3.md) |

- **최종 판정:** 3라운드 둘 다 수용이다.
  - 1라운드의 결함 지적은 당시 고정 source 기준의 역사 기록이며, 최종 상태가 아니다.
  - Claude 3라운드는 dispatch 때 최종 CI가 진행 중이었고, 결과를 사후 조회로 확인했다고 적었다. 원문 그대로 둔다.
- **조건:** source·시험·CI 증거에 대한 수용이다. 다음은 승인하거나 완료한 것이 아니다([운영 문서](../../docs/ops/pc-pull-backup.md)).
  - 실제 PC 설치, ACL 적용, 작업 스케줄러 등록
  - OCI 연동, 정기 복사, 격리 복원

## PR42 · 해지 대장 파일 도구 (#17)

- **최종:**
  - head `2cbd52ef7d7cc17d3139fe95dd0bb0d0dc15e17c`
  - merge [`fb51d4832eb3c4dc5b94506daaa5d7868c54c335`](https://github.com/Lcjam/gongchae-bell/commit/fb51d4832eb3c4dc5b94506daaa5d7868c54c335), **2026-11-22T14:20:36Z**
- **최종 7 check:** CI [45031042409](https://github.com/Lcjam/gongchae-bell/actions/runs/45031042409), Monitoring [45031042429](https://github.com/Lcjam/gongchae-bell/actions/runs/45031042429). root 2라운드가 14:17Z 전후에 직접 조회했다.

| 라운드 | 검수자 | 고정 대상 | 판정(당시) | 원문 |
|---|---|---|---|---|
| 1 | root Codex | `e468576` | 수정 요청: P2 temp 삭제, P2 미커밋 계약 링크, P3 문구 | [pr-42-codex.md](../reviews/pr-42-codex.md) |
| 1 | Sol | `e468576` | 수용(P3 둘) | [pr-42-sol.md](../reviews/pr-42-sol.md) |
| 2 | root Codex | `2cbd52e` | **수용** | [pr-42-codex-round2.md](../reviews/pr-42-codex-round2.md) |
| 2 | Sol | `2cbd52e` | **수용**(추가 지적 없음) | [pr-42-sol-round2.md](../reviews/pr-42-sol-round2.md) |

- **최종 판정:** 2라운드 둘 다 수용이다.
  - root 1라운드가 지적한 `final.md` 링크는 `e468576`에 있던 결함이다. `2cbd52e`에서 [ADR-0013](../../docs/adr/0013-erasure-journal-host-file.md) 링크로 고쳤다.
  - 그 원문 안의 `final.md` 경로는 인용일 뿐 이 저장소의 링크가 아니다.
- **작성자 보고:** 옛 동작을 되돌리면 새 시험이 실패한다는 역패치 확인은 작성자 보고다. 2라운드 검수자는 이를 직접 실행했다고 쓰지 않았다.
- **조건:** 파일 도구 단계의 수용이다. 운영 `init`·`mark` 허가가 아니며, 감독·보존점 coverage·compact 완료도 아니다. 감독 작업 전에는 운영에서 쓰기 명령을 실행하지 않는다([운영 계약](../../docs/ops/alerts.md#해지-대장과-복원-adr-0013)).
- **인용 파일:** 검수 원문이 인용하는 작성자 결과서와 요청문 파일 이름(예: `claude-pr42-review-fixes-result.md`)은 이 보존 범위 밖이다. 저장소에 없다.

## 공개 HTTPS 화면 점검 (root Codex)

- **시각과 방식:** **2026-11-22 13:31~13:36Z**. 실제 `https://gongchae.enjoylcjworld.com`을 Orca 내장 Chromium에서 읽기 전용으로 열었다.
- **원문:** [01-codex.md](../2026-11-22-live-frontend/01-codex.md)
- **원자료:** [evidence/](../2026-11-22-live-frontend/evidence/)의 PNG 10개와 JSON 13개. 화면 10개 상태, network, console, 공개 CSS hash다.
- **운영 앱:** 별도 [13:24:15Z 읽기 전용 metadata 관측](../2026-11-22-recovery-barrier/runtime-baseline-2224.md) 기준이다.
  - active green `sha-e0208ffd…`(E020), `DEPLOY_OK`
  - 배포 기록: [E020 배포 결과](../2026-11-19-resume/claude-main-e020-deploy-result.md)
  - 원문에 따르면 화면 점검 시점에 새 SSH나 배포는 없었다.
- **PR38·42 병합과의 관계:** 두 병합(14:12Z·14:20Z)은 이 점검보다 **뒤**의 main 변경이다. 이 색인은 그 병합본이 운영에 배포됐다고 주장하지 않는다.
- **PR41 합성 QA와 다른 점:**

  | 항목 | 대상 | 기록 |
  |---|---|---|
  | PR41(merge `33d9f5a`, 13:13:10Z) | 로컬 합성 fixture로 PR39 화면을 본 QA | [docs/qa/frontend-refresh-2026-11-22.md](../../docs/qa/frontend-refresh-2026-11-22.md) |
  | 이 점검 | 실제 공개 서버의 E020 화면 | 위 원문 |

  이 점검은 PR41을 다시 실행한 것이 아니다.
- **직접 확인과 그 밖:** 원문은 PNG 5개(홈, 모바일 목록, 펼친 필터, 개인정보, 404)를 육안으로 확인했다고 쓴다. 나머지는 DOM·HTTP와 캡처 생성까지만 확인했다고 구분한다.
- **당시 기록된 도구 결함:** 아래는 원문 그대로 두었다. 원문은 이를 앱 결함으로 보지 않았다.
  - 첫 eval이 selector 인용 문제로 실패했다.
  - 초기 JSON 3개의 `filter.expanded` helper 값이 잘못된 id를 읽어 null이다.
  - 콘솔 출력이 cp949였다.

## 아직 완료되지 않은 것

아래는 이 기록들로 완료된 것이 아니다.
- PC 설치와 정기 pull
- 정기 collector 개시와 수집 중 성능
- M2 알림 활성화와 실제 신청·발송·해지
- 정비 감독 작업
- 서버 백업 설치와 보존 사본 coverage
- 격리 복원 리허설
- 공개 출시([PRD §7](../../docs/prd.md))

## 무결성 manifest

[`manifest.tsv`](manifest.tsv)는 복사한 35개 파일을 한 줄씩 적는다.

**열:**
- `path`
- `kind`
- `raw_bytes`, `raw_sha256`
- `blob_bytes`, `blob_sha256`
- `git_blob`

**raw와 blob:**
- raw는 원본 작업 공간 파일의 바이트다. 복사본과 `cmp`로 35/35 같음을 확인했다.
- blob은 이 저장소에 저장된 내용이다. 저장소 규칙은 `.gitattributes`의 `* text=auto eol=lf`다.

**kind별 결과:**

| kind | 개수 | raw와 blob |
|---|---|---|
| `binary` | 10 (PNG) | 변환 없이 같다 |
| `text-lf` | 12 (Markdown) | 같다 |
| `text-no-eol` | 11 (한 줄 JSON) | 같다 |
| `text-crlf-to-lf` | 2 | 다르다(아래) |

`text-crlf-to-lf` 2개는 `evidence/console.json`(70 → 65 bytes)과 `evidence/network.json`(11,790 → 11,548 bytes)이다. 원본이 CRLF여서 blob에서는 LF로 정규화됐다. blob은 원본의 CRLF를 LF로만 바꾼 것과 정확히 같고, 단독 CR은 없다. 따라서 **35개 전부가 raw bytes와 같은 Git blob인 것은 아니다.** 33개만 같다.

**검증 방법:**
- 공개 저장소에서는 `git ls-files -s <path>`로 blob id를 확인하고 `git cat-file blob <id> | sha256sum`을 `blob_sha256`과 비교한다.
- `raw_sha256`은 비공개 원본 작업 공간을 기준으로 한 값이다.
