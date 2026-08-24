package com.bigproject.backend.domain.intervention.infrastructure;

import com.bigproject.backend.domain.intervention.domain.InterviewRoundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcInterviewRoundRepository implements InterviewRoundRepository {

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 담당 반 → 기수 → 그 기수의 회차 전부.
	 *
	 * <p>{@code DISTINCT}가 필요한 이유: 매니저가 같은 기수의 반을 여럿 맡으면
	 * {@code manager_assignment}가 반 수만큼 행을 곱한다 — 회차가 중복해서 나온다.
	 */
	@Override
	public List<RoundOption> findRoundOptions(UUID managerUserId, UUID orgId, UUID cohortId) {
		return jdbcTemplate.query("""
				SELECT DISTINCT
				       r.assessment_round_id,
				       r.project_id,
				       r.cohort_id,
				       p.name        AS project_name,
				       r.round_no,
				       r.round_name,
				       r.status,
				       p.sequence_no
				FROM manager_assignment ma
				JOIN class c
				       ON c.class_id = ma.class_id
				JOIN project_assessment_round r
				       ON r.cohort_id  = c.cohort_id
				      AND r.deleted_at IS NULL
				JOIN project p
				       ON p.project_id  = r.project_id
				      AND p.deleted_at IS NULL
				WHERE ma.manager_user_id = ?
				  AND ma.org_id          = ?
				  AND ma.status          = 'ACTIVE'
				  AND ma.unassigned_at IS NULL
				  AND (?::uuid IS NULL OR r.cohort_id = ?::uuid)
				ORDER BY p.sequence_no, r.round_no
				""",
				(rs, rowNum) -> new RoundOption(
						rs.getObject("assessment_round_id", UUID.class),
						rs.getObject("project_id", UUID.class),
						rs.getObject("cohort_id", UUID.class),
						rs.getString("project_name"),
						rs.getInt("round_no"),
						rs.getString("round_name"),
						label(rs.getString("project_name"), rs.getString("round_name")),
						rs.getString("status")),
				managerUserId, orgId, cohortId, cohortId);
		// 정렬은 SQL의 p.sequence_no, r.round_no가 한다 — round_no는 프로젝트 안에서만
		// 유일하므로 sequence_no가 앞에 와야 기수 전체에서 순서가 맞는다.
	}

	/**
	 * 회차 메타 + 리포트 발행 시각.
	 *
	 * <p>발행 시각을 {@code report}에서 읽는 이유: 면담 계열 테이블에는 리포트 참조가 하나도
	 * 없다(§08_INTV 전체에 report 컬럼 0건). 리포트는 브리프 내용을 주지 않고 <b>"이 회차 채점이
	 * 끝났다"는 시점만</b> 준다 — 화면의 "7/21 리포트 발행과 함께 등재"가 이 값이다.
	 *
	 * <p>{@code MAX}로 접는 이유: 회차 리포트는 교육생마다 한 건씩이라 회차 하나에 여러 행이
	 * 있다. 일괄 발행이라 값이 모두 같지만, 개별 재발행이 섞이면 가장 최근 것이 그 회차의
	 * 발행 시각으로 보이는 편이 화면 문구("N일째 안 끝났습니다")와 맞는다.
	 *
	 * <h2>32차 R1 — 판정 시각을 함께 읽는다</h2>
	 *
	 * <p>{@code resultStatus}가 <b>발행이 아니라 판정</b>을 봐야 해서 {@code outcome_judged_at}을
	 * 함께 가져온다({@link RoundMeta#resultStatus()}의 근거 참고). 두 시각은 독립이라 한쪽으로
	 * 다른 쪽을 유추할 수 없다.
	 *
	 * <p>이쪽은 {@code MIN}이다. 판정은 회차 단위 배치가 한 트랜잭션으로 찍으므로 값이 사실상
	 * 같지만, 무효 확인이 늦게 끝난 수행이 뒤늦게 판정되면 행마다 시각이 갈린다. 화면에 필요한
	 * 것은 "이 회차 판정이 <b>시작된</b> 시점"이라 가장 이른 값이 맞다 — {@code MAX}로 잡으면
	 * 늦게 붙은 한 건 때문에 회차 전체가 방금 판정된 것처럼 보인다.
	 *
	 * <p>{@code RETRY}는 제외한다. 재시험은 판정 뒤에 열리는 것이라 판정 시작 시각을 뒤로 민다.
	 */
	@Override
	public Optional<RoundMeta> findRoundMeta(UUID managerUserId, UUID orgId, UUID assessmentRoundId) {
		List<RoundMeta> rows = jdbcTemplate.query("""
				SELECT r.assessment_round_id,
				       r.round_no,
				       p.sequence_no,
				       p.name AS project_name,
				       r.round_name,
				       rpt.published_at,
				       judged.outcome_judged_at
				FROM project_assessment_round r
				JOIN project p
				       ON p.project_id = r.project_id
				JOIN class c
				       ON c.cohort_id = r.cohort_id
				JOIN manager_assignment ma
				       ON ma.class_id        = c.class_id
				      AND ma.manager_user_id = ?
				      AND ma.org_id          = ?
				      AND ma.status          = 'ACTIVE'
				      AND ma.unassigned_at IS NULL
				LEFT JOIN LATERAL (
				    SELECT MAX(x.published_at) AS published_at
				    FROM report x
				    WHERE x.assessment_round_id = r.assessment_round_id
				      AND x.report_type         = 'CHECKPOINT'
				      AND x.lifecycle_status    = 'ACTIVE'
				) rpt ON TRUE
				LEFT JOIN LATERAL (
				    SELECT MIN(x.outcome_judged_at) AS outcome_judged_at
				    FROM measurement_attempt x
				    WHERE x.assessment_round_id = r.assessment_round_id
				      AND x.attempt_type        = 'INITIAL'
				      AND x.outcome_judged_at IS NOT NULL
				) judged ON TRUE
				WHERE r.assessment_round_id = ?
				  AND r.deleted_at IS NULL
				LIMIT 1
				""", this::mapMeta, managerUserId, orgId, assessmentRoundId);

		return rows.stream().findFirst();
	}

	private RoundMeta mapMeta(ResultSet rs, int rowNum) throws SQLException {
		Timestamp publishedAt = rs.getTimestamp("published_at");
		Timestamp outcomeJudgedAt = rs.getTimestamp("outcome_judged_at");
		return new RoundMeta(
				rs.getObject("assessment_round_id", UUID.class),
				rs.getInt("round_no"),
				label(rs.getString("project_name"), rs.getString("round_name")),
				// 🔴 round_no로 판정하면 안 된다 — 미니프로젝트는 활성 회차가 1건뿐이라
				// round_no가 항상 1이고, 그러면 모든 회차가 "1차"가 되어 화면에
				// "1차는 위험 유형이 붙지 않습니다" 배너가 영영 뜬다.
				// 화면의 1차·2차는 기수 안의 프로젝트 순서다.
				rs.getInt("sequence_no") == 1,
				publishedAt == null ? null : publishedAt.toInstant(),
				outcomeJudgedAt == null ? null : outcomeJudgedAt.toInstant());
	}

	/**
	 * 드롭다운 문구.
	 *
	 * <p>{@code round_name}이 이미 프로젝트명을 포함하는 경우가 있어(실측: 프로젝트
	 * "미니프로젝트 2차" + 회차 "미니프로젝트 2차 이해도 확인") 그대로 붙이면
	 * "미니프로젝트 2차 미니프로젝트 2차 이해도 확인"이 된다. 포함돼 있으면 회차명만 쓴다.
	 *
	 * <p>포함돼 있지 않을 때만 프로젝트명을 앞에 붙이는 이유는 {@code round_no}가
	 * 프로젝트 안에서만 유일해서다 — 안 붙이면 서로 다른 프로젝트의 회차가 구분되지 않는다.
	 */
	private static String label(String projectName, String roundName) {
		if (roundName == null || roundName.isBlank()) {
			return projectName;
		}
		if (projectName == null || projectName.isBlank() || roundName.contains(projectName)) {
			return roundName;
		}
		return projectName + " " + roundName;
	}
}
