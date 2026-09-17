#!/usr/bin/env bash
# 운영 백엔드 이미지에 넣을 JAR를 만든다. deploy.sh 와 migrate-jobs-queue.sh 가 이미지 빌드 직전에 부른다.
#
# 왜 이미지 빌드 밖에서 만드는가 (S15P21E205-331): `docker compose build` 안에서 컴파일하면 그 빌드는
# 서버 Docker 데몬에서 돌아 Jenkins 컨테이너의 CPU 2개·메모리 4GB 상한을 받지 않는다. 2026-09-17 배포 중
# 서버 유휴 CPU가 0~20%까지 떨어졌다. Jenkins 안에서 만들면 CI와 같은 상한 안에서 돌고, CI가 채워 둔
# Gradle 캐시(/var/jenkins_home/.gradle)도 그대로 쓴다.
#
# 실행 위치에 따라 경로가 둘이다.
#   - JDK 21 이 있는 곳(Jenkins 컨테이너): 그 자리에서 gradlew 를 돌린다.
#   - JDK 21 이 없는 곳(서버에서 수동 실행): 임시 JDK 컨테이너에서 돌리고, 호스트 데몬이 띄우므로
#     CPU·메모리 상한을 docker run 에 직접 건다.
set -euo pipefail

BACKEND_DIR="$(cd "$(dirname "$0")/../../backend" && pwd)"

# 빌드 JVM 메모리. Jenkins 컨테이너의 메모리 4GB를 CI 두 개와 나눠 쓰므로 명시해서 묶는다.
#
# GRADLE_OPTS 로는 묶이지 않는다. --no-daemon 이어도 Gradle 은 빌드용 JVM 을 따로 띄우고(single-use daemon),
# GRADLE_OPTS 는 gradlew 를 띄우는 클라이언트 JVM 에만 걸린다. 예전 Dockerfile 의 GRADLE_OPTS=-Xmx1g 도 빌드
# JVM 에는 닿지 않아, 운영 배포 빌드는 Gradle 기본값(-Xmx512m)으로 돌아 왔다(2026-09-17 두 JVM 의 실행 인자로
# 확인). 그 값으로 배포가 계속 성공했으므로 같은 값을 명시한다. 명시해 두면 Gradle 기본값이나 Jenkins 의
# ~/.gradle/gradle.properties 가 바뀌어도 이 빌드의 상한은 그대로다.
GRADLE_JVMARGS="-Xmx512m -XX:MaxMetaspaceSize=384m"

# 이전 배포의 JAR가 남아 있으면, 이번 빌드가 JAR를 만들지 못했는데도 옛 JAR로 이미지를 만든다. JAR가 둘이면
# Dockerfile 의 `COPY build/libs/*.jar` 는 실패하지 않고 그중 하나만 넣는다. 그래서 매번 비우고 시작하고,
# 끝에서 하나인지 확인한다. 컴파일 결과(build/classes)는 남겨 두므로 바뀐 소스만 다시 컴파일한다.
rm -rf "$BACKEND_DIR/build/libs"

if command -v javac >/dev/null 2>&1 && javac -version 2>&1 | grep -qE '^javac 21(\.|$)'; then
    (cd "$BACKEND_DIR" && ./gradlew -Dorg.gradle.jvmargs="$GRADLE_JVMARGS" bootJar --no-daemon --console=plain)
else
    # 컨테이너 사용자를 호출한 사용자와 맞춘다. root 로 돌면 build/ 와 .gradle/ 이 root 소유가 되어, 다음
    # Jenkins 배포(uid 1000)가 그 안에 쓰지 못해 실패한다. 호출한 uid 가 이미지에 없거나 그 홈에 쓸 수 없을 수
    # 있으므로 HOME 과 Gradle 캐시를 쓸 수 있는 곳으로 준다. 캐시는 backend/.gradle 아래라 git 이 무시하고
    # 이미지 빌드 컨텍스트에서도 빠진다.
    docker run --rm --memory=2g --cpus=2 \
        --user "$(id -u):$(id -g)" \
        -e HOME=/tmp \
        -e GRADLE_USER_HOME=/app/.gradle/user-home \
        -v "$BACKEND_DIR:/app" \
        -w /app \
        eclipse-temurin:21-jdk \
        ./gradlew -Dorg.gradle.jvmargs="$GRADLE_JVMARGS" bootJar --no-daemon --console=plain
fi

# 이미지에 들어갈 JAR는 정확히 하나여야 한다.
shopt -s nullglob
jars=("$BACKEND_DIR"/build/libs/*.jar)
if (( ${#jars[@]} != 1 )); then
    echo "오류: $BACKEND_DIR/build/libs 에 JAR가 ${#jars[@]}개 있다. 하나여야 한다." >&2
    exit 1
fi
echo "백엔드 JAR: ${jars[0]}"
