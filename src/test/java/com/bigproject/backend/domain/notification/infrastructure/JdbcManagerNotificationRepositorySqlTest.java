package com.bigproject.backend.domain.notification.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * findInbox를 실제 PostgreSQL 스키마에 대고 실행한다.
 *
 * assessment_round_attendance 등 View를 읽으므로 테이블 DDL 뿐 아니라 View DDL까지
 * 올라간 컨테이너가 필요하다({@link com.bigproject.backend.domain.projectexecution.infrastructure.JdbcClassProgressQueryRepositorySqlTest}와 같은 준비 절차).
 *   docker run -d --name pg-verify -p 55440:5432 \
 *     -e POSTGRES_PASSWORD=devcheck -e POSTGRES_DB=checkdb postgres:16
 *   docker exec pg-verify psql -U postgres -d checkdb -f .../_DDL.sql
 *   docker exec pg-verify psql -U postgres -d checkdb -f .../_View.sql
 */
class JdbcManagerNotificationRepositorySqlTest {
	private static final String URL = "jdbc:postgresql://localhost:55440/checkdb";

	private final UUID managerId = UUID.randomUUID();
	private final UUID cohortId = UUID.randomUUID();

	@Test
	void findInboxParsesAndRunsAgainstTheRealSchema() {
		JdbcManagerNotificationRepository repository = repositoryOrSkip();

		assertThatCode(() -> repository.findInbox(managerId, cohortId)).doesNotThrowAnyException();
	}

	private JdbcManagerNotificationRepository repositoryOrSkip() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, "postgres", "devcheck");
		dataSource.setDriverClassName("org.postgresql.Driver");
		boolean reachable;
		try (var ignored = dataSource.getConnection()) {
			reachable = true;
		} catch (Exception exception) {
			reachable = false;
		}
		assumeTrue(reachable, "검증용 PostgreSQL 컨테이너가 없어 건너뜁니다.");
		return new JdbcManagerNotificationRepository(new JdbcTemplate(dataSource));
	}
}
