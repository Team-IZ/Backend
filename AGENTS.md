# Codex 작업 지침

## 프로젝트

- Java 17 toolchain, Spring Boot 4.1.0, Gradle Wrapper 기반 백엔드 프로젝트입니다.
- 애플리케이션 패키지 루트는 `com.bigproject.backend`입니다.
- PostgreSQL, Spring Security, JWT, AWS S3·EC2 SDK를 사용합니다.

## 작업 명령

프로젝트 루트에서 Gradle Wrapper만 사용합니다.

```bash
./gradlew tasks --quiet
./gradlew test
./gradlew build
./gradlew bootRun
```

변경 뒤에는 위험도에 맞는 Gradle 검증을 실행하고, 실행하지 못한 검증은 이유를 함께 보고합니다.

## 구현 규칙

- 기존 Java와 Gradle 파일의 탭 들여쓰기 및 코드 스타일을 유지합니다.
- 웹 요청 처리는 Controller, 비즈니스 로직은 Service, 영속성 접근은 Repository 계층에 둡니다.
- 외부 요청은 DTO로 표현하고 Bean Validation으로 검증합니다.
- 인증·인가 또는 JWT 변경 시 Spring Security 필터 체인과 예외 처리에 미치는 영향을 검토합니다.
- 의존성을 바꾸면 `build.gradle`의 설명 주석도 함께 갱신합니다.

## 보안 및 변경 관리

- `application.yaml`은 선택적 `.env` 설정을 불러옵니다. `.env`와 `.env.*`는 읽거나 수정하거나 커밋하지 않습니다.
- 자격 증명, 토큰, 데이터베이스 비밀번호, AWS 키를 코드·테스트·로그에 기록하지 않습니다.
- 기존 사용자의 변경 사항은 보존합니다.
- `git reset --hard`, `git clean`, 대량 삭제, `git push` 등 파괴적이거나 원격 상태를 변경하는 작업은 명시적인 승인 없이는 실행하지 않습니다.
