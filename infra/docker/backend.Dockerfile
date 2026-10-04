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

# --- jre stage ---
# 실행 이미지에 JRE 전체(eclipse-temurin:21-jre, 약 460MB)를 싣지 않고 앱이 쓰는 모듈만 jlink 로 묶는다.
# 실행 이미지가 Alpine(musl)이라 jlink 도 Alpine JDK 로 돌린다. glibc JDK 로 만든 런타임은 Alpine 에서 뜨지 않는다.
# 모듈 목록은 jdeps 가 jar 의 클래스와 라이브러리를 읽어 정한다. jdeps 가 보지 못하는 것만 직접 더한다.
#   jdk.crypto.ec    외부 API·SMTP 의 TLS 가 EC 키 교환을 쓴다. 서비스 로더로 찾으므로 jdeps 에 잡히지 않는다
#   jdk.charsets     응답의 Content-Type 이 EUC-KR 같은 비표준 문자셋이면 이 모듈이 있어야 디코딩된다
#   jdk.localedata   ko 로캘 서식. 없으면 오류 없이 기본 로캘로 내려가 출력만 달라지므로 미리 넣는다
#   jdk.management   Actuator 의 JVM 지표가 com.sun.management 를 리플렉션으로 읽는다
#   jdk.zipfs        jar·zip 을 파일 시스템으로 열 때 서비스 로더로 찾는 모듈이라 jdeps 에 잡히지 않는다. 작아서 미리 넣는다
FROM eclipse-temurin:21-jdk-alpine AS jre
WORKDIR /work
COPY --from=build /app/build/libs/*.jar app.jar
RUN jar xf app.jar \
 && jdeps --ignore-missing-deps -q --recursive --multi-release 21 --print-module-deps \
        --class-path 'BOOT-INF/lib/*' BOOT-INF/classes > modules.txt \
 && echo "jdeps modules: $(cat modules.txt)" \
 && jlink --add-modules "$(cat modules.txt),jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.management,jdk.zipfs" \
        --include-locales=en,ko \
        --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
        --output /opt/jre \
 && /opt/jre/bin/java -version

# --- runtime stage ---
FROM alpine:3.22
ENV JAVA_HOME=/opt/jre \
    PATH="/opt/jre/bin:$PATH"
COPY --from=jre /opt/jre /opt/jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
