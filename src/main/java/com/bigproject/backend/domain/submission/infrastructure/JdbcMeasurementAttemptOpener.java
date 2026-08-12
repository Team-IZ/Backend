package com.bigproject.backend.domain.submission.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 제출이 접수되면 팀원마다 개인 응시({@code measurement_attempt})를 연다.
 *
 * <h2>왜 분석이 아니라 제출 시점인가</h2>
 *
 * <p>{@code measurement_attempt.status}가 흐름을 전제한다.
 *
 * <pre>
 * NOT_STARTED → SUBMITTED → ANALYZING → SESSION_READY → ... → COMPLETED
 * </pre>
 *
 * <p>분석 성공 시점에 처음 만들면 앞의 세 상태를 <b>한 번도 거치지 않은</b> 응시가 생긴다.
 * 그러면 두 가지가 깨진다.
 *
 * <ul>
 *   <li>교육생 홈이 "제출함 / 분석 중"을 보여줄 수 없다 — 분석이 끝나기 전까지 행 자체가 없다.</li>
 *   <li><b>분석이 실패하면 응시가 영영 생기지 않는다.</b> 미응시({@code NOT_ATTENDED})와
 *       "분석 실패로 응시 불가"가 구분되지 않아 위험 교육생 산식의 분모 판정이 흐려진다 —
 *       전자는 분모에서 빠지고 후자는 남아야 한다.</li>
 * </ul>
 *
 * <p>그래서 제출 트랜잭션에서 열고, 분석 쪽은 <b>전이만</b> 한다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcMeasurementAttemptOpener {

	private final JdbcTemplate jdbc;

	/**
	 * 팀원마다 INITIAL 응시를 {@code SUBMITTED}로 연다. 이미 있으면 건너뛴다.
	 *
	 * <p>재제출에서도 이 메서드가 다시 불리는데, 그때는 이미 응시가 있으므로 0건이 된다 —
	 * {@code uq_measurement_attempt_initial}이 회차·사용자당 INITIAL 1건을 강제한다.
	 *
	 * <p>진행 중이거나 끝난 응시는 되돌리지 않는다. 마감 전 재제출로 이미 세션을 시작한 팀원의
	 * 응시가 {@code SUBMITTED}로 후퇴하면 풀던 시험이 사라진다.
	 *
	 * @return 새로 연 응시 수
	 */
	public int openForTeam(UUID orgId, UUID teamId, UUID assessmentRoundId, UUID submissionId) {
		int opened = jdbc.update("""
				INSERT INTO measurement_attempt (org_id, cohort_id, assessment_round_id, project_id, user_id,
				    source_submission_id, attempt_type, attempt_sequence_no, status, validity_review_status)
				SELECT ?, c.cohort_id, ?, pm.project_id, pm.user_id, ?, 'INITIAL', 1, 'SUBMITTED', 'NOT_REQUIRED'
				  FROM team_membership tm
				  JOIN project_membership pm ON pm.project_membership_id = tm.project_membership_id
				   AND pm.status = 'ACTIVE'
				  JOIN class c ON c.class_id = pm.class_id
				 WHERE tm.team_id = ? AND tm.to_at IS NULL
				   AND NOT EXISTS (SELECT 1 FROM measurement_attempt a
				                    WHERE a.assessment_round_id = ? AND a.user_id = pm.user_id
				                      AND a.attempt_type = 'INITIAL')
				""", orgId, assessmentRoundId, submissionId, teamId, assessmentRoundId);

		// 재제출이면 어느 제출을 분석했는지가 바뀐다. 아직 시작 전 응시만 새 제출을 가리키게 한다.
		jdbc.update("""
				UPDATE measurement_attempt
				   SET source_submission_id = ?, updated_at = now()
				 WHERE assessment_round_id = ? AND attempt_type = 'INITIAL'
				   AND status IN ('NOT_STARTED', 'SUBMITTED', 'ANALYZING')
				   AND user_id IN (SELECT pm.user_id FROM team_membership tm
				                     JOIN project_membership pm
				                       ON pm.project_membership_id = tm.project_membership_id
				                    WHERE tm.team_id = ? AND tm.to_at IS NULL)
				""", submissionId, assessmentRoundId, teamId);

		return opened;
	}
}
