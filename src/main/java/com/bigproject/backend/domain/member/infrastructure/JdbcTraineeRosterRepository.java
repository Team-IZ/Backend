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

	/**
	 * 실제 소속과 초대 대기를 같은 명단 행으로 정규화한다. cohort_member는 수락 시에만 생기므로
	 * PENDING 계정은 user_invitation.target_cohort_id에서 기수 범위를 얻는다.
	 */
	private static final String ROSTER_CTE = """
			WITH roster AS (
				SELECT cm.cohort_member_id, cm.user_id, cm.cohort_id, cm.org_id,
				       cm.joined_at, cm.left_at, cm.joined_at AS sort_at,
				       u.name, u.email, u.status AS account_status,
				       u.inactivated_reason_code, u.inactivated_reason, u.inactivated_at,
				       u.inactivated_by, NULL::uuid AS pending_invitation_token_id
				FROM cohort_member cm
				JOIN app_user u ON u.user_id = cm.user_id AND u.deleted_at IS NULL

				UNION ALL

				SELECT NULL::uuid, u.user_id, ui.target_cohort_id, ui.org_id,
				       NULL::timestamptz, NULL::timestamptz, ui.invited_at,
				       u.name, u.email, u.status,
				       u.inactivated_reason_code, u.inactivated_reason, u.inactivated_at,
				       u.inactivated_by,
				       CASE WHEN EXISTS (
				           SELECT 1 FROM cohort invitation_cohort
				           WHERE invitation_cohort.cohort_id = ui.target_cohort_id
				             AND invitation_cohort.org_id = ui.org_id
				             AND invitation_cohort.status <> 'CLOSED'
				             AND invitation_cohort.deleted_at IS NULL
				       ) THEN ui.current_token_id END
				FROM user_invitation ui
				JOIN app_user u
				  ON u.normalized_email = ui.target_email_normalized
				 AND u.org_id = ui.org_id
				 AND u.status = 'PENDING'
				 AND u.deleted_at IS NULL
				JOIN "role" role ON role.role_id = u.role_id AND role.code = 'TRAINEE'
				WHERE ui.target_role_code = 'TRAINEE'
				  AND ui.status IN ('PENDING', 'SENT', 'DELIVERY_FAILED', 'EXPIRED')
				  AND NOT EXISTS (
				      SELECT 1 FROM cohort_member cm
				      WHERE cm.cohort_id = ui.target_cohort_id AND cm.user_id = u.user_id
				  )
			)
			""";

	private static final String ROSTER_FROM = """
			FROM roster r
			LEFT JOIN class_membership csm ON csm.cohort_member_id = r.cohort_member_id AND csm.unassigned_at IS NULL
			LEFT JOIN class c ON c.class_id = csm.class_id
			LEFT JOIN app_user actor ON actor.user_id = r.inactivated_by
			""";

	private static final String ROSTER_SELECT = ROSTER_CTE + """
			SELECT r.user_id AS trainee_id, r.name, r.email, r.account_status,
			       c.class_id AS classroom_id, c.name AS class_name,
			       r.joined_at, r.left_at,
			       r.inactivated_reason_code, r.inactivated_reason, r.inactivated_at,
			       r.inactivated_by AS inactivated_by_id, actor.name AS inactivated_by_name,
			       r.pending_invitation_token_id
			""" + ROSTER_FROM;

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
		StringBuilder where = new StringBuilder(" WHERE r.cohort_id = ? AND r.org_id = ?");
		List<Object> args = new ArrayList<>(List.of(criteria.cohortId(), criteria.orgId()));

		if (criteria.classroomId() != null) {
			where.append(" AND c.class_id = ?");
			args.add(criteria.classroomId());
		}
		if (criteria.unassignedOnly()) {
			where.append(" AND csm.class_id IS NULL");
		}
		if (criteria.rawAccountStatus() != null) {
			where.append(" AND r.account_status = ?");
			args.add(criteria.rawAccountStatus());
		}
		if (criteria.query() != null && !criteria.query().isBlank()) {
			where.append(" AND (r.name ILIKE ? OR r.email ILIKE ?)");
			String likeQuery = "%" + criteria.query().trim() + "%";
			args.add(likeQuery);
			args.add(likeQuery);
		}

		Long total = jdbcTemplate.queryForObject(
				ROSTER_CTE + " SELECT COUNT(*) " + ROSTER_FROM
						+ where,
				Long.class, args.toArray());

		List<Object> pageArgs = new ArrayList<>(args);
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
		String sql = ROSTER_CTE + """
				SELECT COUNT(*) FROM roster r
				WHERE r.cohort_id = ? AND r.org_id = ?
				  AND NOT EXISTS (
				      SELECT 1 FROM class_membership csm
				      WHERE csm.cohort_member_id = r.cohort_member_id AND csm.unassigned_at IS NULL
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
		String sql = ROSTER_CTE + """
				SELECT COUNT(*) FROM roster r
				WHERE r.cohort_id = ? AND r.org_id = ?
				""";
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, cohortId, orgId);
		return count == null ? 0 : count;
	}

	@Override
	public Optional<RosterRow> findTrainee(UUID traineeId, UUID cohortId, UUID orgId) {
		String sql = ROSTER_SELECT + " WHERE r.user_id = ? AND r.cohort_id = ? AND r.org_id = ?";
		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> mapRow(rs), traineeId, cohortId, orgId)
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
		if (sort == TraineeRosterSort.RECENT_ENROLLED) {
			return " ORDER BY r.sort_at DESC, r.name ASC, r.email ASC";
		}
		return " ORDER BY r.name ASC, r.email ASC";
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
				rs.getObject("pending_invitation_token_id", UUID.class)
		);
	}

	private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
	}
}
