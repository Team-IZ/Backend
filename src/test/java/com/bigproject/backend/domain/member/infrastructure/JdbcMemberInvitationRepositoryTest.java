package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.InvitationToken;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.Role;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcMemberInvitationRepositoryTest {
	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
	private final JdbcMemberInvitationRepository repository = new JdbcMemberInvitationRepository(jdbcTemplate);

	@Test
	void createsPendingUserWithoutPartialCommitEmailState() {
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

		repository.createPendingUser(
				UUID.randomUUID(),
				"operator@example.com",
				"operator@example.com",
				"operator@example.com",
				Role.OPERATOR,
				"pending-password-hash",
				Instant.parse("2026-08-02T00:00:00Z")
		);

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
		verify(jdbcTemplate).update(sql.capture(), parameters.capture());
		assertThat(sql.getValue())
				.contains("INSERT INTO app_user")
				.doesNotContain("commit_email_status")
				.doesNotContain("'UNVERIFIED'");
		assertThat(parameters.getValue()).hasSize(10);
	}

	@Test
	void findsIncompleteInvitationByNormalizedEmail() {
		String email = "invited@example.com";
		when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(email))).thenReturn(true);

		assertThat(repository.existsIncompleteInvitationByNormalizedEmail(email)).isTrue();

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).queryForObject(sql.capture(), eq(Boolean.class), eq(email));
		assertThat(sql.getValue())
				.contains("FROM user_invitation")
				.contains("target_email_normalized = ?")
				.contains("'PENDING', 'SENT', 'DELIVERY_FAILED', 'EXPIRED'");
	}

	@Test
	void findsExistingTraineeEmailThroughRoleAndOrganization() {
		UUID organizationId = UUID.randomUUID();
		String email = "existing@example.com";
		when(jdbcTemplate.queryForObject(
				anyString(),
				eq(Boolean.class),
				eq(email),
				eq(organizationId)
		)).thenReturn(true);

		boolean exists = repository.existsOrganizationTraineeByNormalizedEmail(organizationId, email);

		assertThat(exists).isTrue();
		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).queryForObject(
				sql.capture(),
				eq(Boolean.class),
				eq(email),
				eq(organizationId)
		);
		assertThat(sql.getValue())
				.contains("JOIN \"role\"")
				.contains("JOIN organization")
				.contains("u.org_id = ?")
				.contains("r.code = 'TRAINEE'");
	}

	@Test
	void createsRoleScopedInvitationLedger() {
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		UUID invitedBy = UUID.randomUUID();

		UUID invitationId = repository.createInvitation(
				organizationId,
				"manager@example.com",
				"manager@example.com",
				Role.MANAGER,
				cohortId,
				invitedBy,
				Instant.parse("2026-08-02T00:00:00Z"),
				null
		);

		assertThat(invitationId).isNotNull();
		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
		verify(jdbcTemplate).update(sql.capture(), parameters.capture());
		// 반 배정은 초대 시점에 하지 않으므로 원장에 target_class_id를 쓰지 않는다.
		assertThat(sql.getValue())
				.contains("INSERT INTO user_invitation")
				.contains("target_role_code")
				.contains("target_cohort_id")
				.doesNotContain("target_class_id");
		assertThat(parameters.getValue()).contains(Role.MANAGER.name(), cohortId, invitedBy);
		// 단건 초대는 잡에 속하지 않는다. 여기에 값이 들어가면 안전망이 그 행을 집어 메일이 두 번 나간다.
		assertThat(parameters.getValue()[10]).isNull();
		assertThat(parameters.getValue()[11]).isNull();
	}

	/**
	 * 일괄 등록 행은 <b>적재 시점에 이미 클레임된 것</b>으로 넣는다.
	 *
	 * <p>자리를 확보한 인스턴스가 곧바로 발송을 이어받기 때문이다. 비워 두면 안전망 스케줄러가
	 * 발송 중인 초대를 다시 집어 교육생에게 메일이 두 통 간다 — 배포본이 셋이라 실제로 일어난다.
	 */
	@Test
	void claimsBatchInvitationsAtInsertTime() {
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
		Instant invitedAt = Instant.parse("2026-08-02T00:00:00Z");

		repository.createInvitation(
				UUID.randomUUID(),
				"trainee@example.com",
				"trainee@example.com",
				Role.TRAINEE,
				UUID.randomUUID(),
				UUID.randomUUID(),
				invitedAt,
				"batch-1"
		);

		ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
		verify(jdbcTemplate).update(anyString(), parameters.capture());
		assertThat(parameters.getValue()[10]).isEqualTo("batch-1");
		assertThat(parameters.getValue()[11]).isEqualTo(Timestamp.from(invitedAt));
	}

	/**
	 * 아웃박스 클레임에서 빠져서는 안 되는 조건들.
	 *
	 * <p>{@code FOR UPDATE SKIP LOCKED}가 없으면 세 인스턴스가 같은 초대를 집어 메일이 3통 나가고,
	 * {@code batch_request_id IS NOT NULL}이 없으면 동기 경로로 발송 중인 단건 초대(슈퍼어드민·매니저)까지
	 * 집어 간다. 둘 다 지우면 SQL은 그대로 돌고 중복 발송만 생긴다.
	 */
	@Test
	void claimsStalledInvitationsWithoutStealingFromOtherInstances() {
		when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
				.thenReturn(List.of());

		assertThat(repository.claimStalledTraineeInvitations(
				Instant.parse("2026-08-02T00:00:00Z"),
				Instant.parse("2026-08-01T23:50:00Z"),
				100
		)).isEmpty();

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate)
				.query(sql.capture(), any(PreparedStatementSetter.class), any(RowMapper.class));
		assertThat(sql.getValue())
				.contains("FOR UPDATE SKIP LOCKED")
				.contains("batch_request_id IS NOT NULL")
				.contains("target_role_code = 'TRAINEE'")
				.contains("status = 'PENDING'")
				.contains("RETURNING invitation_id");
		// 집을 것이 없으면 상세 조회로 넘어가지 않는다 — 빈 배열 바인딩은 왕복 낭비다.
		verify(jdbcTemplate, times(1))
				.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class));
	}

	/** 잡 진행률은 <b>기관·기수</b>로 좁혀야 한다. 식별자만으로 남의 배치가 보이면 안 된다. */
	@Test
	void scopesBatchProgressToTheCohortAndOrganization() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq("batch-1"),
				eq(organizationId), eq(cohortId)))
				.thenReturn(new MemberInvitationRepository.BatchProgress(4, 1, 1, 2));

		assertThat(repository.findBatchProgress("batch-1", organizationId, cohortId))
				.contains(new MemberInvitationRepository.BatchProgress(4, 1, 1, 2));

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).queryForObject(sql.capture(), any(RowMapper.class), eq("batch-1"),
				eq(organizationId), eq(cohortId));
		assertThat(sql.getValue())
				.contains("batch_request_id = ?")
				.contains("org_id = ?")
				.contains("target_cohort_id = ?")
				// 이미 가입한 교육생도 메일은 받았다. 빼면 진행률이 되레 줄어 보인다.
				.contains("status IN ('SENT', 'ACCEPTED')");
	}

	/** 집계가 0건이면 "그런 배치가 없다"와 같다 — 비워 올려 404로 잇는다. */
	@Test
	void reportsMissingBatchWhenNoInvitationMatches() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq("unknown"),
				eq(organizationId), eq(cohortId)))
				.thenReturn(new MemberInvitationRepository.BatchProgress(0, 0, 0, 0));

		assertThat(repository.findBatchProgress("unknown", organizationId, cohortId)).isEmpty();
	}

	@Test
	void linksOneTimeTokenToInvitationLedger() {
		UUID invitationId = UUID.randomUUID();
		Instant issuedAt = Instant.parse("2026-08-02T00:00:00Z");
		InvitationToken token = new InvitationToken(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				invitationId,
				"operator@example.com",
				"operator@example.com",
				InvitationPurpose.INVITE_OPERATOR_MANAGER,
				"hash",
				"{}",
				issuedAt,
				issuedAt.plusSeconds(3600),
				UUID.randomUUID(),
				"request-1"
		);

		repository.saveToken(token);

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
		verify(jdbcTemplate).update(sql.capture(), parameters.capture());
		assertThat(sql.getValue())
				.contains("INSERT INTO one_time_token")
				.contains("invitation_id");
		assertThat(parameters.getValue()).contains(invitationId);
	}
}
