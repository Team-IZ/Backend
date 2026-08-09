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
# 파일명은 build.gradle의 bootJar.archiveFileName이 정한다. 예전에는 기본값
# (backend-0.0.1-SNAPSHOT.jar)을 여기에 그대로 적어 두었는데, Railway 배포를 고치면서
# 그 값이 app.jar로 바뀌자 이 COPY가 없는 파일을 가리켜 이미지 빌드가 통째로 실패했다.
# 두 곳에 같은 이름을 적어 두면 한쪽만 바뀌는 순간 배포가 멈추므로, 와일드카드로 받아
# 이름이 무엇이든 따라가게 한다. plain.jar는 bootJar가 아니라 제외해야 한다.
COPY --from=build --chown=app:app /app/build/libs/*.jar /tmp/libs/
RUN set -eu; \
	jar="$(find /tmp/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1)"; \
	[ -n "$jar" ] || { echo "실행 가능한 부트 JAR을 찾지 못했습니다."; ls -al /tmp/libs; exit 1; }; \
	mv "$jar" /app/app.jar; \
	chown app:app /app/app.jar; \
	rm -rf /tmp/libs
USER app
EXPOSE 8080
# MaxRAMPercentage: App Runner InstanceConfiguration.Memory 값의 70%까지 힙으로 사용 --
# 고정 -Xmx는 Railway 시절(384m) 값이라 App Runner의 2GB 할당량을 못 씀. feat/ci 브랜치의
# EC2용 Dockerfile과 동일 패턴으로 통일.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+UseSerialGC", "-Xss512k", "-XX:MaxMetaspaceSize=192m", "-XX:MaxDirectMemorySize=64m", "-XX:+ExitOnOutOfMemoryError", "-Dserver.port=8080", "-jar", "app.jar"]
