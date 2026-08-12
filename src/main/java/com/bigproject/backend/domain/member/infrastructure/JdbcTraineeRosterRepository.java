package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.TraineeRosterRepository;
import com.bigproject.backend.domain.member.domain.TraineeRosterSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcTraineeRosterRepository implements TraineeRosterRepository {

	private final JdbcTemplate jdbcTemplate;

	private static final String ROSTER_SELECT = """
			SELECT cm.user_id AS trainee_id, u.name, u.email, u.status AS account_status,
			       c.class_id AS classroom_id, c.name AS class_name,
			       cm.joined_at, cm.left_at,
			       u.inactivated_reason_code, u.inactivated_reason, u.inactivated_at,
			       u.inactivated_by AS inactivated_by_id, actor.name AS inactivated_by_name,
			       /*
			        * 대기 중 초대 토큰(11차 R2). 재발송이 토큰 단위라 목록에 없으면 화면이 버튼을
			        * status='INVITED'로 유추해야 했다. 매니저·오퍼레이터 목록과 같은 방식으로 상관
			        * 서브쿼리로 둔다 — 조인하면 계정 한 명이 토큰 수만큼 중복 행으로 늘어난다.
			        */
			       (SELECT t.token_id FROM one_time_token t
			         WHERE t.user_id = cm.user_id AND t.purpose = 'INVITE_TRAINEE'
			           AND t.used_at IS NULL AND t.invalidated_at IS NULL
			         ORDER BY t.issued_at DESC
			         LIMIT 1) AS pending_invitation_token_id,
			       mv.assessment_round_id, mv.attempt_id, mv.row_result_status,
			       mv.concept_result_items::text AS concept_result_items,
			       mv.low_stage_concept_count, mv.excellent_occurrence_count,
			       mv.current_round_matched_risk_type_codes::text AS matched_risk_type_codes,
			       mv.row_aggregation_status
			FROM cohort_member cm
			JOIN app_user u ON u.user_id = cm.user_id AND u.deleted_at IS NULL
			LEFT JOIN class_membership csm ON csm.cohort_member_id = cm.cohort_member_id AND csm.unassigned_at IS NULL
			LEFT JOIN class c ON c.class_id = csm.class_id
			LEFT JOIN app_user actor ON actor.user_id = u.inactivated_by
			LEFT JOIN manager_trainee_roster_view mv
			  ON mv.user_id = cm.user_id AND mv.manager_user_id = ?::uuid
			 AND mv.assessment_round_id = ?::uuid
			""";

	@Override
	public Optional<CohortScope> findCohortScope(UUID cohortId) {
		String sql = "SELECT cohort_id, org_id FROM cohort WHERE cohort_id = ? AND deleted_at IS NULL";
		return jdbcTemplate.query(sql,
						(ResultSet rs, int rowNum) -> new CohortScope(
								rs.getObject("cohort_id", UUID.class), rs.getObject("org_id", UUID.class)),
						cohortId)
				.stream().findFirst();
	}

	@Override
	public Page<RosterRow> findRoster(RosterCriteria criteria, Pageable pageable) {
		StringBuilder where = new StringBuilder(" WHERE cm.cohort_id = ? AND cm.org_id = ?");
		List<Object> args = new ArrayList<>(List.of(criteria.cohortId(), criteria.orgId()));

		if (criteria.classroomId() != null) {
			where.append(" AND c.class_id = ?");
			args.add(criteria.classroomId());
		}
		if (criteria.unassignedOnly()) {
			where.append(" AND csm.class_id IS NULL");
		}
		if (criteria.rawAccountStatus() != null) {
			where.append(" AND u.status = ?");
			args.add(criteria.rawAccountStatus());
		}
		if (criteria.query() != null && !criteria.query().isBlank()) {
			where.append(" AND (u.name ILIKE ? OR u.email ILIKE ?)");
			String likeQuery = "%" + criteria.query().trim() + "%";
			args.add(likeQuery);
			args.add(likeQuery);
		}

		Long total = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM cohort_member cm "
						+ "JOIN app_user u ON u.user_id = cm.user_id AND u.deleted_at IS NULL "
						+ "LEFT JOIN class_membership csm ON csm.cohort_member_id = cm.cohort_member_id AND csm.unassigned_at IS NULL "
						+ "LEFT JOIN class c ON c.class_id = csm.class_id"
						+ where,
				Long.class, args.toArray());

		List<Object> pageArgs = new ArrayList<>();
		pageArgs.add(criteria.managerId());
		pageArgs.add(criteria.assessmentRoundId());
		pageArgs.addAll(args);
		pageArgs.add(pageable.getPageSize());
		pageArgs.add(pageable.getOffset());

		List<RosterRow> content = jdbcTemplate.query(
				ROSTER_SELECT + where + orderBy(criteria.sort()) + " LIMIT ? OFFSET ?",
				(ResultSet rs, int rowNum) -> mapRow(rs),
				pageArgs.toArray());

		return new PageImpl<>(content, pageable, total == null ? 0 : total);
	}

	@Override
	public int countUnassigned(UUID cohortId, UUID orgId) {
		String sql = """
				SELECT COUNT(*) FROM cohort_member cm
				WHERE cm.cohort_id = ? AND cm.org_id = ?
				  AND NOT EXISTS (
				      SELECT 1 FROM class_membership csm
				      WHERE csm.cohort_member_id = cm.cohort_member_id AND csm.unassigned_at IS NULL
				  )
				""";
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, cohortId, orgId);
		return count == null ? 0 : count;
	}

	/**
	 * {@link #countUnassigned}와 <b>같은 모집단</b>(그 기수의 cohort_member 전체)을 센다.
	 * 두 값이 같은 분모 위에 있어야 화면의 '393명 중 미배정 12'가 성립한다.
	 */
	@Override
	public int countCohortTotal(UUID cohortId, UUID orgId) {
		String sql = """
				SELECT COUNT(*) FROM cohort_member cm
				WHERE cm.cohort_id = ? AND cm.org_id = ?
				""";
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, cohortId, orgId);
		return count == null ? 0 : count;
	}

	@Override
	public Optional<RosterRow> findTrainee(UUID traineeId, UUID cohortId, UUID orgId) {
		String sql = ROSTER_SELECT + " WHERE cm.user_id = ? AND cm.cohort_id = ? AND cm.org_id = ?";
		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> mapRow(rs),
				null, null, traineeId, cohortId, orgId)
				.stream().findFirst();
	}

	/**
	 * {@code JdbcOrganizationOperatorRepository.updateOperatorStatus}와 같은 CHECK 대응 방식이다.
	 * ck_app_user_status_3(INACTIVE면 inactivated_* 세 컬럼이 모두 NOT NULL)을 만족시켜야 하므로
	 * 활성화 방향이든 정지 방향이든 세 컬럼을 한 세트로 채우거나 비운다.
	 */
	@Override
	public int updateStatus(UUID traineeId, String rawStatus, UUID actorUserId, String reasonCode, String reason) {
		boolean inactivating = "INACTIVE".equals(rawStatus);
		String sql = """
				UPDATE app_user
				SET status = ?,
				    inactivated_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
				    inactivated_by = CASE WHEN ? THEN ?::uuid ELSE NULL END,
				    inactivated_reason_code = CASE WHEN ? THEN ? ELSE NULL END,
				    inactivated_reason = CASE WHEN ? THEN ? ELSE NULL END,
				    updated_at = CURRENT_TIMESTAMP,
				    row_version = row_version + 1
				WHERE user_id = ?
				    AND deleted_at IS NULL
				""";
		return jdbcTemplate.update(
				sql,
				rawStatus,
				inactivating,
				inactivating, actorUserId,
				inactivating, reasonCode,
				inactivating, reason,
				traineeId
		);
	}

	/**
	 * COMMENT의 불변식(ACTIVE면 left_at IS NULL, LEFT면 left_at 필수)을 한 문장으로 지킨다.
	 * DB CHECK가 status 값만 보고 left_at과의 정합성은 보지 않아, 여기서 두 컬럼을 한 세트로 움직인다.
	 *
	 * <p>이탈 시각은 {@code left_at > joined_at}이어야 하므로 등록 직후 같은 시각에 이탈 처리되는
	 * 경우까지 대비해 joined_at보다 뒤가 되도록 보정한다.
	 */
	@Override
	public int updateCohortMembership(UUID traineeId, UUID cohortId, UUID orgId, boolean left) {
		String sql = """
				UPDATE cohort_member
				SET status = CASE WHEN ? THEN 'LEFT' ELSE 'ACTIVE' END,
				    left_at = CASE
				                  WHEN ? THEN GREATEST(CURRENT_TIMESTAMP, joined_at + INTERVAL '1 microsecond')
				                  ELSE NULL
				              END
				WHERE user_id = ? AND cohort_id = ? AND org_id = ?
				""";
		return jdbcTemplate.update(sql, left, left, traineeId, cohortId, orgId);
	}

	private String orderBy(TraineeRosterSort sort) {
		if (sort == TraineeRosterSort.RISK) {
			return " ORDER BY COALESCE(mv.risk_sort_key, -1) DESC, u.name ASC, u.email ASC";
		}
		if (sort == TraineeRosterSort.EXCELLENCE) {
			return " ORDER BY COALESCE(mv.excellent_occurrence_count, -1) DESC, u.name ASC, u.email ASC";
		}
		if (sort == TraineeRosterSort.RECENT_ENROLLED) {
			return " ORDER BY cm.joined_at DESC, u.name ASC, u.email ASC";
		}
		return " ORDER BY u.name ASC, u.email ASC";
	}

	private RosterRow mapRow(ResultSet rs) throws SQLException {
		return new RosterRow(
				rs.getObject("trainee_id", UUID.class),
				rs.getString("name"),
				rs.getString("email"),
				rs.getString("account_status"),
				rs.getObject("classroom_id", UUID.class),
				rs.getString("class_name"),
				toOffsetDateTime(rs.getTimestamp("joined_at")),
				toOffsetDateTime(rs.getTimestamp("left_at")),
				rs.getString("inactivated_reason_code"),
				rs.getString("inactivated_reason"),
				toOffsetDateTime(rs.getTimestamp("inactivated_at")),
				rs.getObject("inactivated_by_id", UUID.class),
				rs.getString("inactivated_by_name"),
				rs.getObject("pending_invitation_token_id", UUID.class),
				rs.getObject("assessment_round_id", UUID.class),
				rs.getObject("attempt_id", UUID.class),
				rs.getString("row_result_status"),
				rs.getString("concept_result_items"),
				integer(rs, "low_stage_concept_count"),
				integer(rs, "excellent_occurrence_count"),
				rs.getString("matched_risk_type_codes"),
				rs.getString("row_aggregation_status")
		);
	}

	private Integer integer(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
	}
}
