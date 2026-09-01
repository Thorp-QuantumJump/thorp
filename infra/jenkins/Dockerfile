# 공식 Jenkins 이미지에는 docker CLI가 없다.
# 호스트의 docker.sock을 쓰려면(DooD) CLI와 compose 플러그인을 넣어야 한다.
# backend가 Java 21이라 jdk21 태그를 쓴다.
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
