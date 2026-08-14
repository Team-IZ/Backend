package com.bigproject.backend.domain.intervention.infrastructure;

import com.bigproject.backend.domain.intervention.domain.InterviewCaseLookupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcInterviewCaseLookupRepository implements InterviewCaseLookupRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<CaseSummary> findCase(UUID managerUserId, UUID orgId, UUID caseId) {
		/*
		 * 위험 유형 식은 JdbcInterviewListRepository.RISK_EXPR과 같아야 한다 — 목록의 배지와
		 * 브리프 헤더의 배지가 다르면 매니저가 같은 사람을 두 화면에서 다르게 본다.
		 * 사유 요약도 목록과 같은 규칙으로 첫 건만 쓴다(화면이 한 줄이다).
		 */
		List<CaseSummary> rows = jdbcTemplate.query("""
				SELECT v.candidate_id,
				       v.target_user_id,
				       v.target_user_name,
				       v.class_name,
				       v.interview_id,
				       v.interview_status,
				       v.assessment_round_id,
				       CASE WHEN 'INVALID_ATTEMPT' = ANY(rsn.codes) THEN 'INVALID'
				            WHEN 'NOT_APPLICABLE'  = ANY(rsn.evaluations) THEN 'OBSERVE'
				            WHEN 'PERSISTENT_LOW'  = ANY(rsn.codes) THEN 'LOW_PERSISTENT'
				            WHEN 'STAGE_DECLINE'   = ANY(rsn.codes) THEN 'DECLINE'
				            ELSE 'OBSERVE' END AS risk_type,
				       rsn.summaries[1] AS risk_summary
				FROM manager_interview_list_view v
				LEFT JOIN LATERAL (
				    SELECT ARRAY_AGG(x.reason_code       ORDER BY x.detected_at) AS codes,
				           ARRAY_AGG(x.reason_summary    ORDER BY x.detected_at) AS summaries,
				           ARRAY_AGG(x.evaluation_status ORDER BY x.detected_at) AS evaluations
				    FROM interview_candidate_reason x
				    WHERE x.candidate_id = v.candidate_id
				      AND x.reason_status = 'ACTIVE'
				) rsn ON TRUE
				WHERE v.manager_user_id = ?
				  AND v.org_id          = ?
				  AND v.candidate_id    = ?
				""",
				(rs, rowNum) -> new CaseSummary(
						rs.getObject("candidate_id", UUID.class),
						rs.getObject("target_user_id", UUID.class),
						rs.getString("target_user_name"),
						rs.getString("class_name"),
						rs.getObject("interview_id", UUID.class),
						rs.getString("interview_status"),
						rs.getString("risk_type"),
						rs.getString("risk_summary"),
						rs.getObject("assessment_round_id", UUID.class)),
				managerUserId, orgId, caseId);

		return rows.stream().findFirst();
	}
}
