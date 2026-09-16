# Jenkins 자원과 동시 실행

4 vCPU·16GB 호스트를 Backend·Worker 2개·PostgreSQL·RabbitMQ와 공유한다.

- Jenkins 컨테이너 상한: CPU 2개, RAM 4GiB. 예약 자원이 아니다.
- `memswap_limit: 6G`는 RAM과 swap의 합이다. swap 허용량은 기존과 같은 2GiB다.
- Jenkins 본체의 Java heap은 1GiB다. 컨테이너 메모리를 늘렸다고 heap도 함께 늘리지 않는다.
- built-in executor는 1개다. 현재 두 Job 모두 최상위 `agent any`를 사용하므로
  CI와 CD는 실행 슬롯 하나를 공유하고, 뒤에 들어온 작업은 큐에서 기다린다.
  `disableConcurrentBuilds()`는 같은 Job의 중복만 막으므로 이것만으로는 부족하다.
- CI의 Gradle·Java 테스트·Node는 Jenkins 컨테이너 안에서 실행된다.
  CD의 Node 컨테이너·Docker 이미지 빌드와 Testcontainers DB·브로커는 별도 자원을 쓴다.
  이 설정은 수동 SSH 빌드나 별도 에이전트, Pipeline 내부 병렬 실행까지 제한하지 않는다.

## 적용

`docker-compose.ci.yml`은 `init.groovy.d/10-build-executors.groovy`를 읽기 전용으로
마운트한다. 기존 Jenkins 볼륨에도 시작할 때 executor=1을 저장한다. 향후 빌드 에이전트를
분리할 때는 이 스크립트와 두 Pipeline의 실행 노드 정책을 함께 변경한다.

진행 중인 빌드가 끝났고 실행 대기 중인 작업도 없는 것을 Jenkins 화면에서 확인한 뒤,
호스트의 `infra/jenkins`에서 기존 배포 환경값을 사용해 적용한다.

```bash
docker compose -f docker-compose.ci.yml --env-file ../.env.prod config --quiet
docker compose -f docker-compose.ci.yml --env-file ../.env.prod up -d --no-deps jenkins
docker inspect thorp-jenkins --format 'RAM={{.HostConfig.Memory}} RAM_SWAP={{.HostConfig.MemorySwap}} CPU={{.HostConfig.NanoCpus}}'
docker logs --since 5m thorp-jenkins 2>&1 | grep 'Thorp: built-in executors=1'
```

기대값은 RAM=4294967296, RAM_SWAP=6442450944, CPU=2000000000이다.
Jenkins의 Manage Jenkins → Nodes → Built-In Node에서도 executor=1을 확인한다.
Jenkins 재생성 동안 관리 화면과 웹훅 수신에 짧은 중단이 생긴다. 서비스 컨테이너는 재시작하지 않는다.

## 검증과 되돌리기

- 설정 적용과 테스트 속도 개선은 별개다. 동일 커밋의 완료된 실행끼리 비교하고,
  실행 도중 메모리를 바꾼 빌드는 전후 비교에서 제외한다.
- 같은 조건에서 CI만 실행, Worker 2개 계산, 계산과 CI 동시 실행을 구분해
  소요 시간·메모리 최고값·swap 입출력·API 응답을 기록한다.
- 상한 합계가 호스트 RAM에 가까우므로 순간 유휴 사용량으로 서비스의 최대 부하를 판단하지 않는다.
- 되돌릴 때는 빌드 종료 후 Compose RAM=2G, `memswap_limit: 4G`, 초기화 스크립트의
  executor=2를 함께 복원하고 Jenkins만 재생성한다. 사용 중인 메모리보다 낮게 즉시 축소하지 않는다.

관련 티켓: [S15P21E205-288](https://ssafy.atlassian.net/browse/S15P21E205-288)
