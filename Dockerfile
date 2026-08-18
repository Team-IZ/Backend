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
# 🔴 --chown은 복사한 파일에만 걸린다. WORKDIR이 만든 /app 자체는 root:root로 남아, uid 1001은
# 그 밑에 디렉터리를 만들 수 없다. app.submission.storage-root 기본값이 상대경로(./var/submissions)라
# 런타임에 /app/var/submissions를 새로 만들려다 AccessDeniedException → ARTIFACT_STORE_FAILED(500)로
# ZIP 제출이 전부 거부됐다(35차 R1 · 37차 R1b). 디렉터리를 미리 만들고 소유권을 넘긴다.
RUN mkdir -p /app/var/submissions && chown -R app:app /app
USER app
EXPOSE 8080
# MaxRAMPercentage: App Runner InstanceConfiguration.Memory 값의 70%까지 힙으로 사용 --
# 고정 -Xmx는 Railway 시절(384m) 값이라 App Runner의 2GB 할당량을 못 씀. feat/ci 브랜치의
# EC2용 Dockerfile과 동일 패턴으로 통일.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+UseSerialGC", "-Xss512k", "-XX:MaxMetaspaceSize=192m", "-XX:MaxDirectMemorySize=64m", "-XX:+ExitOnOutOfMemoryError", "-Dserver.port=8080", "-jar", "app.jar"]
