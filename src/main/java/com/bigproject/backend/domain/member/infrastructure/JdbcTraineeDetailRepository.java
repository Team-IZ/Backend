package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.TraineeDetailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcTraineeDetailRepository implements TraineeDetailRepository {
	private final JdbcTemplate jdbcTemplate;

	/**
	 * 회차 이름·프로젝트 이름은 뷰에 없어 원장에서 붙인다. 뷰는 차수({@code analysis_sequence_no})만
	 * 내는데 화면은 `미프 3차`와 회차 이름을 함께 그린다.
	 *
	 * <p>정렬은 {@code analysis_sequence_no}(= project.sequence_no)다. {@code round_no}는
	 * 프로젝트 안의 순번이라 미니프로젝트에서 전부 1이 되어 차수를 구분하지 못한다.
	 */
	private static final String SQL = """
			SELECT mv.user_id AS trainee_id, mv.name, mv.email,
			       mv.cohort_id, co.name AS cohort_name,
			       mv.account_display_status, mv.current_class_id, cl.name AS class_name,
			       u.inactivated_reason_code, u.inactivated_reason, u.inactivated_at,
			       mv.assessment_round_id, mv.analysis_sequence_no, r.round_no,
			       r.round_name, p.project_id, p.name AS project_name,
			       mv.attempt_id, mv.row_result_status,
			       mv.current_round_primary_status_code,
			       mv.current_round_not_attended_reason_code,
			       mv.current_round_matched_risk_type_codes,
			       mv.risk_reason_summary, mv.round_terminal_at,
			       mv.concept_result_items::text AS concept_result_items,
			       mv.expected_concept_count, mv.low_stage_concept_count,
			       mv.excellent_occurrence_count, mv.excellent_assessment_sequence_nos,
			       mv.team_id_at_round, mv.row_aggregation_status
			FROM manager_trainee_roster_view mv
			-- 비활성 사유·일자는 뷰에 없다. 헤더가 `계정 비활성 · 이탈 08.02`를 그리려면 원장이 필요하다.
			JOIN app_user u ON u.user_id = mv.user_id
			-- 헤더의 `7기 · C반`에서 앞부분이다. 뷰는 기수 ID만 낸다.
			JOIN cohort co ON co.cohort_id = mv.cohort_id
			LEFT JOIN class cl ON cl.class_id = mv.current_class_id
			LEFT JOIN project_assessment_round r
			  ON r.assessment_round_id = mv.assessment_round_id AND r.deleted_at IS NULL
			LEFT JOIN project p ON p.project_id = r.project_id
			WHERE mv.manager_user_id = ? AND mv.cohort_id = ? AND mv.user_id = ?
			ORDER BY mv.analysis_sequence_no, r.round_no, mv.assessment_round_id
			""";

	@Override
	public List<DetailRow> findRounds(UUID managerId, UUID cohortId, UUID traineeId) {
		return jdbcTemplate.query(SQL, this::mapRow, managerId, cohortId, traineeId);
	}

	private DetailRow mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new DetailRow(
				rs.getObject("trainee_id", UUID.class),
				rs.getString("name"),
				rs.getString("email"),
				rs.getObject("cohort_id", UUID.class),
				rs.getString("cohort_name"),
				rs.getString("account_display_status"),
				rs.getObject("current_class_id", UUID.class),
				rs.getString("class_name"),
				rs.getString("inactivated_reason_code"),
				rs.getString("inactivated_reason"),
				time(rs.getTimestamp("inactivated_at")),
				rs.getObject("assessment_round_id", UUID.class),
				integer(rs, "analysis_sequence_no"),
				integer(rs, "round_no"),
				rs.getString("round_name"),
				rs.getObject("project_id", UUID.class),
				rs.getString("project_name"),
				rs.getObject("attempt_id", UUID.class),
				rs.getString("row_result_status"),
				rs.getString("current_round_primary_status_code"),
				rs.getString("current_round_not_attended_reason_code"),
				stringList(rs, "current_round_matched_risk_type_codes"),
				rs.getString("risk_reason_summary"),
				time(rs.getTimestamp("round_terminal_at")),
				rs.getString("concept_result_items"),
				integer(rs, "expected_concept_count"),
				integer(rs, "low_stage_concept_count"),
				integer(rs, "excellent_occurrence_count"),
				intArray(rs, "excellent_assessment_sequence_nos"),
				rs.getObject("team_id_at_round", UUID.class),
				rs.getString("row_aggregation_status"));
	}

	private Integer integer(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	/**
	 * 위험 유형 배열이다. 뷰가 {@code COALESCE(..., ARRAY[])}로 내므로 SQL null은 회차 자체가
	 * 없는 행에서만 나오며, 그때도 화면이 매번 null 검사를 하지 않도록 빈 목록으로 통일한다.
	 */
	private List<String> stringList(ResultSet rs, String column) throws SQLException {
		java.sql.Array array = rs.getArray(column);
		if (array == null) {
			return List.of();
		}
		return List.of((String[]) array.getArray());
	}

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

	private OffsetDateTime time(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
	}
}
