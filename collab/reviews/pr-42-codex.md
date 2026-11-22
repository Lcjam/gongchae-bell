# PR42 검수 · root Codex · 1라운드

**판정: 수정 요청. P2 2건, P3 문구 범위 보완.** 고정 source `e4685764a8cc14262f7d6af4da631917b0c9398b`, base `33d9f5a93b0beefb53d29b4d9da82e1a67c492a9`. Claude의 완료 보고·9파일 diff·도구 전체316줄·JUnit340줄·실제JARsmoke104줄과GitHub최종CI를읽었다. 소스/시험/구현설정을root가수정하지않았다. 새Sol독립결론은아직읽지않았다.

## P2-1 — CREATE_NEW 실패 뒤 자신이 만들지 않은 임시 파일을 삭제함

`ErasureJournalTool.writeTemp`는 `.erasure.log.tool-<random>` 경로를먼저정한뒤 `FileChannel.open(...CREATE_NEW...)`를try-with-resources의초기화에쓴다. cleanup은 `finally { if (!kept) Files.deleteIfExists(temp); }`다.

동명경로에기존파일이있으면CREATE_NEW가실패하므로도구는그파일을만든적이없다. 그러나finally는kept=false인채기존파일을삭제한다. random64충돌이흔하다는주장이아니며, **exclusive create 실패에서는해당경로를자기소유생성물로간주할수없다**는정확성문제다. 기존설계는살아남은helper/임시파일을확인전에맹목적으로정리하지않고남겨두므로이경로역시같은소유원칙을지켜야한다.

최소수정: 실제생성/열기에성공해이번호출이소유한temp에만실패cleanup을적용한다. 생성실패한기존파일은bytes/inode를그대로두고실패한다. 생성후실패한자기temp의정상정리는유지한다. deterministic collision/생성거부를강제하는작은회귀로두상황을구별한다. 새운영설정·외부노출seed·프레임워크는필요없다. 이finding은고정source의예외/cleanup경로에대한분석반례이며root가실제random충돌/운영삭제를발생시켰다고쓰지않는다.

## P2-2 — 새 운영 문서의 계약 링크가 고정 Git tree에 없음

새 `docs/ops/alerts.md`의 `[합의](../../collab/2026-11-22-recovery-barrier/final.md)` target은고정Git tree에없다. root가`git ls-tree`로직접확인했다. 해당final은root작업공간의조율기록일뿐PR42의9파일에포함되지않았다. 새Javadoc의같은경로참조와PR본문상대링크도공개검토자가따라갈수없다.

실제committed계약/ADR/문서로참조를맞추거나필요한독립계약을커밋해야한다. **링크하나를위해미공개collab전체를무작정복사할필요는없다.** 이미설명한ops/alerts의자기계약과존재하는ADR0013등을사용하는최소수정이가능하다. 수정된문서의상대링크target을고정tree로검증한다.

## P3 — root/잠금 문구의 보장 범위

- “root로실행하면OWNER_MISMATCH로거부”는앱소유L에새temp를만들어교체하는smoke사례다. 실제권한코드는파일/디렉터리의uid·gid일치를보며호출자UID0을일괄거부하지않는다. root의NOOP/ALREADY/verify나root소유fixture전체에같은거부가보장되는표현은좁힌다. 실제supervisor가UID1000:G로실행한다는운영게이트는별도다.
- 초기 `underLock`은tryLock이라busy즉시거부지만publish후 `reread`는기존 `journal.readLatest()`의blocking lock을쓴다. 뒤시점의다른process잠금까지절대기다리지않는다는보장은현재시험/코드로뒷받침되지않는다. 이경로를같은비대기잠금으로맞추거나문구/전제/한계를명시한다. 호출자의writer정지·orphanfencing범위를이PR가해결했다고주장하지않는현재분리는유지한다. root는이후단계의cross-processhang를직접재현했다고쓰지않는다.

## 확인한 구현/증거

- main은첫인자일때만도구/System.exit로분기한다. 기존Spring인자경로/공통strictparse·marker·coverage·DELETE gate는변하지않았다. readAll추출은기존positionalread루프를유지한다. reservedUUIDv8은tag48/version/variant를고정하고74난수bits를쓴다. 구독UUID를marker로재적용하지않는기존계약을바꾸지않았다.
- missing만init·empty/nonmarker/corrupt거부·marker1행ALREADY, mark의empty거부/tailNOOP/원문prefix보존,ENOENT와EACCES분리,0600/0640·owner/gid,dirforce실패UNCONFIRMED/NOOP재시도와stat/no-follow/CREATE_NEW/link/atomicrename흐름을읽었다. 초기B=[]/M1사본수락범위검사는감독에남기며운영init금지문구를유지한다.
- 최종e468의CI45028240737/Monitoring45028240700,7잡COMPLETED/SUCCESS를직접조회했다. testjob137225873585의원본로그에서**23smoke기대명령/ALL_JOURNAL_TOOL_SMOKE_PASSED**·비Spring클래스검사와역할별결과를읽었다. 네트워크없음·앱env/DB없는실제bootJar실행이다. 실제운영이미지/OCI경로시험으로확대하지않는다.
- 같은CI의`test-results` artifact를직접다운로드해HTML원문을읽었다. **전체188tests/failures0/skipped0**, ToolTest **12/failures0/skipped0**다. 이artifact는XML이아니므로초기XML탐색0개를시험0개로해석하지않았다. 로컬Linux21/Windowsskip보고는작성자의실행이며root실행과구별한다.
- 새로운전체Gradle/Docker시험·운영SSH/실대장/계정/env/key/provider작업은이PR검수에서실행하지않았다. 별도rootmetadata관측은파일도구를운영실행한것이아니다. fileforce/rename/ENOSPC·cross-process lock미실행과감독/보존점/compact미구현한계는작성자가밝힌대로남긴다.

새Sol독립검수와위수정대응을함께받은뒤고정새head/최종CI를확인한다. source완료·검수·실제정비가능·M2/전체출시는각각별개다.
