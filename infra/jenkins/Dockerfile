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

# Jenkins Job의 GitLab Push Webhook 트리거를 이미지 재생성 후에도 보장한다.
RUN jenkins-plugin-cli --plugins gitlab-plugin
