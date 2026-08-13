package com.bigproject.backend.domain.assessment.infrastructure;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JdbcTraineeHomeRoundRepositoryTest {
	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
	private final JdbcTraineeHomeRoundRepository repository = new JdbcTraineeHomeRoundRepository(jdbcTemplate);

	private final UUID traineeUserId = UUID.randomUUID();

	@Test
	void readsRoundCardsFromViewWithSingleCohortJoin() {
		repository.findAllByTraineeUserId(traineeUserId);

		String sql = capturedSql();
		assertThat(sql)
				.contains("FROM trainee_home_round_view v")
				// 기수 표시명만 View에 없어 조인 1개를 더한다.
				.contains("LEFT JOIN cohort c")
				.contains("WHERE v.trainee_user_id = ?");
		// 팀·반은 회차 시점 스냅샷 컬럼을 읽는다.
		assertThat(sql).contains("v.team_id_at_round").contains("v.class_id_at_round");
	}

	@Test
	void readsCommitEmailStatusExposedByV14() {
		repository.findAllByTraineeUserId(traineeUserId);

		// V-14로 View에 노출한 69번째 컬럼. 없으면 배너를 그릴 원천이 사라진다.
		assertThat(capturedSql()).contains("v.commit_email_status");
	}

	@Test
	void doesNotReadAvailableSubmissionMethodsFromView() {
		repository.findAllByTraineeUserId(traineeUserId);

		// V-12(::TEXT 캐스팅 오류)·V-13(allow_github_integration 무시)이 남아 있어 정책을 직접 읽는다.
		assertThat(capturedSql()).doesNotContain("available_submission_methods");
	}

	@Test
	void scopesMembershipFallbackToActiveCohortAndClassAssignment() {
		repository.findMembershipByUserId(traineeUserId);

		assertThat(capturedSql())
				.contains("FROM cohort_member cm")
				.contains("cm.status = 'ACTIVE'")
				// 반 이동 이력에서 현재 배정만 남긴다.
				.contains("clm.unassigned_at IS NULL")
				.contains("LEFT JOIN class cl");
	}

	@Test
	void readsLatestActiveOrganizationPolicyForSubmissionMethods() {
		repository.findSubmissionMethodPolicyByUserId(traineeUserId);

		assertThat(capturedSql())
				.contains("p.allow_github_integration")
				.contains("p.allow_zip_submission")
				.contains("p.status = 'ACTIVE'")
				.contains("ORDER BY p.policy_version DESC");
	}

	@SuppressWarnings("unchecked")
	private String capturedSql() {
		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
		return sql.getValue();
	}
}
