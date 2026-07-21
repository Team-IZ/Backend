# PostgreSQL DataSource 설정

애플리케이션은 PostgreSQL 연결 정보를 코드에 저장하지 않고 환경변수로 받는다. 로컬에서는 기존 `application.yaml`의 선택적 `.env` import를 사용할 수 있지만, `.env` 파일은 저장소에 포함하지 않는다.

## 필수 환경변수

| 이름 | 예시 형식 | 설명 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/izget` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `izget_app` | 애플리케이션 전용 DB 사용자 |
| `DB_PASSWORD` | 별도 보안 저장소에서 주입 | DB 비밀번호 |
| `JWT_SECRET` | 32바이트 이상의 임의 문자열 | JWT 서명 키 |

운영 환경에서는 DB 사용자에게 스키마 전체 관리자 권한을 주지 않고 애플리케이션 동작에 필요한 최소 권한만 부여한다.

환경변수 이름은 운영체제와 배포 도구에서 널리 사용하는 대문자 `SNAKE_CASE` 형식으로 통일한다. `.env`가 properties 형식으로 import되므로 아래 이름은 `application.yaml`의 placeholder와 정확히 일치해야 한다.

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `JWT_SECRET`, `JWT_ACCESS_TOKEN_EXPIRATION`, `JWT_REFRESH_TOKEN_EXPIRATION`
- `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE` 등 선택 설정

소문자나 점 표기법도 별도 매핑을 구성하면 사용할 수 있지만, 현재 프로젝트에서는 혼용하지 않는다.

## 로컬 임시 JWT 키

`local` 프로필에서는 `JWT_SECRET`이 없을 때 `application-local.yaml`이 실행 시점에 임시 키를 생성한다. 저장소나 로그에는 키가 기록되지 않으며 애플리케이션을 재시작하면 기존 JWT는 모두 무효화된다.

IntelliJ Run Configuration의 Active profiles에 `local`을 지정하거나 다음 환경변수를 사용한다.

```text
SPRING_PROFILES_ACTIVE=local
```

운영·배포 환경에서는 반드시 `JWT_SECRET`을 보안 저장소에서 주입해야 하며 `local` 프로필을 활성화하지 않는다.

## 선택 환경변수

| 이름 | 기본값 | 설명 |
|---|---:|---|
| `DB_POOL_MAX_SIZE` | `10` | HikariCP 최대 풀 크기 |
| `DB_POOL_MIN_IDLE` | `2` | 최소 유휴 연결 수 |
| `DB_CONNECTION_TIMEOUT_MS` | `30000` | 연결 획득 제한 시간 |
| `DB_VALIDATION_TIMEOUT_MS` | `5000` | 연결 검증 제한 시간 |
| `DB_IDLE_TIMEOUT_MS` | `600000` | 유휴 연결 유지 시간 |
| `DB_MAX_LIFETIME_MS` | `1800000` | 연결 최대 수명 |
| `JPA_DDL_AUTO` | `validate` | Hibernate 스키마 정책 |
| `JPA_SHOW_SQL` | `false` | SQL 로그 출력 여부 |
| `JPA_BATCH_SIZE` | `50` | Hibernate JDBC 배치 크기 |

`JPA_DDL_AUTO`의 기본값은 `validate`이므로 애플리케이션이 운영 스키마를 임의 변경하지 않는다. 실제 스키마 변경은 이후 Flyway 같은 마이그레이션 도구로 관리하는 것을 전제로 한다.

## 실행 전 확인

IDE에서 실행할 때는 IDE의 Run Configuration에도 필수 환경변수를 전달해야 한다. 터미널 셸에 설정한 값은 IntelliJ 실행 구성에 자동 전달되지 않을 수 있다.

다음 조건이 모두 충족되어야 기본 프로필로 애플리케이션이 시작된다.

1. `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`이 실행 프로세스에 설정되어 있다.
2. `DB_URL`은 `jdbc:postgresql://호스트:포트/데이터베이스` 형식이다.
3. 해당 호스트와 포트에서 PostgreSQL이 실행 중이다.
4. 지정한 데이터베이스가 존재하고 계정에 접속 및 스키마 조회 권한이 있다.
5. `JPA_DDL_AUTO=validate`를 사용할 때 엔티티에 대응하는 스키마가 이미 생성되어 있다.

Hibernate Dialect는 `PostgreSQLDialect`로 명시되어 있다. 따라서 이후 시작 실패가 발생하면 Dialect 추론 오류 대신 연결 거부, 인증 실패, 데이터베이스 없음 또는 스키마 검증 실패처럼 실제 연결 원인을 확인할 수 있다.

## 테스트

`test` 프로필은 H2의 PostgreSQL 호환 모드를 사용한다. 이는 Spring 컨텍스트와 기본 JPA 설정 검증을 위한 것이며 PostgreSQL 고유 타입, 인덱스, SQL 문법을 완전히 보장하지 않는다. 실제 데이터 접근 구현 이후에는 Testcontainers 기반 PostgreSQL 통합 테스트를 추가한다.
