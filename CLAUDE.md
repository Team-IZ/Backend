# Backend 프로젝트 작업 가이드

## 프로젝트 개요

- Java 17 toolchain을 사용하는 Spring Boot 4.1.0 기반 REST API 프로젝트입니다.
- 빌드는 Gradle Wrapper로 실행합니다. 시스템에 설치된 Gradle은 사용하지 않습니다.
- 기본 패키지는 `com.bigproject.backend`입니다.
- 운영 데이터베이스 드라이버는 PostgreSQL이며, AWS S3 및 EC2 SDK를 사용합니다.

## 주요 경로

- `src/main/java/com/bigproject/backend/`: 애플리케이션 소스
- `src/main/resources/application.yaml`: Spring Boot 기본 설정
- `src/test/java/com/bigproject/backend/`: 테스트 소스
- `build.gradle`: 의존성 및 빌드 설정

## 명령어

프로젝트 루트에서 다음 명령을 사용합니다.

```bash
./gradlew tasks --quiet  # Gradle 설정과 사용 가능한 작업 확인
./gradlew test           # 테스트 실행
./gradlew build          # 컴파일, 테스트, 패키징
./gradlew bootRun        # 로컬 애플리케이션 실행
```

변경 후에는 최소한 관련 테스트를 실행하고, 실행이 어려운 경우 원인을 결과에 명시합니다.

## 구현 규칙

- 기존 Java·Gradle 파일의 탭 들여쓰기와 코드 스타일을 유지합니다.
- Spring 계층을 추가할 때 웹 요청은 Controller, 비즈니스 규칙은 Service, 영속성 접근은 Repository에 둡니다.
- 외부 입력은 DTO로 받고 Bean Validation 애너테이션으로 검증합니다.
- 인증·인가 변경은 Spring Security 설정 및 JWT 처리 흐름 전체에 미치는 영향을 함께 확인합니다.
- 라이브러리를 추가하거나 제거하면 `build.gradle`의 설명 주석도 함께 갱신합니다.

## 설정 및 보안

- `application.yaml`은 선택적 `.env` 파일을 불러옵니다.
- `.env`와 `.env.*`에는 비밀값이 있을 수 있으므로 읽거나 수정하거나 커밋하지 않습니다. 필요한 값은 키 이름만 문서화하고 실제 값은 사용자에게 요청합니다.
- 자격 증명, 토큰, 데이터베이스 비밀번호, AWS 키를 소스·테스트·로그에 작성하지 않습니다.
- 파괴적 명령(`git reset --hard`, `git clean`, 대량 삭제)과 원격 저장소 변경은 사용자의 명시적 승인 후에만 실행합니다.

## 작업 절차

1. 작업 전에 관련 코드와 현재 Git 변경 사항을 확인합니다.
2. 요청 범위에 맞게 최소한으로 수정하고 기존 사용자 변경은 보존합니다.
3. 포맷·컴파일·테스트를 요청 범위와 변경 위험에 맞게 검증합니다.
4. 변경 파일, 검증 결과, 남은 제약 사항을 간결하게 보고합니다.
