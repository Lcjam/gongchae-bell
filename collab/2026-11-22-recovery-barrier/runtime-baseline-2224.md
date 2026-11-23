# 정비 전 서버 파일 현황 · root 읽기 전용 관측

2026-11-22 **13:24:15Z / 22:24:15 KST**. 검수용 SSH로 고정 경로의 metadata와 deploy status만 읽었다. 파일 내용·전체 env·secret을 출력하지 않았고 디렉터리/권한/앱/DB/계정/저장소는 변경하지 않았다.

| 경로/항목 | 직접 관측 |
|---|---|
| erasure directory | `/var/lib/gongchae-bell/erasure`, UID1000:GID1000, mode0750, 실제 directory, symlink 아님 |
| ubuntu의 erasure.log lstat | **EACCES errno13**. 이것은 파일 부재가 아니며 부재로 분류할 수 없음 |
| sudo -n metadata-only lstat | `/var/lib/gongchae-bell/erasure/erasure.log`는 **ENOENT**. 내용을 읽거나 새 파일을 만들지 않음 |
| 기본 host backup directory | `/opt/gongchae-bell/backup` ENOENT |
| 그 아래 기본 backup.env | ENOENT. 다른 override경로/OCI/PC 저장소 전체 부재의 증거는 아님 |
| 정비 guard 경로 | `/opt/gongchae-bell/deploy/state/erasure_maintenance` ENOENT |
| active/upstream | green, `sha-e0208ffd16c39b511cc0065dc8ee40e5c8b4f9c1` |
| 배포 상태 | DEPLOY_OK, pending none, active deploy `bluegreen-e0208ffd16c3-45001647801-1` |

검수용 `.tools/review/nov22-erasure-baseline.sh`는 ignored 로컬 artifact다. 첫13:23:03Z실행은필요metadata/status출력뒤PowerShell파이프의끝CR때문에exit1이었다. 검수스크립트끝에명시exit0을넣은위13:24:15Z실행은exit0이며sudo metadata 확인도포함한다. 앱/배포코드는수정하지않았다.

**다음 동작에 주는 의미:** 현재 host에는 초기marker를넣을기존empty파일조차없다. 그렇다고지금init해도된다는뜻은아니다. 실제OCI/PC 보존점 전수현황, writer정지/backupgroup권한·감독도구/시험을앞서확인해야한다. CLI의missing init 경로는현재host상태와맞으며,부재판정을일반ubuntu의`[[ -e ]]`하나로하면이실제EACCES를놓친다. 기본backup경로ENOENT를remote repo snapshot0증거로쓰지않는다.
