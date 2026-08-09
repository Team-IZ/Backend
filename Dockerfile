FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --version
COPY src ./src
RUN ./gradlew build -x test --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd -r app && useradd -r -g app -u 1001 -d /app app
COPY --from=build --chown=app:app /app/build/libs/app.jar app.jar
USER app
EXPOSE 8080
# MaxRAMPercentage: App Runner InstanceConfiguration.Memory 값의 70%까지 힙으로 사용 --
# 고정 -Xmx는 Railway 시절(384m) 값이라 App Runner의 2GB 할당량을 못 씀. feat/ci 브랜치의
# EC2용 Dockerfile과 동일 패턴으로 통일.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+UseSerialGC", "-Xss512k", "-XX:MaxMetaspaceSize=192m", "-XX:MaxDirectMemorySize=64m", "-XX:+ExitOnOutOfMemoryError", "-Dserver.port=8080", "-jar", "app.jar"]
