# PR32 검수 · Codex

2026-11-20. 비작성자 root Codex. 고정 source `717fb914510564bd86247ed932976c98049e1090`, 기준 main eb903017. **source 수용**, 최종 결과 추가 HEAD/CI와 두 번째 독립 판정은 별도 확인한다.

compose.monitoring.yml의 Prometheus 두 파일/하위디렉터리 mount를 `/etc/prometheus` 하나의 readonly 디렉터리로 바꿨다. config.file·rule_files의 컨테이너 경로와 TSDB named volume은 그대로다. tar가 내부 파일 inode를 교체해도 directory mount는 새 파일을 찾으므로 reload가 stale 단일파일을 읽던 원인이 해소된다. app/DB/네트워크/공개 포트/플래그 변경이 없다. Grafana 두 mount는 원래 디렉터리이므로 같은 수정을 할 필요가 없다.

실제 Docker Compose 회귀는 기존1~3단계(태그보간·allowlist 네트워크/포트·별도 app compose중 두 모니터링 container보존) 뒤 실제 배포와 같은 tracked 경로 tar를 작업복제본에 풀고 inode 변경을 확인한다. host config에 새 scrape job을 in-place로 추가해 SIGHUP한 뒤 Prometheus `/api/v1/status/config`에서 그 job을 찾고 원래 container ID가 유지됨을 확인한다. 단순 mount YAML 단언이 아니라 실제 프로세스가 새 설정을 읽는 동작을 검사한다. 작성자 원본 단일파일 mount 변이의 실패 기록과 논리적으로 구별된다.

root가 직접 확인한 것은 전체4파일 diff와 시험 전체 문맥이다. 로컬 전체/Docker 시험을 root가 다시 실행한 것은 아니다. 작성자가 보고한 Docker27-dind/GNUtar1.35 정상·원본실패·기존설치 up-d 재생성 및 데이터 sample 보존은 작성자 보고로 구별한다. docs는 처음 설치/up-d, config-only SIGHUP, mount변경 재생성을 나누며 named volume 유지와 잠깐의 감시 오류를 알린다. 구버전 설치는 새 Compose가 반영된 뒤 재생성돼야 한다.

최종 CI는 root가 실제 head로 별도 조회한다. 실서버 적용/감시 경보 훈련은 이 source 검수로 완료되지 않는다. 운영 SSH·env·키·provider는 이 검수에서 접근/변경하지 않았다.

## 실제 CI 후속

root가 고정717의 Monitoring44714412767/job136214000860 실패 로그를 읽었다. 1~3단계를 지나 새4단계의 `tar did not replace prometheus.yml, so this step would prove nothing`에서 실패했다. 아직 통과한 CI가 아니므로 병합하지 않는다. primary Claude가 계속 working 상태에서 로컬 Docker 재현을 진행하는 화면을 확인했으며 중복 수정하거나 재시작하지 않았다. source의 directory mount 해결 여부와 tar 뒤 inode 비교 시험의 가정(재사용 등 가능성)은 구별해 후속 수정본을 검수해야 한다.

Sol 독립 검수도 source 수용했고, 문서의 '모든 for 대기를 처음부터 센다'는 단정을 P3로 지적했다. 고정v3.16의 RestoreForState 조건에 따라 상태가 이어질 수 있으므로 실제 alerts 상태를 확인하라는 표현 보완을 작성자 완료 뒤 전달한다. 이 항목을 해결한 것으로 미리 기록하지 않는다.

## 최종 dee0f6c 확인

그뒤작성자가4f4d141에서옛파일을하드링크로잡고-ef로교체를판정해inode번호재사용오판을고쳤다. root가실제diff를읽었고원래교체guard·새job/reload/동일container검사를유지함을확인했다. dee0f6c5d4c1112738debcab1af4d1e5f15ff88a의P3문서는상태복원조건·실제alerts확인으로정정됐고기존실패기록을보존한작성자결과2개가커밋됐다. root가정정diff/보고를읽었고Sol도`pr-32-sol-round2.md`에서최종차분을독립수용했다.

root가dee의CI44717513931 4잡과Monitoring44717513956 3잡전부COMPLETED/SUCCESS, MERGEABLE/CLEAN을직접조회했다. 과거717실패를덮지않으며현재최종판정은**수용**이다. source/시험/문서외실제운영적용은아직없었다. 로컬보고서의최종CI항목만추가한미커밋분은새제품변경이아니다.
