# Jenkins 자원과 동시 실행

4 vCPU·16GB 호스트를 Backend·Worker 2개·PostgreSQL·RabbitMQ와 공유한다.

- Jenkins 컨테이너 상한: CPU 2개, RAM 4GiB. 예약 자원이 아니다.
- `memswap_limit: 6G`는 RAM과 swap의 합이다. swap 허용량은 기존과 같은 2GiB다.
- Jenkins 본체의 Java heap은 1GiB다. 컨테이너 메모리를 늘렸다고 heap도 함께 늘리지 않는다.
- built-in executor는 3개다. CI(thorp-ci)는 최대 2개, 배포는 최대 1개가 돈다.
  CI 상한은 `init.groovy.d/20-ci-concurrency.groovy`가 Throttle Concurrent Builds 플러그인으로 걸고,
  배포 상한은 `Jenkinsfile.deploy`의 `disableConcurrentBuilds()`가 건다.
  CI가 이미 2개 돌면 셋째 CI는 시작하지 않고 대기열(Pending)에 남는다.
- 슬롯 수는 288 이전 2개였고, S15P21E205-288에서 CI와 배포의 자원 경합을 줄이려고 1개로 줄였다.
  2026-09-17 한 빌드가 끝나지 않고 그 슬롯을 붙잡아 뒤의 CI가 전부 멈춘 뒤, CI에 45분 제한을 걸고
  CI 2개 + 배포 1개로 나눴다(S15P21E205-319).
- CI 상한을 Jenkinsfile이 아니라 Jenkins 시작 스크립트에 두는 이유: thorp-ci는 S15P21E205-332 전까지
  Jenkinsfile을 MR 소스 브랜치에서 읽었다. Jenkinsfile의 `options`는 빌드가 시작될 때 Job 속성으로
  저장되므로, dev를 반영하지 않은 브랜치가 빌드되면 옛 설정이 되살아났다. 지금은 dev에서 읽지만 Job
  설정을 되돌리면 같은 일이 생긴다. 시작 스크립트는 thorp-ci 설정이 저장될 때마다
  `disableConcurrentBuilds`를 지우고 throttle 상한을 다시 확인한다.
- CI의 Gradle·Java 테스트·Node는 Jenkins 컨테이너 안에서 실행된다.
  CD의 Node 컨테이너·Docker 이미지 빌드와 Testcontainers DB·브로커는 별도 자원을 쓴다.
  이 설정은 수동 SSH 빌드나 별도 에이전트, Pipeline 내부 병렬 실행까지 제한하지 않는다.

## 적용

`docker-compose.ci.yml`은 `init.groovy.d/` 폴더를 읽기 전용으로 마운트한다. Jenkins가 시작할 때
두 스크립트가 순서대로 돈다.

- `10-build-executors.groovy`: executor=3을 저장한다.
- `20-ci-concurrency.groovy`: thorp-ci에 동시 실행 상한 2를 걸고, `disableConcurrentBuilds` 속성을 지우고,
  GitLab 트리거의 **Cancel pending merge request builds on update**를 켠다. 이 옵션을 Jenkinsfile의
  `triggers`로 옮기지 않는 이유는 그 블록이 Job에 저장된 웹훅 Secret token까지 덮어쓰기 때문이다.

향후 빌드 에이전트를 분리할 때는 이 스크립트들과 두 Pipeline의 실행 노드 정책을 함께 변경한다.

플러그인(throttle-concurrents)이 이미지에 들어가므로 이미지를 다시 빌드해야 한다.
진행 중인 빌드가 끝났고 실행 대기 중인 작업도 없는 것을 Jenkins 화면에서 확인한 뒤,
호스트의 `infra/jenkins`에서 기존 배포 환경값을 사용해 적용한다.

```bash
docker compose -f docker-compose.ci.yml --env-file ../.env.prod config --quiet
docker compose -f docker-compose.ci.yml --env-file ../.env.prod build --pull jenkins
docker compose -f docker-compose.ci.yml --env-file ../.env.prod up -d --no-deps --force-recreate jenkins
docker inspect thorp-jenkins --format 'RAM={{.HostConfig.Memory}} RAM_SWAP={{.HostConfig.MemorySwap}} CPU={{.HostConfig.NanoCpus}}'
docker logs --since 5m thorp-jenkins 2>&1 | grep 'Thorp:'
```

기대값은 RAM=4294967296, RAM_SWAP=6442450944, CPU=2000000000이다. 로그에는 다음 두 줄이 있어야 한다.

- `Thorp: built-in executors=3; CI up to 2 and CD 1.`
- `Thorp: thorp-ci max 2 concurrent, extra builds stay pending.`

`20-ci-concurrency.groovy`가 실패하면 로그에 이 줄 대신 스크립트 오류가 남고, CI는 상한 없이 슬롯 3개를
모두 쓸 수 있다. 그때는 Manage Jenkins → Plugins에서 Throttle Concurrent Builds가 설치되었는지 먼저 본다.

적용 후 확인:

- thorp-ci → Configure에서 Throttle Concurrent Builds가 켜져 있고 전체 상한이 2다.
- 같은 GitLab 트리거의 Advanced에서 Cancel pending merge request builds on update가 켜져 있다.
- CI 세 개가 겹치면 셋째가 Pending에 남는다. 스테이지 칸이 빈 채로 진행 막대만 도는 빌드가
  생기면 상한이 걸리지 않은 것이다.

CI Jenkinsfile의 시간 제한은 코드라서 다음 CI 빌드부터 바로 적용된다.
Jenkins 재생성 동안 관리 화면과 웹훅 수신에 짧은 중단이 생긴다. 서비스 컨테이너는 재시작하지 않는다.

## 검증과 되돌리기

- 설정 적용과 테스트 속도 개선은 별개다. 동일 커밋의 완료된 실행끼리 비교하고,
  실행 도중 메모리를 바꾼 빌드는 전후 비교에서 제외한다.
- 같은 조건에서 CI만 실행, Worker 2개 계산, 계산과 CI 동시 실행을 구분해
  소요 시간·메모리 최고값·swap 입출력·API 응답을 기록한다.
- 상한 합계가 호스트 RAM에 가까우므로 순간 유휴 사용량으로 서비스의 최대 부하를 판단하지 않는다.
- CI 두 개가 동시에 도는 동안의 메모리는 순간값이 아니라 최고값을 본다. `docker stats`는 순간값이라
  최고값을 놓친다. 2026-09-17 순간값은 1.6~1.8GiB였지만 cgroup의 `memory.peak`는 4GiB였고
  상한 도달(`memory.events`의 `max`)이 4,357번이었다. 이 값에는 파일 캐시도 포함되므로
  프로그램 메모리는 `memory.stat`의 `anon`으로 따로 본다.
  ```bash
  J=/sys/fs/cgroup/system.slice/docker-$(docker inspect -f '{{.Id}}' thorp-jenkins).scope
  echo "peak=$(( $(cat $J/memory.peak)/1048576 ))MB"; grep -E '^(max|oom_kill) ' $J/memory.events
  ```
- CI 상한을 되돌릴 때는 `20-ci-concurrency.groovy`의 `MAX_CONCURRENT`와 `10-build-executors.groovy`의
  executor 수를 함께 바꾸고 Jenkins만 재생성한다. 스크립트를 지워도 Job에 저장된 throttle 속성은
  남으므로 thorp-ci → Configure에서 Throttle Concurrent Builds도 끈다.
- 288 이전 자원 상한으로 되돌릴 때는 빌드 종료 후 Compose RAM=2G, `memswap_limit: 4G`를 복원하고
  Jenkins만 재생성한다. 사용 중인 메모리보다 낮게 즉시 축소하지 않는다.

## CI가 읽는 Jenkinsfile

thorp-ci는 검사 절차(`Jenkinsfile`)를 dev에서 읽고, MR 소스 커밋과 dev를 병합한 결과를 검사한다.
Jenkins 컨테이너는 `docker.sock`을 마운트해 호스트 root와 같은 권한을 가지므로, MR에서 고친 절차가
리뷰·머지 전에 그 권한으로 돌지 않게 한다(S15P21E205-332).

Job 설정은 Jenkins 볼륨에만 저장되므로 여기에 남긴다. thorp-ci → Configure → Pipeline:

| 항목 | 값 |
|---|---|
| Definition | Pipeline script from SCM |
| SCM | Git |
| Repository URL | `https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21E205.git` |
| Credentials | `gitlab-deploy-token` |
| Branch Specifier | `refs/heads/dev` |
| Script Path | `Jenkinsfile` |
| Lightweight checkout | 켬 |

- Branch Specifier는 `refs/heads/dev` 형태로 적는다. `origin/dev`처럼 적으면 Lightweight checkout을
  쓰지 못하고 전체 체크아웃으로 넘어간다. 전체 체크아웃은 GitLab 트리거가 빌드에 붙인 MR 커밋
  (RevisionParameterAction)을 브랜치보다 먼저 쓴다. 지금은 트리거가 알려 준 저장소 주소(SSH)와 Job의
  주소(HTTPS)가 달라 그 커밋을 건너뛸 뿐이다. Lightweight checkout은 그 커밋을 보지 않는다.
- Jenkinsfile은 `skipDefaultCheckout()`으로 기본 체크아웃을 끄고, `Merge with dev` 스테이지에서
  `gitlabMergeRequestLastCommit`을 직접 체크아웃한다. 기본 체크아웃을 되살리면 모든 MR이 dev 자신을
  검사하고 초록불을 받는다.
- GitLab 검사 상태는 gitlab-plugin이 웹훅의 MR 마지막 커밋에 붙인다. 체크아웃한 커밋과 같다.

확인: 콘솔 로그 첫머리에 `Obtained Jenkinsfile from git https://lab.ssafy.com/...`이 나오고
`thorp-ci@script`로 체크아웃하는 줄이 없다. `Merge with dev`의 `Checking out Revision`은 MR의 마지막
커밋이다.

되돌리기: Branch Specifier를 `origin/${gitlabSourceBranch}`로 바꾼다. Jenkinsfile은 그 설정에서도 MR
커밋을 직접 받으므로 고치지 않는다. 반대로 Branch Specifier를 dev로 바꾸는 일은 `skipDefaultCheckout()`이
든 Jenkinsfile이 dev에 머지된 뒤에만 한다. 먼저 바꾸면 옛 Jenkinsfile의 기본 체크아웃이 dev를 받는다.

### Jenkinsfile을 고치는 MR을 머지 전에 확인하기

MR 이벤트 빌드는 dev의 절차로 돌므로 바뀐 절차를 검사하지 않는다. 그 MR의 빌드에서 Replay를 열고,
Jenkinsfile 내용을 MR의 것으로 바꾼 뒤 `environment` 블록에 한 줄을 더해 실행한다.

```groovy
CI_SOURCE_COMMIT = '<MR 소스 커밋 40자리 SHA>'
```

Replay는 웹훅 정보를 물려받지 않아 이 값이 없으면 검사할 커밋을 알 수 없고, 그 이유를 알리며 실패한다.
지금 빌드도 같은 이유로 실패한다. Replay 결과는 체크아웃한 커밋에 GitLab 검사 상태로 붙는다.

관련 티켓: [S15P21E205-288](https://ssafy.atlassian.net/browse/S15P21E205-288),
[S15P21E205-319](https://ssafy.atlassian.net/browse/S15P21E205-319),
[S15P21E205-332](https://ssafy.atlassian.net/browse/S15P21E205-332)

조사 근거와 적용 결과: [288 트러블슈팅](https://app.notion.com/p/3dd61b3dc08f81d0900df3c010ea6d9e),
[319 트러블슈팅](https://app.notion.com/p/3de61b3dc08f8122ad4ad545feb7a108)
