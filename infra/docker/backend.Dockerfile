# --- build stage ---
FROM eclipse-temurin:21-jdk AS build
# 빌드 컨테이너에는 외부 메모리 상한이 걸리지 않아 힙을 직접 제한한다
ENV GRADLE_OPTS="-Xmx1g"
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# 소스를 넣기 전에 의존성만 받아 레이어로 굳힌다. 소스만 바뀐 빌드는 이 레이어를 재사용한다.
# 출력은 버린다 — 의존성 트리는 1000줄 가까이 배포 로그를 덮는데 읽을 일이 없고, 캐시를
# 채우는 효과는 출력과 무관하다. 경고·오류는 stderr 로 남으므로 그대로 보인다.
RUN ./gradlew dependencies --no-daemon --console=plain > /dev/null || true
COPY src ./src
RUN ./gradlew bootJar --no-daemon

# --- runtime stage ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
