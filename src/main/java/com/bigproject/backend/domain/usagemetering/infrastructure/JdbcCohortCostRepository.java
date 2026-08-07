package com.bigproject.backend.domain.usagemetering.infrastructure;

import com.bigproject.backend.domain.usagemetering.domain.CohortCostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * OP-06 ⑤ 비용 탭 조회 구현.
 *
 * <p>월 버킷은 {@code date_trunc('month', occurred_at AT TIME ZONE 'UTC')}로 만든다.
 * 저장은 TIMESTAMPTZ지만 집계 기준은 <b>UTC 고정</b>이다 — 서버 로컬 존을 쓰면
 * 배포 환경에 따라 월 경계가 흔들려 같은 달의 합계가 달라진다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcCohortCostRepository implements CohortCostRepository {

	private final JdbcTemplate jdbcTemplate;

	/** 단가 미설정 호출은 합계에서 제외한다 — 0으로 더하면 청구액이 실제보다 작아 보인다. */
	private static final String PRICED_COST =
			"COALESCE(SUM(COALESCE(u.actual_cost, u.estimated_cost)) "
					+ "FILTER (WHERE u.pricing_status <> 'UNPRICED'), 0)";

	private static final String MONTH_BUCKET =
			"date_trunc('month', u.occurred_at AT TIME ZONE 'UTC')";

	@Override
	public CohortPeriod findCohortPeriod(UUID organizationId, UUID cohortId) {
		String sql = """
				SELECT cohort_id, name, start_date, end_date
				FROM cohort
				WHERE org_id = ? AND cohort_id = ? AND deleted_at IS NULL
				""";
		try {
			return jdbcTemplate.queryForObject(sql, (rs, n) -> new CohortPeriod(
					rs.getObject("cohort_id", UUID.class),
					rs.getString("name"),
					rs.getObject("start_date", LocalDate.class),
					rs.getObject("end_date", LocalDate.class)
			), organizationId, cohortId);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	@Override
	public List<MonthlyAmount> findOrganizationMonthlyCost(UUID organizationId, Instant from, Instant to) {
		String sql = """
				SELECT %s AS bucket, %s AS cost
				FROM ai_usage u
				WHERE u.org_id = ?
				  AND u.occurred_at >= ? AND u.occurred_at < ?
				GROUP BY bucket
				ORDER BY bucket
				""".formatted(MONTH_BUCKET, PRICED_COST);

		return jdbcTemplate.query(sql, (rs, n) -> new MonthlyAmount(month(rs), cost(rs)),
				organizationId, Timestamp.from(from), Timestamp.from(to));
	}

	@Override
	public List<MonthlyCohortCost> findCohortMonthlyCost(
			UUID organizationId, UUID cohortId, Instant from, Instant to) {
		/*
		 * 세션 수를 같은 쿼리에 조인하지 않는다 — ai_usage는 호출당 1행이고 assessment_session은
		 * 세션당 1행이라 조인하면 행이 곱해져 비용이 부풀려진다. 월별 서브쿼리로 따로 세서 붙인다.
		 */
		String sql = """
				WITH cost AS (
				    SELECT %s AS bucket, %s AS amount
				    FROM ai_usage u
				    WHERE u.org_id = ? AND u.cohort_id = ?
				      AND u.occurred_at >= ? AND u.occurred_at < ?
				    GROUP BY bucket
				),
				sess AS (
				    -- assessment_session에는 cohort_id가 없다. 기수는 measurement_attempt를 타고 나온다.
				    SELECT date_trunc('month', s.ended_at AT TIME ZONE 'UTC') AS bucket,
				           COUNT(*) AS sessions
				    FROM assessment_session s
				    JOIN measurement_attempt ma ON ma.attempt_id = s.attempt_id
				    WHERE s.org_id = ? AND ma.cohort_id = ?
				      AND s.status = 'COMPLETED'
				      AND s.ended_at >= ? AND s.ended_at < ?
				    GROUP BY bucket
				)
				SELECT COALESCE(cost.bucket, sess.bucket) AS bucket,
				       COALESCE(cost.amount, 0)           AS cost,
				       COALESCE(sess.sessions, 0)         AS sessions
				FROM cost FULL OUTER JOIN sess ON cost.bucket = sess.bucket
				ORDER BY bucket
				""".formatted(MONTH_BUCKET, PRICED_COST);

		return jdbcTemplate.query(sql,
				(rs, n) -> new MonthlyCohortCost(month(rs), cost(rs), rs.getLong("sessions")),
				organizationId, cohortId, Timestamp.from(from), Timestamp.from(to),
				organizationId, cohortId, Timestamp.from(from), Timestamp.from(to));
	}

	@Override
	public List<MonthlyRoundName> findCohortMonthlyRoundNames(
			UUID organizationId, UUID cohortId, Instant from, Instant to) {
		// submission_due_at은 NOT NULL이라 기간 필터가 행을 조용히 흘리지 않는다.
		String sql = """
				SELECT date_trunc('month', r.submission_due_at AT TIME ZONE 'UTC') AS bucket,
				       r.round_name
				FROM project_assessment_round r
				WHERE r.org_id = ? AND r.cohort_id = ?
				  AND r.deleted_at IS NULL
				  AND r.submission_due_at >= ? AND r.submission_due_at < ?
				ORDER BY bucket, r.submission_due_at
				""";

		return jdbcTemplate.query(sql,
				(rs, n) -> new MonthlyRoundName(month(rs), rs.getString("round_name")),
				organizationId, cohortId, Timestamp.from(from), Timestamp.from(to));
	}

	@Override
	public List<CohortCard> findActiveCohortCards(UUID organizationId, Instant from, Instant to) {
		/*
		 * INNER JOIN이라 그 기간에 ai_usage 행이 없는 기수는 빠진다 — 의도한 동작이다.
		 * 부트캠프는 한 번에 한 기수를 돌리므로 평시에는 1건, 전환기에만 2건이 나온다.
		 */
		String sql = """
				SELECT c.cohort_id, c.name, c.start_date, c.end_date,
				       %s AS cost,
				       (SELECT COUNT(*) FROM cohort_member cm
				         WHERE cm.cohort_id = c.cohort_id AND cm.left_at IS NULL) AS trainee_count
				FROM cohort c
				JOIN ai_usage u
				  ON u.cohort_id = c.cohort_id
				 AND u.occurred_at >= ? AND u.occurred_at < ?
				WHERE c.org_id = ? AND c.deleted_at IS NULL
				GROUP BY c.cohort_id, c.name, c.start_date, c.end_date
				ORDER BY c.start_date DESC
				""".formatted(PRICED_COST);

		return jdbcTemplate.query(sql, (rs, n) -> new CohortCard(
				rs.getObject("cohort_id", UUID.class),
				rs.getString("name"),
				cost(rs),
				rs.getInt("trainee_count"),
				rs.getObject("start_date", LocalDate.class),
				rs.getObject("end_date", LocalDate.class)
		), Timestamp.from(from), Timestamp.from(to), organizationId);
	}

	@Override
	public List<ClassSummary> findClassSummaries(
			UUID organizationId, UUID cohortId, Instant from, Instant to) {
		/*
		 * 담당 매니저는 반 단위 기간형 배정이고 공동 담당이 허용되므로 가장 먼저 배정된 1명을 대표로 쓴다
		 * (JdbcOperationsCostRepository와 같은 규칙).
		 * 세션 수는 ai_usage와 조인하면 행이 곱해지므로 상관 서브쿼리로 센다.
		 */
		String sql = """
				SELECT cl.class_id,
				       cl.name,
				       (SELECT au.name FROM manager_assignment ma
				          JOIN app_user au ON au.user_id = ma.manager_user_id
				         WHERE ma.class_id = cl.class_id AND ma.status = 'ACTIVE'
				         ORDER BY ma.assigned_at
				         LIMIT 1) AS manager_name,
				       (SELECT COUNT(*)
				          FROM assessment_session s
				          JOIN measurement_attempt ma ON ma.attempt_id = s.attempt_id
				          JOIN cohort_member cm
				            ON cm.cohort_id = ma.cohort_id AND cm.user_id = ma.user_id
				          JOIN class_membership clm
				            ON clm.cohort_member_id = cm.cohort_member_id
				           AND clm.unassigned_at IS NULL
				         WHERE clm.class_id = cl.class_id
				           AND s.status = 'COMPLETED'
				           AND s.ended_at >= ? AND s.ended_at < ?) AS sessions,
				       %s AS cost
				FROM class cl
				LEFT JOIN ai_usage u
				       ON u.class_id = cl.class_id
				      AND u.occurred_at >= ? AND u.occurred_at < ?
				WHERE cl.org_id = ? AND cl.cohort_id = ? AND cl.deleted_at IS NULL
				GROUP BY cl.class_id, cl.name
				ORDER BY cl.name
				""".formatted(PRICED_COST);

		return jdbcTemplate.query(sql, (rs, n) -> new ClassSummary(
				rs.getObject("class_id", UUID.class),
				rs.getString("name"),
				rs.getString("manager_name"),
				cost(rs),
				rs.getLong("sessions")
		), Timestamp.from(from), Timestamp.from(to),
				Timestamp.from(from), Timestamp.from(to), organizationId, cohortId);
	}

	@Override
	public List<MonthlyClassAmount> findClassMonthlyCost(
			UUID organizationId, UUID cohortId, Instant from, Instant to) {
		String sql = """
				SELECT cl.class_id, %s AS bucket, %s AS cost
				FROM class cl
				JOIN ai_usage u
				  ON u.class_id = cl.class_id
				 AND u.occurred_at >= ? AND u.occurred_at < ?
				WHERE cl.org_id = ? AND cl.cohort_id = ? AND cl.deleted_at IS NULL
				GROUP BY cl.class_id, bucket
				""".formatted(MONTH_BUCKET, PRICED_COST);

		return jdbcTemplate.query(sql, (rs, n) -> new MonthlyClassAmount(
				rs.getObject("class_id", UUID.class), month(rs), cost(rs)
		), Timestamp.from(from), Timestamp.from(to), organizationId, cohortId);
	}

	private static YearMonth month(ResultSet rs) throws SQLException {
		Timestamp bucket = rs.getTimestamp("bucket");
		return YearMonth.from(bucket.toInstant().atZone(ZoneOffset.UTC));
	}

	private static BigDecimal cost(ResultSet rs) throws SQLException {
		BigDecimal value = rs.getBigDecimal("cost");
		return value == null ? BigDecimal.ZERO : value;
	}
}
