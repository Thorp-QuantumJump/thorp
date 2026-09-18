# 백엔드 이미지. 단계(target)에 따라 JAR를 어디서 가져올지가 다르다.
#
#   prebuilt  운영 배포(docker-compose.prod.yml). scripts/build-backend-jar.sh 가 이미지 빌드 전에 만든
#             build/libs 의 JAR를 넣기만 한다. 이 이미지 안에서 다시 컴파일하면 그 빌드는 서버 Docker 에서
#             돌아 Jenkins 컨테이너의 CPU·메모리 상한을 받지 않고, 운영 서비스와 같은 CPU를 나눠 쓴다
#             (2026-09-17 배포 중 서버 유휴 CPU 0~20%, S15P21E205-331).
#   source    로컬 개발(docker-compose.dev.yml, target 미지정 시 기본값). 소스에서 JAR를 만든다.
#
# 단계 순서가 중요하다. 운영 배포는 Jenkins 컨테이너에서 도는데, 그 이미지에는 buildx 플러그인이 없어 compose 가
# 옛(classic) 빌더로 빌드한다. 옛 빌더는 파일 순서대로 target 까지의 단계를 모두 돌리므로, prebuilt 가 build 보다
# 앞에 있어야 운영 배포가 build 단계를 건너뛴다. BuildKit 은 target 에 필요한 단계만 돌리므로 순서와 무관하다.
# 반대로 source 를 옛 빌더로 빌드하면 prebuilt 단계까지 돌아 build/libs 에 JAR가 있어야 한다. 로컬 개발은
# Docker Desktop 의 BuildKit 으로 빌드하므로 영향이 없다.

# --- runtime base ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

# --- prebuilt: 운영 배포 ---
FROM runtime AS prebuilt
# JAR가 없으면 COPY 가 실패한다. 둘 이상이면 옛 빌더는 실패하지만 BuildKit 은 그중 하나만 남긴다. 옛 JAR가
# 섞여 들어가지 않도록 build-backend-jar.sh 가 매번 build/libs 를 비우고 JAR가 하나인지 확인한다.
COPY build/libs/*.jar app.jar

# --- build stage: 로컬 개발 ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# 소스를 넣기 전에 의존성만 받아 레이어로 굳힌다. 소스만 바뀐 빌드는 이 레이어를 재사용한다.
# 출력은 버린다 — 의존성 트리는 1000줄 가까이 배포 로그를 덮는데 읽을 일이 없고, 캐시를
# 채우는 효과는 출력과 무관하다. 경고·오류는 stderr 로 남으므로 그대로 보인다.
RUN ./gradlew dependencies --no-daemon --console=plain > /dev/null || true
COPY src ./src
RUN ./gradlew bootJar --no-daemon

# --- source: 로컬 개발 (기본) ---
FROM runtime AS source
COPY --from=build /app/build/libs/*.jar app.jar
