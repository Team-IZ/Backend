package com.bigproject.backend.domain.usagemetering.infrastructure;

import com.bigproject.backend.domain.usagemetering.domain.OperationsActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 기간 활동량 집계 (목업 SA-02 ③ `사용 규모` · OP-06 ⑤ 반별 세션 수).
 *
 * <p>모든 쿼리가 {@code org_id}를 조건에 넣는다 — 세 테이블 모두 {@code org_id}를 직접 들고 있어
 * 기관 경계를 조인 없이 세울 수 있다. 테넌트 경계를 조인 결과에 의존시키지 않는 편이 안전하다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcOperationsActivityRepository implements OperationsActivityRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public long countCompletedSessions(UUID organizationId, Instant from, Instant to) {
		String sql = """
				SELECT COUNT(*) AS cnt
				FROM assessment_session
				WHERE org_id = ?
					AND status = 'COMPLETED'
					AND ended_at >= ?
					AND ended_at < ?
				""";
		return count(sql, organizationId, from, to);
	}

	@Override
	public long countGradingRounds(UUID organizationId, Instant from, Instant to) {
		// submission_due_at은 NOT NULL이라 기간 필터가 행을 조용히 흘리지 않는다.
		String sql = """
				SELECT COUNT(*) AS cnt
				FROM project_assessment_round
				WHERE org_id = ?
					AND deleted_at IS NULL
					AND submission_due_at >= ?
					AND submission_due_at < ?
				""";
		return count(sql, organizationId, from, to);
	}

	@Override
	public long countPublishedReports(UUID organizationId, Instant from, Instant to) {
		String sql = """
				SELECT COUNT(*) AS cnt
				FROM report
				WHERE org_id = ?
					AND published_at IS NOT NULL
					AND published_at >= ?
					AND published_at < ?
				""";
		return count(sql, organizationId, from, to);
	}

	@Override
	public Map<UUID, Long> countCompletedSessionsByClass(
			UUID organizationId, UUID cohortId, Instant from, Instant to
	) {
		/*
		 * assessment_session에는 class_id가 없다. 반은 교육생의 배정에서 나오므로 네 단계를 타고 내려간다.
		 *   assessment_session → measurement_attempt(cohort_id·user_id) → cohort_member → class_membership
		 *
		 * cohort_member 조인 키를 (cohort_id, user_id)로 잡는 이유: 같은 사람이 여러 기수에 속할 수 있어
		 * user_id만으로 조인하면 다른 기수의 반까지 붙어 세션이 중복 집계된다.
		 *
		 * 배정이 해제된(unassigned_at) 행은 제외한다 — 지금 그 반의 세션이 아니다.
		 */
		String sql = """
				SELECT clm.class_id, COUNT(*) AS cnt
				FROM assessment_session s
				JOIN measurement_attempt ma
				  ON ma.attempt_id = s.attempt_id
				JOIN cohort_member cm
				  ON cm.cohort_id = ma.cohort_id
				 AND cm.user_id = ma.user_id
				JOIN class_membership clm
				  ON clm.cohort_member_id = cm.cohort_member_id
				 AND clm.unassigned_at IS NULL
				WHERE s.org_id = ?
					AND ma.cohort_id = ?
					AND s.status = 'COMPLETED'
					AND s.ended_at >= ?
					AND s.ended_at < ?
				GROUP BY clm.class_id
				""";

		return jdbcTemplate.query(sql, (ResultSet rs) -> {
			Map<UUID, Long> result = new HashMap<>();
			while (rs.next()) {
				result.put(rs.getObject("class_id", UUID.class), rs.getLong("cnt"));
			}
			return result;
		}, organizationId, cohortId, Timestamp.from(from), Timestamp.from(to));
	}

	private long count(String sql, UUID organizationId, Instant from, Instant to) {
		Long count = jdbcTemplate.queryForObject(
				sql, Long.class, organizationId, Timestamp.from(from), Timestamp.from(to)
		);
		return count == null ? 0L : count;
	}
}
