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
				       u.inactivated_by, NULL::uuid AS pending_invitation_token_id,
				       /*
				        * 매니저 스코프용 반. 수락한 교육생의 반은 class_membership에서 오므로
				        * 여기서는 비우고, 초대 대기 행만 target_class_id를 싣는다 --
				        * 초대는 아직 cohort_member가 없어 class_membership으로 이어지지 않는다.
				        */
				       NULL::uuid AS invited_class_id
				FROM cohort_member cm
				/*
				 * 중도 이탈 처리는 app_user.deleted_at까지 함께 찍는다. 그래서 deleted_at만 보고 거르면
				 * 이탈자가 명단에서 통째로 사라지고, 이탈 이전 회차의 결과까지 볼 수 없게 된다.
				 * 이탈(cohort_member.status='LEFT')로 삭제된 계정만 되살리고, 그 밖의 사유로
				 * 삭제된 계정은 그대로 제외한다.
				 */
				JOIN app_user u ON u.user_id = cm.user_id
				 AND (u.deleted_at IS NULL OR cm.status = 'LEFT')

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
				       ) THEN ui.current_token_id END,
				       ui.target_class_id
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

	/**
	 * 이탈자는 반 배정도 함께 해제되므로 {@code unassigned_at IS NULL}만 보면 반이 사라지고,
	 * 담당 반 스코프({@link #MANAGER_SCOPE_CONDITION})에서도 빠져 매니저가 이탈자를 볼 수 없다.
	 * 이탈자에 한해 <b>해제된 마지막 배정</b>까지 후보로 둔다 — 재직 중인 교육생의 판정은 그대로다.
	 */
	private static final String ROSTER_FROM = """
			FROM roster r
			LEFT JOIN LATERAL (
			    SELECT x.class_id
			    FROM class_membership x
			    WHERE x.cohort_member_id = r.cohort_member_id
			      AND (x.unassigned_at IS NULL OR r.left_at IS NOT NULL)
			    ORDER BY x.unassigned_at DESC NULLS FIRST
			    LIMIT 1
			) csm ON TRUE
			LEFT JOIN class c ON c.class_id = csm.class_id
			LEFT JOIN app_user actor ON actor.user_id = r.inactivated_by
			""";

	/**
	 * 매니저가 부를 때만 붙는 담당 반 조건이다. 오퍼레이터는 기수 전체를 보므로 붙이지 않는다.
	 *
	 * <p>수락한 교육생은 현재 반 배정(class_membership)으로, 아직 수락하지 않은 초대는
	 * {@code user_invitation.target_class_id}로 판정한다 -- 둘 중 하나만 값이 있다.
	 * 초대 대기자를 빼면 화면 상단의 `초대 대기 N`이 명단과 어긋난다.
	 */
	private static final String MANAGER_SCOPE_CONDITION = """
			 AND EXISTS (
			     SELECT 1 FROM manager_assignment ma
			     WHERE ma.manager_user_id = ?
			       AND ma.status = 'ACTIVE' AND ma.unassigned_at IS NULL
			       AND ma.class_id = COALESCE(csm.class_id, r.invited_class_id)
			 )
			""";

	/**
	 * 위험·우수 지표는 매니저·회차 단위 집계라 명단 본문에서만 붙인다. 집계 없이 세기만 하는
	 * COUNT 질의({@link #ROSTER_FROM})에 함께 두면 쓰지도 않을 바인딩 파라미터 두 개를 요구한다.
	 *
	 * <p>초대 대기 행은 응시 이력이 없어 조인 결과가 모두 NULL이다.
	 */
	private static final String ROSTER_MANAGER_METRICS_JOIN = """
			LEFT JOIN manager_trainee_roster_view mv
			  ON mv.user_id = r.user_id AND mv.manager_user_id = ?::uuid
			 AND mv.assessment_round_id = ?::uuid
			""";

	private static final String ROSTER_SELECT = ROSTER_CTE + """
			SELECT r.user_id AS trainee_id, r.name, r.email, r.account_status,
			       c.class_id AS classroom_id, c.name AS class_name,
			       r.joined_at, r.left_at,
			       r.inactivated_reason_code, r.inactivated_reason, r.inactivated_at,
			       r.inactivated_by AS inactivated_by_id, actor.name AS inactivated_by_name,
			       r.pending_invitation_token_id,
			       mv.assessment_round_id, mv.attempt_id, mv.row_result_status,
			       mv.concept_result_items::text AS concept_result_items,
			       mv.expected_concept_count,
			       mv.low_stage_concept_count, mv.excellent_occurrence_count,
			       mv.excellent_assessment_sequence_nos,
			       mv.current_round_matched_risk_type_codes::text AS matched_risk_type_codes,
			       mv.current_round_primary_status_code,
			       mv.round_terminal_at,
			       mv.row_aggregation_status
			""" + ROSTER_FROM + ROSTER_MANAGER_METRICS_JOIN;

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
		// 담당 반 조건은 맨 뒤에 붙인다. 앞의 필터들과 순서가 섞이면 바인딩이 어긋난다.
		if (criteria.scopedManagerId() != null) {
			where.append(MANAGER_SCOPE_CONDITION);
			args.add(criteria.scopedManagerId());
		}

		Long total = jdbcTemplate.queryForObject(
				ROSTER_CTE + " SELECT COUNT(*) " + ROSTER_FROM
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

	/**
	 * 반 배정이 없는 교육생 수이며 오퍼레이터 화면(OP-06)의 `미배정 N` 배지다.
	 *
	 * <p><b>매니저에게는 항상 0이다.</b> 매니저 명단은 담당 반으로 좁혀져 있어 반이 없는 교육생은
	 * 애초에 목록에 들어오지 않는다 -- 배정되지 않은 사람은 어느 매니저의 담당도 아니다.
	 * 매니저 화면(MG-05)에도 이 배지가 없다.
	 */
	@Override
	public int countUnassigned(UUID cohortId, UUID orgId, UUID scopedManagerId) {
		if (scopedManagerId != null) {
			return 0;
		}
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
	 * 화면 상단의 총원이며 <b>목록과 같은 모집단</b>이어야 한다.
	 * 오퍼레이터는 기수 전체, 매니저는 담당 반 전체다 -- 매니저 화면에 `담당 반 21명`이라 떠 있는데
	 * 총원이 `393명`이면 두 숫자가 서로 다른 것을 세게 된다.
	 *
	 * <p>필터(검색·계정 상태 등)는 적용하지 않는다. `조건에 맞는 교육생이 없습니다`일 때도
	 * 모집단 수를 보여줘야 하기 때문이다.
	 */
	@Override
	public int countCohortTotal(UUID cohortId, UUID orgId, UUID scopedManagerId) {
		StringBuilder sql = new StringBuilder(ROSTER_CTE + " SELECT COUNT(*) " + ROSTER_FROM
				+ " WHERE r.cohort_id = ? AND r.org_id = ?");
		List<Object> args = new ArrayList<>(List.of(cohortId, orgId));
		if (scopedManagerId != null) {
			sql.append(MANAGER_SCOPE_CONDITION);
			args.add(scopedManagerId);
		}
		Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, args.toArray());
		return count == null ? 0 : count;
	}

	@Override
	public Optional<RosterRow> findTrainee(UUID traineeId, UUID cohortId, UUID orgId) {
		String sql = ROSTER_SELECT + " WHERE r.user_id = ? AND r.cohort_id = ? AND r.org_id = ?";
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
			return " ORDER BY COALESCE(mv.risk_sort_key, -1) DESC, r.name ASC, r.email ASC";
		}
		if (sort == TraineeRosterSort.EXCELLENCE) {
			return " ORDER BY COALESCE(mv.excellent_occurrence_count, -1) DESC, r.name ASC, r.email ASC";
		}
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
				rs.getObject("pending_invitation_token_id", UUID.class),
				rs.getObject("assessment_round_id", UUID.class),
				rs.getObject("attempt_id", UUID.class),
				rs.getString("row_result_status"),
				rs.getString("concept_result_items"),
				integer(rs, "expected_concept_count"),
				integer(rs, "low_stage_concept_count"),
				integer(rs, "excellent_occurrence_count"),
				intArray(rs, "excellent_assessment_sequence_nos"),
				rs.getString("matched_risk_type_codes"),
				rs.getString("current_round_primary_status_code"),
				toOffsetDateTime(rs.getTimestamp("round_terminal_at")),
				rs.getString("row_aggregation_status")
		);
	}

	private Integer integer(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	/**
	 * 매니저·회차 지표가 붙지 않은 행(조회 시 assessmentRoundId를 안 넘겼거나 오퍼레이터 조회)은
	 * 배열 컬럼 자체가 SQL null이다. 화면이 매번 null 검사를 하지 않도록 빈 배열로 통일한다.
	 */
	private int[] intArray(ResultSet rs, String column) throws SQLException {
		java.sql.Array array = rs.getArray(column);
		if (array == null) {
			return new int[0];
		}
		Integer[] boxed = (Integer[]) array.getArray();
		int[] result = new int[boxed.length];
		for (int i = 0; i < boxed.length; i++) {
			result[i] = boxed[i];
		}
		return result;
	}

	private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
	}
}
