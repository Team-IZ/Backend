# syntax=docker/dockerfile:1
#
# Spring Boot 4.1.0 / Java 17 (build.gradle의 toolchain 기준)
# 멀티스테이지: 빌드용 JDK 이미지와 실행용 JRE 이미지를 분리해 최종 이미지 크기를 줄인다.
#
# 이 파일에는 DB 주소·계정·비밀번호가 일절 들어가지 않는다.
# 모든 접속 정보는 런타임 환경변수로만 주입한다(docker-compose.yml 참고).

# ---------------------------------------------------------------------------
# 1) build 스테이지 — 소스를 컴파일해 실행 가능한 jar를 만든다
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace

# 의존성 정의 파일을 소스보다 먼저 복사한다.
# 소스만 바뀐 빌드에서는 아래 의존성 레이어가 캐시로 재사용된다.
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew

COPY src src

# --mount=type=cache: Gradle 의존성 캐시를 빌드 간에 재사용한다(BuildKit 기능).
# -x test: 테스트는 CI(.github/workflows/ci.yml)에서 돌리므로 이미지 빌드에서는 건너뛴다.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon --console=plain -x test \
 && cp build/libs/*.jar /workspace/app.jar

# ---------------------------------------------------------------------------
# 2) runtime 스테이지 — jar만 들고 가는 가벼운 실행 이미지
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

# curl: docker-compose의 healthcheck에서 사용
# 비루트 사용자로 실행해 컨테이너 탈출 시 피해를 줄인다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -r app \
 && useradd -r -g app -u 1001 -d /app app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar
USER app

# ---------------------------------------------------------------------------
# JVM 힙 옵션 — t3.micro(메모리 1GB) 대응
# ---------------------------------------------------------------------------
# MaxRAMPercentage=70 : 컨테이너에 할당된 메모리 한도의 70%까지만 힙으로 쓴다.
#                       -Xmx를 숫자로 박지 않으므로 나중에 인스턴스를 키우면
#                       compose의 mem_limit만 올리면 힙도 따라 커진다.
# UseSerialGC         : 작은 힙·적은 코어에서 G1보다 메모리 오버헤드가 작다.
# MaxMetaspaceSize    : 메타스페이스 폭주로 컨테이너가 OOM Kill 되는 것을 막는다.
# ExitOnOutOfMemoryError : OOM 시 좀비 상태로 버티지 말고 즉시 종료 → restart 정책이 살린다.
#
# 전부 환경변수라 이미지를 다시 빌드하지 않고 compose에서 덮어쓸 수 있다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:MaxMetaspaceSize=160m -Xss512k -XX:+ExitOnOutOfMemoryError"

# 프로파일은 런타임에 주입한다. 비워두면 기본 프로파일로 뜬다.
# 예) SPRING_PROFILES_ACTIVE=prod
ENV SPRING_PROFILES_ACTIVE=""

EXPOSE 8080

# sh -c + exec: $JAVA_OPTS를 셸이 전개하되, java가 PID 1을 이어받아
# docker stop의 SIGTERM을 직접 받도록 한다(graceful shutdown의 전제).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
