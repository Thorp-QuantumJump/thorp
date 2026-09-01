# infra — INFRA 파트

| 경로 | 내용 |
|------|------|
| `docker/` | 서비스별 Dockerfile (compose가 참조) |
| `docker-compose.prod.yml` | **운영(EC2) 스택** — postgres / kafka / backend / worker / nginx |
| `nginx/templates/` | nginx 설정 템플릿 (기동 시 `${DOMAIN}` 치환) |
| `nginx/html/` | 프론트 빌드 산출물이 놓이는 자리 (배포 시 채워짐, 커밋 안 함) |
| `jenkins/` | Jenkins 서버 이미지·compose·배포 파이프라인 |
| `scripts/` | 인증서 발급/갱신, DB 백업, 배포 스크립트 |
| `.env.prod.example` | 운영 환경변수 템플릿 |

- **로컬 개발**은 레포 루트의 `docker-compose.yml`을 쓴다. (`docker compose up -d postgres kafka`)
- **운영 서버**는 이 폴더의 `docker-compose.prod.yml`을 쓴다. 두 파일은 별개다.

로컬용과 운영용의 차이:

| | 로컬 (`/docker-compose.yml`) | 운영 (`infra/docker-compose.prod.yml`) |
|---|---|---|
| DB/Kafka/WAS 포트 | 전부 호스트에 공개 | 공개 안 함 (nginx 80/443만) |
| Kafka 리스너 | EXTERNAL → `localhost:9092` | 내부 전용 (`kafka:29092`) |
| 비밀번호 | 기본값 허용 | `.env.prod` 필수, 기본값 없음 |
| 리소스 상한 | 없음 | 컨테이너별 메모리·CPU 명시 |
| nginx / HTTPS | 없음 | 있음 (Let's Encrypt) |

---

# EC2 배포 런북

SSH로만 접근 가능한 서버(16GB RAM / 4 vCPU / 320GB SSD + 6TB HDD, Ubuntu)에
전체 스택을 올리는 순서다. **아래 단계를 건너뛰지 말고 순서대로** 진행한다.
한 계층 올릴 때마다 검증하고 다음으로 넘어가야 문제가 생겼을 때 원인을 좁힐 수 있다.

| # | 단계 | 완료 판정 |
|---|------|-----------|
| 1 | 서버 기본 세팅 | `free -h`에 스왑 표시 |
| 2 | 디스크 배치 (HDD 마운트) | `df -h`에 `/data` |
| 3 | Docker / Compose 설치 | `docker compose version` |
| 4 | 레포 클론 + `.env.prod` | 값이 모두 채워짐 |
| 5 | PostgreSQL | `healthy` + 터널 접속 성공 |
| 6 | Kafka | 토픽 생성/조회 성공 |
| 7 | Backend + Worker | 로그에 정상 기동 |
| 8 | Nginx + 프론트 | 도메인으로 화면이 뜸 |
| 9 | HTTPS | 자물쇠 + 갱신 cron |
| 10 | Jenkins | 터널로 UI 접속 |
| 11 | 배포 파이프라인 | 커밋 → 자동 배포 |
| 12 | 백업·정리 cron | 덤프 파일 생성 확인 |

---

## 대상 서버

| 항목 | 값 |
|------|-----|
| 도메인 | `j15e205.p.ssafy.io` |
| SSH 계정 | `ubuntu` |
| 스펙 | 16GB RAM / 4 vCPU / 320GB SSD + 6TB HDD |
| 공개 포트 | 22, 80, 443 **만** |

```bash
ssh -i key.pem ubuntu@j15e205.p.ssafy.io
```

## 1. 서버 기본 세팅

```bash
free -h && nproc && df -h
sudo timedatectl set-timezone Asia/Seoul
```

16GB라 스왑이 필수는 아니지만 4G만 잡아둔다. Jenkins가 Gradle 빌드 컨테이너를
띄우는 순간 메모리가 순간적으로 튀는데, 그때 OOM으로 운영 컨테이너가 죽는 것보다
잠깐 느려지는 편이 낫다.

```bash
sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

**보안그룹/방화벽은 22, 80, 443만 연다.** 5432(DB), 9092(Kafka), 8080(WAS),
9090(Jenkins)은 외부로 열지 않는다. 필요하면 SSH 터널로 붙는다.

> ⚠️ Docker는 `ports:`를 열 때 iptables를 직접 조작해 **UFW를 우회한다.**
> `"5432:5432"`로 쓰면 UFW로 막았다고 믿고 있어도 실제로는 외부에 열린다.
> 그래서 `docker-compose.prod.yml`은 전부 `127.0.0.1:` 바인딩을 쓴다.

## 2. 디스크 배치

SSD와 HDD의 역할을 먼저 나눈다. 나중에 옮기면 볼륨을 통째로 이사해야 한다.

| 대상 | 위치 | 이유 |
|------|------|------|
| OS, `/var/lib/docker`, Jenkins home | SSD | 빌드·기동 속도 |
| PostgreSQL 데이터 | SSD | 랜덤 IO |
| Kafka 로그 | SSD (named volume) | Job 메시지라 용량이 작다 |
| **GDELT 원본 · Parquet · 시세 데이터** | **HDD** (`/data`) | 대용량 순차 읽기 |
| DB 백업 덤프 | HDD (`/data/backup`) | 크고 접근 빈도 낮음 |

```bash
lsblk -f
```

```bash
sudo mkfs.ext4 -m 0 /dev/nvme1n1 && sudo mkdir -p /data && sudo mount /dev/nvme1n1 /data
```

`nofail`이 없으면 디스크 인식이 늦을 때 부팅이 멈춘다. 반드시 넣는다.

```bash
echo "UUID=$(sudo blkid -s UUID -o value /dev/nvme1n1) /data ext4 defaults,nofail 0 2" | sudo tee -a /etc/fstab
```

```bash
sudo mkdir -p /data/{raw,parquet,backup} && sudo chown -R ubuntu:ubuntu /data
```

## 3. Docker / Compose 설치

`apt install docker.io`는 버전이 낮고 compose v2가 따라오지 않는다.

```bash
curl -fsSL https://get.docker.com | sudo sh
```

```bash
sudo usermod -aG docker $USER && newgrp docker && docker compose version
```

## 4. 레포 클론 + 환경변수

경로를 `/home/ubuntu/S15P21E205`로 **고정**한다. 10단계에서 Jenkins 컨테이너에
같은 경로로 마운트해야 compose의 상대경로 bind mount가 깨지지 않는다.

```bash
git clone https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21E205.git /home/ubuntu/S15P21E205
```

```bash
cd /home/ubuntu/S15P21E205/infra && cp .env.prod.example .env.prod
```

`.env.prod`를 열어 `DOMAIN`, `CERTBOT_EMAIL`, `POSTGRES_PASSWORD`를 채운다.
비밀번호는 `openssl rand -base64 24`로 만든다.

## 5. PostgreSQL

가장 아래 계층부터 하나씩 올린다.

```bash
cd /home/ubuntu/S15P21E205/infra && docker compose -f docker-compose.prod.yml --env-file .env.prod up -d postgres
```

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod ps
```

`STATUS`가 `healthy`가 될 때까지 기다린다. 로컬 PC에서 확인하려면 SSH 터널을 연다.

```bash
ssh -L 5432:localhost:5432 -i key.pem ubuntu@j15e205.p.ssafy.io
```

## 6. Kafka

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d kafka
```

```bash
docker exec qj-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list
```

운영 설정은 EXTERNAL 리스너가 없어서 **컨테이너 네트워크 안에서만** 접근된다.
로컬 PC에서 직접 붙으려 하면 실패하는데, 운영 서버는 이게 맞는 상태다.

## 7. Backend + Worker

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build backend worker
```

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f backend
```

DB 주소가 `localhost`가 아니라 서비스명 `postgres`인 점을 확인한다.
컨테이너 안의 `localhost`는 자기 자신이다.

## 8. Nginx + 프론트엔드

프론트를 빌드해 `infra/nginx/html/`에 넣는다. (`scripts/deploy.sh`가 이걸 자동화한다.)

```bash
cd /home/ubuntu/S15P21E205/frontend && npm ci && npm run build && cp -r dist/. ../infra/nginx/html/
```

이 시점에는 인증서가 없어서 nginx가 뜨지 않는다. **9단계 스크립트가 기동까지 처리하므로
여기서 따로 `up -d nginx`를 하지 않는다.**

## 9. HTTPS (Let's Encrypt)

nginx는 인증서가 없으면 뜨지 않고, certbot은 nginx가 떠 있어야 도메인을 검증한다.
이 닭-달걀 문제를 `init-letsencrypt.sh`가 임시 인증서로 푼다.

먼저 연습 모드로 도메인·방화벽 설정이 맞는지 확인한다. 실제 발급은 실패 횟수 제한
(주당 5회)이 있어서 바로 실행하면 잠길 수 있다.

```bash
cd /home/ubuntu/S15P21E205/infra && STAGING=1 ./scripts/init-letsencrypt.sh
```

성공하면 실제 인증서를 받는다.

```bash
cd /home/ubuntu/S15P21E205/infra && ./scripts/init-letsencrypt.sh
```

갱신 cron 등록 (`crontab -e`):

```
0 3 * * 1 /home/ubuntu/S15P21E205/infra/scripts/renew-cert.sh >> /var/log/certbot-renew.log 2>&1
```

## 10. Jenkins

앱 스택과 **별도 compose 프로젝트**로 띄운다. 같은 파일에 넣으면 Jenkins가 배포하면서
자기 자신을 재시작해 파이프라인이 끊긴다.

```bash
cd /home/ubuntu/S15P21E205/infra/jenkins && docker compose --env-file ../.env.prod up -d --build
```

```bash
docker logs qj-jenkins 2>&1 | grep -A2 'Please use the following password'
```

로컬에서 SSH 터널을 열고 `http://localhost:9090`으로 접속한다.

```bash
ssh -L 9090:localhost:9090 -i key.pem ubuntu@j15e205.p.ssafy.io
```

초기 설정에서 반드시 **관리자 비밀번호 변경**과 **익명 가입 차단**을 한다.

## 11. 배포 파이프라인

Jenkins에 Pipeline Job을 만들고 *Pipeline script from SCM* →
Script Path에 `infra/jenkins/Jenkinsfile.deploy`를 지정한다.

**트리거 선택** — Jenkins를 `127.0.0.1`에만 묶으면 GitLab Webhook이 들어오지 못한다.

| 방식 | 장점 | 단점 |
|------|------|------|
| **Poll SCM** (`H/2 * * * *`) | Jenkins를 완전히 비공개로 유지 | 최대 2분 지연 |
| nginx `/jenkins` 프록시 + Webhook | 즉시 트리거 | Jenkins가 인터넷에 노출 |

초반에는 Poll SCM으로 시작하고, 익숙해지면 Webhook으로 바꾼다. Webhook을 쓸 때는
`jenkins/docker-compose.yml`의 `JENKINS_OPTS: --prefix=/jenkins` 주석을 풀고
nginx 템플릿에 해당 `location`을 추가해야 정적 리소스가 깨지지 않는다.

수동 배포도 같은 스크립트를 쓴다.

```bash
cd /home/ubuntu/S15P21E205/infra && ./scripts/deploy.sh
```

## 12. 백업 · 정리 cron

`crontab -e`:

```
30 4 * * * /home/ubuntu/S15P21E205/infra/scripts/backup-db.sh >> /var/log/qj-backup.log 2>&1
0  5 * * 0 docker system prune -af --filter "until=168h"
```

---

## 자주 겪는 함정

| 증상 | 원인 |
|------|------|
| DB 접속 실패 (`Connection refused`) | 접속 주소를 `localhost`로 씀. 컨테이너 안에서는 서비스명(`postgres`)을 써야 한다 |
| nginx가 기동 실패 | 인증서가 없는데 443 블록을 읽음 → 9단계 스크립트로 발급해야 한다 |
| Jenkins 배포 시 설정 파일이 빈 채로 마운트됨 | Jenkins 워크스페이스에서 compose를 실행함. 반드시 `$DEPLOY_DIR`에서 실행한다 |
| Worker를 늘렸는데 처리량이 그대로 | Kafka 파티션 수가 Worker 수보다 적음. 남는 Worker는 논다 |
| 백테스트가 로컬보다 느림 | Numba/OpenBLAS 스레드 수. compose에 `NUMBA_NUM_THREADS=1` 등이 걸려 있는지 확인 |
| 컨테이너가 갑자기 죽음 | 메모리 상한 미설정 → 커널 OOM killer. `docker stats`로 확인 |
