# 공식 이미지에는 docker CLI가 없어 직접 넣는다. docker.sock을 쓰려면 필요하다.
FROM jenkins/jenkins:lts-jdk21

USER root

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
 && install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
 && chmod a+r /etc/apt/keyrings/docker.asc \
 && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian bookworm stable" \
    > /etc/apt/sources.list.d/docker.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin git \
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
RUN jenkins-plugin-cli --plugins gitlab-plugin pipeline-graph-view ansicolor
