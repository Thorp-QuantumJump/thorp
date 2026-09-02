# --- build stage ---
FROM eclipse-temurin:21-jdk AS build
# 이 빌드 컨테이너에는 외부 메모리 상한이 걸리지 않으므로 Gradle JVM 힙을 직접 제한한다
ENV GRADLE_OPTS="-Xmx1g"
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon || true
COPY src ./src
RUN ./gradlew bootJar --no-daemon

# --- runtime stage ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
