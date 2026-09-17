# Jenkins 자원과 동시 실행

4 vCPU·16GB 호스트를 Backend·Worker 2개·PostgreSQL·RabbitMQ와 공유한다.

- Jenkins 컨테이너 상한: CPU 2개, RAM 4GiB. 예약 자원이 아니다.
- `memswap_limit: 6G`는 RAM과 swap의 합이다. swap 허용량은 기존과 같은 2GiB다.
- Jenkins 본체의 Java heap은 1GiB다. 컨테이너 메모리를 늘렸다고 heap도 함께 늘리지 않는다.
- built-in executor는 2개다. 두 Job 모두 최상위 `agent any`를 사용하므로 CI와 CD는
  실행 슬롯 두 개를 공유하고, 셋째로 들어온 작업은 큐에서 기다린다.
  CI는 `disableConcurrentBuilds()`를 쓰지 않아 서로 다른 MR의 빌드가 동시에 돈다.
  CD는 그 설정을 유지해 배포끼리는 겹치지 않는다.
- 처음에는 1개였다(S15P21E205-288). 2026-09-17 한 빌드가 끝나지 않고 슬롯을 붙잡아 뒤의
  CI가 전부 멈춘 뒤 2개로 늘리고 CI에 45분 제한을 걸었다(S15P21E205-319).
  3개 이상은 아래 측정 결과를 보고 정한다.
- CI의 Gradle·Java 테스트·Node는 Jenkins 컨테이너 안에서 실행된다.
  CD의 Node 컨테이너·Docker 이미지 빌드와 Testcontainers DB·브로커는 별도 자원을 쓴다.
  이 설정은 수동 SSH 빌드나 별도 에이전트, Pipeline 내부 병렬 실행까지 제한하지 않는다.

## 적용

`docker-compose.ci.yml`은 `init.groovy.d/10-build-executors.groovy`를 읽기 전용으로
마운트한다. 기존 Jenkins 볼륨에도 시작할 때 executor=2를 저장한다. 향후 빌드 에이전트를
분리할 때는 이 스크립트와 두 Pipeline의 실행 노드 정책을 함께 변경한다.

진행 중인 빌드가 끝났고 실행 대기 중인 작업도 없는 것을 Jenkins 화면에서 확인한 뒤,
호스트의 `infra/jenkins`에서 기존 배포 환경값을 사용해 적용한다.

```bash
docker compose -f docker-compose.ci.yml --env-file ../.env.prod config --quiet
docker compose -f docker-compose.ci.yml --env-file ../.env.prod up -d --no-deps jenkins
docker inspect thorp-jenkins --format 'RAM={{.HostConfig.Memory}} RAM_SWAP={{.HostConfig.MemorySwap}} CPU={{.HostConfig.NanoCpus}}'
docker logs --since 5m thorp-jenkins 2>&1 | grep 'Thorp: built-in executors=2'
```

기대값은 RAM=4294967296, RAM_SWAP=6442450944, CPU=2000000000이다.
Jenkins의 Manage Jenkins → Nodes → Built-In Node에서도 executor=2를 확인한다.

CI Jenkinsfile의 시간 제한과 동시 실행 허용은 코드라서 다음 CI 빌드부터 바로 적용된다.
executor 수는 위 재생성을 해야 바뀐다.

같은 MR에 새 커밋이 오면 아직 시작하지 못한 이전 빌드를 버리도록 Job 설정에서
thorp-ci → Configure → Build Triggers → GitLab 트리거의 Advanced →
**Cancel pending merge request builds on update**를 켠다. Jenkinsfile의 `triggers`로
옮기지 않는 이유는 그 블록이 Job에 저장된 웹훅 Secret token까지 덮어쓰기 때문이다.
이미 실행 중인 이전 빌드는 취소하지 않는다. 그것까지 끊으려면 MR 브랜치의 Jenkinsfile에
Jenkins 내부 API 사용을 승인해야 하는데, 누구나 올릴 수 있는 브랜치 코드에 그 권한을 줄 수 없다.
Jenkins 재생성 동안 관리 화면과 웹훅 수신에 짧은 중단이 생긴다. 서비스 컨테이너는 재시작하지 않는다.

## 검증과 되돌리기

- 설정 적용과 테스트 속도 개선은 별개다. 동일 커밋의 완료된 실행끼리 비교하고,
  실행 도중 메모리를 바꾼 빌드는 전후 비교에서 제외한다.
- 같은 조건에서 CI만 실행, Worker 2개 계산, 계산과 CI 동시 실행을 구분해
  소요 시간·메모리 최고값·swap 입출력·API 응답을 기록한다.
- 상한 합계가 호스트 RAM에 가까우므로 순간 유휴 사용량으로 서비스의 최대 부하를 판단하지 않는다.
- executor=2 적용 후 CI 두 개가 동시에 도는 동안 `docker stats thorp-jenkins`로 메모리 최고값을
  기록한다. 4GiB 상한에 가까우면 늘리지 말고, 여유가 있을 때만 3개를 검토한다.
- executor만 되돌릴 때는 초기화 스크립트를 1로 바꾸고 CI Jenkinsfile에 `disableConcurrentBuilds()`를
  다시 넣은 뒤 Jenkins만 재생성한다.
- 288 이전 상태로 되돌릴 때는 빌드 종료 후 Compose RAM=2G, `memswap_limit: 4G`, 초기화 스크립트의
  executor=2를 함께 복원하고 Jenkins만 재생성한다. 사용 중인 메모리보다 낮게 즉시 축소하지 않는다.

관련 티켓: [S15P21E205-288](https://ssafy.atlassian.net/browse/S15P21E205-288),
[S15P21E205-319](https://ssafy.atlassian.net/browse/S15P21E205-319)

조사 근거와 적용 결과: [Notion 트러블슈팅](https://app.notion.com/p/3dd61b3dc08f81d0900df3c010ea6d9e)
