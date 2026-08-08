package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.domain.ManagedReportQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 매니저 담당 반 리포트 목록 SQL.
 *
 * <p>조인 경로가 {@code ReportDisclosureRepository.isManagedBy}의 {@code EXISTS} 절과 같다 —
 * 그쪽은 리포트 1건이 담당인지 묻고 여기는 담당인 것을 전부 세운다. 두 SQL이 어긋나면
 * "목록에 보이는데 수정하면 404"가 된다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcManagedReportQueryRepository implements ManagedReportQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public List<ManagedReportRow> findManagedReports(
			UUID managerUserId, UUID orgId, UUID cohortId, UUID assessmentRoundId, UUID classId) {

		/*
		 * 개인 리포트만 대상이다. 기수 단위 리포트(COHORT_*)는 report.user_id가 NULL이라
		 * cohort_member 조인에서 자연히 걸러진다 — report_type을 따로 검사할 필요가 없다.
		 *
		 * SUPERSEDED는 제외한다. 재생성으로 대체된 이력이라 공개 범위를 정할 대상이 아니고,
		 * 남겨 두면 같은 회차가 목록에 두 번 보인다. DRAFT는 남긴다 — 생성 중인 리포트가
		 * 목록에서 사라지면 매니저는 "아직 안 만들어졌다"와 "실패했다"를 구분할 수 없다.
		 */
		StringBuilder sql = new StringBuilder("""
				SELECT r.report_id,
				       r.assessment_round_id,
				       par.round_name,
				       par.round_no,
				       r.user_id                     AS trainee_user_id,
				       au.name                       AS trainee_name,
				       clm.class_id,
				       cl.name                       AS class_name,
				       r.cohort_id,
				       r.lifecycle_status,
				       r.published_at,
				       r.trainee_release_status,
				       r.trainee_disclosure_scope,
				       r.trainee_released_at
				FROM report r
				JOIN cohort_member cm
				       ON cm.cohort_id = r.cohort_id
				      AND cm.user_id   = r.user_id
				      AND cm.org_id    = r.org_id
				      AND cm.left_at IS NULL
				JOIN class_membership clm
				       ON clm.cohort_member_id = cm.cohort_member_id
				      AND clm.org_id           = r.org_id
				      AND clm.unassigned_at IS NULL
				JOIN manager_assignment ma
				       ON ma.class_id        = clm.class_id
				      AND ma.org_id          = r.org_id
				      AND ma.manager_user_id = ?
				      AND ma.unassigned_at IS NULL
				JOIN class cl
				       ON cl.class_id = clm.class_id
				JOIN app_user au
				       ON au.user_id = r.user_id
				LEFT JOIN project_assessment_round par
				       ON par.assessment_round_id = r.assessment_round_id
				WHERE r.org_id = ?
				  AND r.lifecycle_status <> 'SUPERSEDED'
				""");

		List<Object> args = new ArrayList<>();
		args.add(managerUserId);
		args.add(orgId);

		/*
		 * 선택 필터는 절을 붙일 때만 파라미터를 넣는다. `(? IS NULL OR col = ?)` 형태로 두면
		 * PostgreSQL이 NULL 파라미터의 타입을 정하지 못해("could not determine data type")
		 * 필터를 안 쓴 호출이 통째로 실패한다.
		 */
		if (cohortId != null) {
			sql.append("  AND r.cohort_id = ?\n");
			args.add(cohortId);
		}
		if (assessmentRoundId != null) {
			sql.append("  AND r.assessment_round_id = ?\n");
			args.add(assessmentRoundId);
		}
		if (classId != null) {
			sql.append("  AND clm.class_id = ?\n");
			args.add(classId);
		}

		// 화면이 회차 → 반 → 이름 순으로 읽는다. 최신 회차가 위다(TR-04 레일과 같은 방향).
		sql.append("ORDER BY par.round_no DESC NULLS LAST, cl.name, au.name\n");

		return jdbcTemplate.query(sql.toString(), (ResultSet rs, int rowNum) -> new ManagedReportRow(
				rs.getObject("report_id", UUID.class),
				rs.getObject("assessment_round_id", UUID.class),
				rs.getString("round_name"),
				rs.getInt("round_no"),
				rs.getObject("trainee_user_id", UUID.class),
				rs.getString("trainee_name"),
				rs.getObject("class_id", UUID.class),
				rs.getString("class_name"),
				rs.getObject("cohort_id", UUID.class),
				rs.getString("lifecycle_status"),
				instant(rs, "published_at"),
				rs.getString("trainee_release_status"),
				rs.getString("trainee_disclosure_scope"),
				instant(rs, "trainee_released_at")
		), args.toArray());
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}
}
