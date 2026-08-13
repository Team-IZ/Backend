package com.bigproject.backend.domain.member.infrastructure;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcCommitEmailRepositoryTest {
	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
	private final JdbcCommitEmailRepository repository = new JdbcCommitEmailRepository(jdbcTemplate);

	@Test
	void clearsVerificationTraceSoStatusDowngradeSatisfiesCheckConstraint() {
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

		repository.updateCommitEmail(
				UUID.randomUUID(),
				"gildong@example.com",
				"gildong@example.com",
				Instant.parse("2026-08-06T09:14:02Z")
		);

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
		// ck_app_user_commit_email_verified_at: status='VERIFIED' ⟺ verified_at NOT NULL.
		// PENDING으로 내리면서 검증 흔적을 비우지 않으면 이미 VERIFIED였던 사용자의 변경이 실패한다.
		assertThat(sql.getValue())
				.contains("commit_email_status = 'PENDING'")
				.contains("commit_email_verified_at = NULL")
				.contains("commit_email_verified_by = NULL")
				.contains("commit_email_verification_method = NULL")
				.contains("commit_email_normalized = ?")
				.contains("deleted_at IS NULL");
	}

	@Test
	void scopesLookupToActiveAccount() {
		UUID userId = UUID.randomUUID();

		repository.findByUserId(userId);

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
		assertThat(sql.getValue())
				.contains("FROM app_user")
				.contains("deleted_at IS NULL");
	}
}
