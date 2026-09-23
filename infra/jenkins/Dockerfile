# 공식 이미지에는 docker CLI가 없어 직접 넣는다. docker.sock을 쓰려면 필요하다.
FROM jenkins/jenkins:lts-jdk21

USER root

# 프론트엔드 CI(npm ci / lint / test / build)를 이 컨테이너 안에서 실행하므로 Node가 필요하다.
# 베이스 이미지에는 Node도 npm도 들어 있지 않아, 없으면 Frontend 스테이지가 첫 명령에서
# "npm: not found" 로 끝난다.
#
# 메이저 버전을 24로 고정한 이유는 두 가지다.
#   1) frontend 테스트는 Node 22.18 이상을 요구한다. node --test 의 글롭 패턴 인자,
#      node:module 의 registerHooks(22.15 이상), 플래그 없는 TypeScript 타입
#      스트리핑(22.18 이상)을 모두 쓰기 때문이다. 실제로 컨테이너에서 확인한 결과
#      24.19.0 과 22.23.2 는 13개 테스트를 통과했고, 20.20.2 는 글롭 패턴을 해석하지
#      못해 "Could not find src/**/*.test.ts" 로 끝났다.
#   2) 팀의 로컬 개발 환경이 24.x 이므로 같은 메이저를 맞춰야 CI에서만 나타나는 차이를 줄일 수 있다.
ARG NODE_MAJOR=24

# Worker·Data CI 는 첫 명령에서 python3 -m venv 로 가상환경을 만든다. Data 는 그 안에서
# pip install 을 돌리고, Worker 는 uv 를 설치할 부트스트랩 가상환경을 만든 뒤 uv sync 로 설치한다
# (Jenkinsfile Worker Setup). 베이스 이미지에 python3 는 있지만 venv 모듈이 빠져 있어, 없으면 두
# Setup 모두 첫 명령에서 "ensurepip is not available" 로 끝난다. python3-venv 는 이미지의 기본
# python3 버전에 맞는 것을 가져온다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg python3-venv \
 && install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
 && chmod a+r /etc/apt/keyrings/docker.asc \
 && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian bookworm stable" \
    > /etc/apt/sources.list.d/docker.list \
 && curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key -o /tmp/nodesource.key \
 && gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg /tmp/nodesource.key \
 && rm /tmp/nodesource.key \
 && chmod a+r /etc/apt/keyrings/nodesource.gpg \
 && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${NODE_MAJOR}.x nodistro main" \
    > /etc/apt/sources.list.d/nodesource.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin git nodejs \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

USER jenkins

# 이미지를 다시 만들어도 플러그인이 유지되도록 여기에 고정한다.
#   gitlab-plugin        Jenkins Job의 GitLab Push Webhook 트리거
#   pipeline-graph-view  Pipeline Overview 화면. 스테이지를 순서대로 보여주고,
#                        스테이지를 선택하면 그 구간의 로그만 펼쳐 준다.
#                        (Blue Ocean은 유지보수가 중단되어 이 플러그인이 후속이다)
#   ansicolor            Jenkinsfile의 ansiColor('xterm') 지시어가 동작하려면 필요하다.
#                        이 플러그인 없이 지시어만 있으면 파이프라인이 기동에 실패한다.
#   mattermost           Jenkinsfile 의 mattermostSend 로 빌드 결과를 팀 채널에 알린다.
#                        UI 로 설치해 두면 볼륨에만 남아, 볼륨을 새로 만드는 순간 사라지고
#                        두 파이프라인 모두 post 블록에서 실패한다.
#   warnings-ng          ESLint·Spotless·ruff 같은 정적 분석 결과를 빌드 화면에 목록으로
#                        모아 준다. 지금은 설치만 해 둔다 — Jenkinsfile에서 recordIssues 로
#                        각 lint 출력을 연결하는 일은 실제 Jenkins에서 파서와 출력 형식을
#                        맞춰 봐야 하므로 후속 작업으로 남긴다. 설치가 먼저 있어야 그 작업을
#                        시작할 수 있다.
#   throttle-concurrents thorp-ci 를 동시에 2개까지만 실행하고 나머지를 대기열에 남긴다.
#                        설정은 init.groovy.d/20-ci-concurrency.groovy 가 건다. 이 플러그인이
#                        없으면 그 스크립트가 실패해 상한 없이 실행 슬롯 수만큼 돈다.
RUN jenkins-plugin-cli --plugins gitlab-plugin pipeline-graph-view ansicolor mattermost warnings-ng throttle-concurrents
