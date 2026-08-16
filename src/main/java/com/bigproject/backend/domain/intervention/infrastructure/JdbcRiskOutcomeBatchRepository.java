package com.bigproject.backend.domain.intervention.infrastructure;

import com.bigproject.backend.domain.intervention.domain.RiskOutcomeBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 위험·우수 유형 판정 정책을 SQL로 옮긴 구현이다.
 *
 * <h2>왜 전부 SQL인가</h2>
 *
 * <p>판정 하나에 필요한 것이 문제별 도달 단계, 회차 평균, <b>이전 회차들과의 비교</b>, 기수 평균과
 * 백분위다. 이걸 자바로 가져오면 교육생 한 명당 이력 전체를 왕복해야 한다. 창 함수 하나로 끝나는
 * 계산이라 DB에 두고, 자바는 실행 순서와 로그만 맡는다.
 *
 * <h2>용어 (정책 §0)</h2>
 *
 * <ul>
 *   <li><b>도달 단계</b> 0~4. 그 문제에서 <b>마지막으로 통과한</b> 단계. 하나도 통과 못 하면 0단.
 *       {@code problem_stage.axis_code}(L1~L4) 중 {@code status='PASSED'}의 최대값이다.
 *   <li><b>회차 평균 도달 단계</b> 채점된 문제들의 도달 단계 평균. "평균 점수"가 아니다 —
 *       질문 점수(0~5)와는 다른 축이다.
 *   <li><b>게이트</b> 도달 단계가 <b>2단 미만</b>(0단 또는 1단)인 문제가 1개 이상. 2단은 게이트 밖이다.
 *   <li><b>유효 회차</b> 미응시·중단·무효 확정을 뺀 INITIAL 수행.
 * </ul>
 *
 * <p>답을 하나도 하지 않은 문제는 분자에서 뺀다. '못 푼' 것이 아니라 '안 한' 것이라 미응시 쪽
 * 사정이고, 이 기준은 {@code manager_trainee_roster_view.low_stage_concept_count}와 같다. 두 화면의
 * 숫자가 갈리면 안 된다.
 *
 * <h2>확정되지 않은 값</h2>
 *
 * <p>{@link #CONTRIBUTION_THRESHOLD}는 정책 §9-2가 "확정 필요"로 남긴 값이다. 문서가 적은 10%를
 * 그대로 쓰되 상수로 뽑아 뒀다. 저기여와 괴리는 이 값을 경계로 공유해 서로 겹치지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcRiskOutcomeBatchRepository implements RiskOutcomeBatchRepository {

	/**
	 * 저기여 임계값. 기여 비율이 이 값 미만이면 LOW_PARTICIPATION, 이상이면서 게이트에 걸리면
	 * CONTRIBUTION_UNDERSTANDING_GAP이다.
	 *
	 * <p>정책 §9-2가 미확정으로 남긴 값이다. 문서는 "현재 코드에 10%가 들어가 있다"고 적었으나 이
	 * 백엔드에는 {@code contribution_ratio}를 읽는 코드가 없었다 — 즉 근거는 문서뿐이다. 확정되면
	 * 여기만 고친다.
	 */
	private static final String CONTRIBUTION_THRESHOLD = "0.10";

	/**
	 * 유효 회차 조건. 미집계 3종(미응시·중단·무효 확정)을 뺀다.
	 *
	 * <p>{@code JdbcRiskTraineeQueryRepository.ELIGIBLE_CONDITION}과 같은 기준이다. 위험 비율과
	 * 판정이 서로 다른 모수를 쓰면 같은 학생이 두 화면에서 다르게 보인다.
	 */
	private static final String VALID_ROUND_CONDITION = """
			ma.terminal_reason_code IS DISTINCT FROM 'NOT_ATTENDED'
			AND ma.terminal_reason_code IS DISTINCT FROM 'SESSION_INCOMPLETE'
			""";

	/**
	 * 회차 1건 판정.
	 *
	 * <p>파라미터는 (회차, 정책버전, 정책버전) 순이다.
	 *
	 * <p>대상에서 {@code validity_review_status='PENDING'}을 뺀다. 무효 확인이 진행 중이면 결과가
	 * 뒤집힐 수 있고, 판정은 {@code outcome_judged_at}으로 1회성이라 뒤집힌 뒤 다시 못 판정한다.
	 * 확정되면 다음 실행에서 잡힌다.
	 *
	 * <p>기수 평균·백분위의 모수는 <b>판정 대상이 아니라 회차 전체</b>({@code round_pool})다. 대상만
	 * 쓰면 재실행할 때마다 남은 인원으로 평균이 흔들린다.
	 */
	private static final String JUDGE_SQL = """
			WITH round_pool AS (
			    SELECT ma.attempt_id, ma.org_id, ma.cohort_id, ma.user_id, ma.project_id,
			           ma.assessment_round_id, ma.code_analysis_id, ma.source_submission_id,
			           ma.validity_review_status, ma.outcome_judged_at,
			           p.sequence_no AS project_sequence_no, r.round_no
			      FROM measurement_attempt ma
			      JOIN project_assessment_round r ON r.assessment_round_id = ma.assessment_round_id
			      JOIN project p ON p.project_id = r.project_id
			     WHERE ma.assessment_round_id = ?
			       AND ma.attempt_type = 'INITIAL'
			       AND ma.status = 'COMPLETED'
			       AND p.project_category = 'MINI_PROJECT'
			       AND %1$s
			),
			prior_pool AS (
			    SELECT ma.attempt_id, ma.cohort_id, ma.user_id, ma.code_analysis_id,
			           p.sequence_no AS project_sequence_no, r.round_no
			      FROM round_pool rp
			      JOIN measurement_attempt ma ON ma.user_id = rp.user_id AND ma.cohort_id = rp.cohort_id
			      JOIN project_assessment_round r ON r.assessment_round_id = ma.assessment_round_id
			      JOIN project p ON p.project_id = r.project_id
			     WHERE ma.attempt_type = 'INITIAL'
			       AND ma.status = 'COMPLETED'
			       AND p.project_category = 'MINI_PROJECT'
			       AND %1$s
			       AND ma.validity_review_status <> 'CONFIRMED_INVALID'
			       AND (p.sequence_no, r.round_no) < (rp.project_sequence_no, rp.round_no)
			),
			scope AS (
			    SELECT attempt_id, cohort_id, user_id, code_analysis_id, project_sequence_no, round_no
			      FROM round_pool
			    UNION
			    SELECT attempt_id, cohort_id, user_id, code_analysis_id, project_sequence_no, round_no
			      FROM prior_pool
			),
			problem AS (
			    SELECT sc.attempt_id, ap.problem_no,
			           COUNT(*) FILTER (WHERE ps.status IN ('PASSED','NOT_PASSED'))::int AS answered_axis_count,
			           COALESCE(MAX(SUBSTRING(ps.axis_code FROM 2)::int)
			                    FILTER (WHERE ps.status='PASSED'), 0)::int AS reach_level
			      FROM scope sc
			      LEFT JOIN LATERAL (
			        SELECT s.session_id FROM assessment_session s
			         WHERE s.attempt_id = sc.attempt_id AND s.status <> 'SUPERSEDED'
			         ORDER BY s.started_at DESC NULLS LAST, s.session_id LIMIT 1
			      ) sess ON TRUE
			      JOIN assessment_problem ap
			        ON ap.generation_status = 'GENERATED'
			       AND (ap.measurement_attempt_id = sc.attempt_id
			            OR (ap.code_analysis_id = sc.code_analysis_id
			                AND ap.problem_scope = 'TEAM_SHARED_PROBLEM'))
			      LEFT JOIN problem_stage ps
			        ON ps.session_id = sess.session_id AND ps.problem_id = ap.problem_id
			     GROUP BY sc.attempt_id, ap.problem_no
			),
			agg AS (
			    SELECT attempt_id,
			           COUNT(*) FILTER (WHERE answered_axis_count > 0)::int AS scored_count,
			           COUNT(*) FILTER (WHERE answered_axis_count > 0 AND reach_level <= 1)::int AS low_count,
			           COUNT(*) FILTER (WHERE answered_axis_count > 0 AND reach_level >= 3)::int AS high_count,
			           ROUND(AVG(reach_level) FILTER (WHERE answered_axis_count > 0), 2) AS avg_level,
			           COALESCE(JSONB_AGG(JSONB_BUILD_OBJECT(
			                        'problemNo', problem_no, 'reachLevel', reach_level,
			                        'axisCode', 'L' || (reach_level + 1)) ORDER BY problem_no)
			                    FILTER (WHERE answered_axis_count > 0 AND reach_level <= 1),
			                    '[]'::jsonb) AS low_problems
			      FROM problem GROUP BY attempt_id
			),
			series AS (
			    SELECT sc.attempt_id, sc.user_id,
			           COALESCE(a.scored_count,0) AS scored_count,
			           COALESCE(a.low_count,0) AS low_count,
			           COALESCE(a.high_count,0) AS high_count,
			           a.avg_level,
			           COALESCE(a.low_problems,'[]'::jsonb) AS low_problems,
			           LAG(a.avg_level,1) OVER w AS prev1_avg,
			           LAG(a.avg_level,2) OVER w AS prev2_avg,
			           LAG(a.low_count,1) OVER w AS prev1_low,
			           (ROW_NUMBER() OVER w)::int - 1 AS prior_round_count
			      FROM scope sc LEFT JOIN agg a USING (attempt_id)
			    WINDOW w AS (PARTITION BY sc.user_id ORDER BY sc.project_sequence_no, sc.round_no)
			),
			cohort AS (
			    SELECT ROUND(AVG(s.avg_level),2) AS cohort_avg, MAX(s.avg_level) AS cohort_max
			      FROM series s JOIN round_pool rp USING (attempt_id) WHERE s.scored_count > 0
			),
			ranked AS (
			    SELECT s.attempt_id, PERCENT_RANK() OVER (ORDER BY s.avg_level DESC) AS pct_rank
			      FROM series s JOIN round_pool rp USING (attempt_id) WHERE s.scored_count > 0
			),
			judged AS (
			    SELECT rp.attempt_id, rp.validity_review_status,
			           s.scored_count, s.low_count, s.high_count, s.avg_level, s.low_problems,
			           s.prev1_avg, s.prev2_avg, s.prev1_low, s.prior_round_count,
			           c.cohort_avg, c.cohort_max, rk.pct_rank,
			           pcs.status AS contribution_status, pcs.contribution_ratio,
			           (s.scored_count > 0 AND s.low_count >= 1) AS gate
			      FROM round_pool rp
			      JOIN series s USING (attempt_id)
			      LEFT JOIN ranked rk USING (attempt_id)
			      CROSS JOIN cohort c
			      LEFT JOIN LATERAL (
			        SELECT x.status, x.contribution_ratio FROM participant_contribution_snapshot x
			         WHERE x.assessment_round_id = rp.assessment_round_id AND x.user_id = rp.user_id
			         ORDER BY x.calculated_at DESC NULLS LAST LIMIT 1
			      ) pcs ON TRUE
			     WHERE rp.outcome_judged_at IS NULL
			       AND rp.validity_review_status <> 'PENDING'
			),
			evaluated AS (
			    SELECT j.*,
			      CASE WHEN j.validity_review_status = 'CONFIRMED_INVALID' THEN 'MATCHED'
			           ELSE 'NOT_MATCHED' END AS st_invalid,
			      CASE WHEN NOT j.gate THEN 'NOT_MATCHED'
			           WHEN j.prior_round_count = 0 THEN 'NOT_APPLICABLE'
			           WHEN j.prev1_low >= 1
			             OR (j.avg_level < 2.00 AND j.prev1_avg < 2.00)
			             OR (j.avg_level < 3.00 AND j.cohort_avg IS NOT NULL
			                 AND j.avg_level <= j.cohort_avg - 0.5)
			           THEN 'MATCHED' ELSE 'NOT_MATCHED' END AS st_persistent,
			      CASE WHEN NOT j.gate THEN 'NOT_MATCHED'
			           WHEN j.prior_round_count = 0 THEN 'NOT_APPLICABLE'
			           WHEN j.avg_level <= j.prev1_avg - 1.00
			             OR (j.prior_round_count >= 2 AND j.avg_level < j.prev1_avg
			                 AND j.prev1_avg < j.prev2_avg AND j.prev2_avg - j.avg_level >= 1.00)
			           THEN 'MATCHED' ELSE 'NOT_MATCHED' END AS st_decline,
			      CASE WHEN j.contribution_status IS DISTINCT FROM 'AVAILABLE'
			                OR j.contribution_ratio IS NULL THEN 'UNAVAILABLE'
			           WHEN NOT j.gate THEN 'NOT_MATCHED'
			           WHEN j.contribution_ratio >= %2$s THEN 'MATCHED' ELSE 'NOT_MATCHED' END AS st_gap,
			      CASE WHEN j.contribution_status IS DISTINCT FROM 'AVAILABLE'
			                OR j.contribution_ratio IS NULL THEN 'UNAVAILABLE'
			           WHEN j.contribution_ratio < %2$s THEN 'MATCHED' ELSE 'NOT_MATCHED' END AS st_low_part,
			      (j.scored_count > 0 AND j.avg_level >= 3.00 AND j.low_count = 0
			       AND j.high_count >= CEIL(j.scored_count * 2.0 / 3.0)) AS excellent_candidate
			    FROM judged j
			),
			final AS (
			    SELECT e.*,
			      (e.excellent_candidate AND (
			          (e.cohort_avg IS NOT NULL AND e.avg_level >= e.cohort_avg + 0.5)
			       OR (e.pct_rank IS NOT NULL AND e.pct_rank <= 0.20)
			       OR (e.cohort_max IS NOT NULL AND e.avg_level = e.cohort_max))) AS excellent_trainee
			    FROM evaluated e
			),
			verdict AS (
			    SELECT f.attempt_id,
			      CASE WHEN f.st_invalid    = 'MATCHED' THEN 'INVALID_ATTEMPT'
			           WHEN f.st_persistent = 'MATCHED' THEN 'PERSISTENT_LOW'
			           WHEN f.st_decline    = 'MATCHED' THEN 'STAGE_DECLINE'
			           WHEN f.st_gap        = 'MATCHED' THEN 'CONTRIBUTION_UNDERSTANDING_GAP'
			           WHEN f.st_low_part   = 'MATCHED' THEN 'LOW_PARTICIPATION'
			           WHEN f.excellent_trainee          THEN 'EXCELLENT_TRAINEE'
			           ELSE NULL END AS selected,
			      JSONB_BUILD_OBJECT(
			        'judgedAt', TO_CHAR(CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
			                            'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
			        -- JSONB_BUILD_OBJECT 는 가변인자 any 라 타입 없는 자리표시자를 그대로 넣으면
			        -- "could not determine data type of parameter" 로 파싱이 실패한다. 그래서 캐스팅한다.
			        -- 이 주석에 물음표를 쓰지 않는 이유도 같다 -- 자리표시자로 세어질 여지를 남기지 않는다.
			        'policyVersion', CAST(? AS INTEGER),
			        'selectionRule', 'RISK_PRIORITY_THEN_EXCELLENT',
			        'gate', JSONB_BUILD_OBJECT(
			            'rule', 'REACH_LEVEL_BELOW_2_PROBLEM_COUNT>=1',
			            'passed', f.gate,
			            'scoredCount', f.scored_count, 'lowCount', f.low_count,
			            'highCount', f.high_count, 'avgLevel', f.avg_level,
			            'cohortAvgLevel', f.cohort_avg, 'priorRoundCount', f.prior_round_count,
			            'prevAvgLevel', f.prev1_avg, 'prevLowCount', f.prev1_low,
			            'excellentCandidate', f.excellent_candidate,
			            'lowProblems', f.low_problems),
			        'types', JSONB_BUILD_ARRAY(
			            JSONB_BUILD_OBJECT('typeCode','INVALID_ATTEMPT','evaluationStatus',f.st_invalid),
			            JSONB_BUILD_OBJECT('typeCode','PERSISTENT_LOW','evaluationStatus',f.st_persistent)
			              || CASE WHEN f.st_persistent='NOT_APPLICABLE'
			                      THEN JSONB_BUILD_OBJECT('notApplicableReasonCode',
			                                              'INSUFFICIENT_LONGITUDINAL_HISTORY')
			                      ELSE '{}'::jsonb END,
			            JSONB_BUILD_OBJECT('typeCode','STAGE_DECLINE','evaluationStatus',f.st_decline)
			              || CASE WHEN f.st_decline='NOT_APPLICABLE'
			                      THEN JSONB_BUILD_OBJECT('notApplicableReasonCode','FIRST_MINI_PROJECT')
			                      ELSE '{}'::jsonb END,
			            JSONB_BUILD_OBJECT('typeCode','CONTRIBUTION_UNDERSTANDING_GAP',
			                               'evaluationStatus',f.st_gap),
			            JSONB_BUILD_OBJECT('typeCode','LOW_PARTICIPATION','evaluationStatus',f.st_low_part),
			            JSONB_BUILD_OBJECT('typeCode','EXCELLENT_TRAINEE','evaluationStatus',
			                CASE WHEN f.excellent_trainee THEN 'MATCHED' ELSE 'NOT_MATCHED' END)),
			        'resolved', '[]'::jsonb) AS verdict
			    FROM final f
			)
			UPDATE measurement_attempt ma
			   SET outcome_type_code = v.selected,
			       outcome_verdict = v.verdict,
			       outcome_policy_version = ?,
			       outcome_judged_at = CURRENT_TIMESTAMP,
			       row_version = ma.row_version + 1,
			       updated_at = CURRENT_TIMESTAMP
			  FROM verdict v
			 WHERE ma.attempt_id = v.attempt_id
			""".formatted(VALID_ROUND_CONDITION, CONTRIBUTION_THRESHOLD);

	/**
	 * 후보 생성.
	 *
	 * <p>우수와 무효는 뺀다. 우수는 면담 대상이 아니고, 무효는 무효 확인 API(assessment 도메인)가
	 * 확정 시점에 이미 만든다. 여기서 또 만들면 소유자가 둘이 된다.
	 */
	private static final String ENROLL_CANDIDATE_SQL = """
			INSERT INTO interview_candidate (
			  org_id, cohort_id, class_id, user_id, project_id, assessment_round_id,
			  team_id, project_membership_id, team_membership_id, source_submission_id,
			  status, detected_at)
			SELECT DISTINCT
			       ma.org_id, ma.cohort_id, pm.class_id, ma.user_id, ma.project_id,
			       ma.assessment_round_id, tm.team_id, pm.project_membership_id,
			       tm.team_membership_id, ma.source_submission_id, 'ELIGIBLE', ma.outcome_judged_at
			  FROM measurement_attempt ma
			  JOIN project_membership pm ON pm.project_id = ma.project_id AND pm.user_id = ma.user_id
			  LEFT JOIN LATERAL (
			    SELECT x.team_id, x.membership_id AS team_membership_id FROM team_membership x
			     WHERE x.project_membership_id = pm.project_membership_id
			     ORDER BY x.from_at DESC LIMIT 1
			  ) tm ON TRUE
			 WHERE ma.assessment_round_id = ?
			   AND ma.attempt_type = 'INITIAL'
			   AND ma.outcome_judged_at IS NOT NULL
			   AND EXISTS (
			     SELECT 1 FROM jsonb_array_elements(ma.outcome_verdict->'types') t
			      WHERE t->>'typeCode' NOT IN ('EXCELLENT_TRAINEE','INVALID_ATTEMPT')
			        AND t->>'evaluationStatus' IN ('MATCHED','NOT_APPLICABLE'))
			ON CONFLICT (org_id, assessment_round_id, user_id) DO NOTHING
			""";

	/**
	 * 사유 생성.
	 *
	 * <p>NOT_APPLICABLE도 남긴다. 게이트에는 걸렸는데 비교할 이전 회차가 없는 1차 회차가 여기 걸리며,
	 * MG-03은 이 행을 "관찰"로 표시한다. 게이트를 통과하지 못한 행에는 애초에 이 상태가 붙지 않는다.
	 *
	 * <p>{@code reason_summary}는 화면이 그대로 읽는 문장이라 판정 근거 수치를 그 자리에서 박아 둔다.
	 * 나중에 원장이 바뀌어도 그때 본 숫자가 남는다.
	 */
	private static final String ENROLL_REASON_SQL = """
			WITH reason AS (
			    SELECT ma.org_id, ma.assessment_round_id, ma.user_id, ma.attempt_id,
			           ma.outcome_judged_at, ma.outcome_policy_version,
			           ma.outcome_verdict->'gate' AS gate,
			           t->>'typeCode' AS reason_code,
			           t->>'evaluationStatus' AS evaluation_status,
			           t->>'notApplicableReasonCode' AS not_applicable_reason_code
			      FROM measurement_attempt ma
			      CROSS JOIN LATERAL jsonb_array_elements(ma.outcome_verdict->'types') t
			     WHERE ma.assessment_round_id = ?
			       AND ma.attempt_type = 'INITIAL'
			       AND ma.outcome_judged_at IS NOT NULL
			       AND t->>'typeCode' NOT IN ('EXCELLENT_TRAINEE','INVALID_ATTEMPT')
			       AND t->>'evaluationStatus' IN ('MATCHED','NOT_APPLICABLE')
			)
			INSERT INTO interview_candidate_reason (
			  candidate_id, reason_code, evaluation_status, not_applicable_reason_code,
			  reason_status, effective_from, source_assessment_round_id, source_attempt_id,
			  reason_summary, policy_version, detected_at)
			SELECT c.candidate_id, r.reason_code, r.evaluation_status, r.not_applicable_reason_code,
			       'ACTIVE', r.outcome_judged_at, r.assessment_round_id, r.attempt_id,
			       CASE r.reason_code
			         WHEN 'PERSISTENT_LOW' THEN
			           CASE WHEN r.evaluation_status = 'NOT_APPLICABLE'
			                THEN '2단 미만 ' || (r.gate->>'lowCount')
			                     || '개. 비교할 이전 회차가 없어 유형을 붙이지 않았습니다.'
			                ELSE '2단 미만 ' || (r.gate->>'lowCount') || '개. 직전 회차 2단 미만 '
			                     || COALESCE(r.gate->>'prevLowCount','-') || '개, 평균 도달 단계 '
			                     || COALESCE(r.gate->>'prevAvgLevel','-') || ' → '
			                     || COALESCE(r.gate->>'avgLevel','-') || '.'
			           END
			         WHEN 'STAGE_DECLINE' THEN
			           CASE WHEN r.evaluation_status = 'NOT_APPLICABLE'
			                THEN '2단 미만 ' || (r.gate->>'lowCount')
			                     || '개. 첫 미니프로젝트라 직전 회차 비교가 성립하지 않습니다.'
			                ELSE '평균 도달 단계 ' || COALESCE(r.gate->>'prevAvgLevel','-') || ' → '
			                     || COALESCE(r.gate->>'avgLevel','-') || '. 2단 미만 '
			                     || (r.gate->>'lowCount') || '개.'
			           END
			         WHEN 'CONTRIBUTION_UNDERSTANDING_GAP' THEN
			           '기여도는 정상 범위인데 2단 미만이 ' || (r.gate->>'lowCount')
			           || '개입니다. 코드는 냈으나 설명하지 못한 상태입니다.'
			         WHEN 'LOW_PARTICIPATION' THEN '기여 비율이 임계값 미만입니다.'
			         ELSE NULL
			       END,
			       r.outcome_policy_version, r.outcome_judged_at
			  FROM reason r
			  JOIN interview_candidate c
			    ON c.org_id = r.org_id AND c.assessment_round_id = r.assessment_round_id
			   AND c.user_id = r.user_id
			 WHERE NOT EXISTS (
			   SELECT 1 FROM interview_candidate_reason x
			    WHERE x.candidate_id = c.candidate_id
			      AND x.reason_code = r.reason_code
			      AND x.source_attempt_id = r.attempt_id
			      AND x.reason_status = 'ACTIVE')
			""";

	/**
	 * 재시험으로 풀릴 수 있는 사유.
	 *
	 * <p>무효 응시(INVALID_ATTEMPT)와 저기여(LOW_PARTICIPATION)는 빠진다. 게이트에서 나온 유형이
	 * 아니라 각각 무결성·기여도 축이라 재시험 결과로 풀리지 않는다.
	 */
	private static final String RESOLVABLE_REASON_CODES =
			"'PERSISTENT_LOW', 'STAGE_DECLINE', 'CONTRIBUTION_UNDERSTANDING_GAP'";

	/**
	 * 해소 대상 회차 찾기.
	 *
	 * <p>판정 배치와 <b>분리된</b> 큐다. 함께 돌면 해소가 영영 실행되지 않는다 — 판정은 회차의 마지막
	 * 응시 마감 뒤 한 번에 끝나고, 재시험은 그 <b>뒤에</b> 열린다. 판정이 끝난 회차는
	 * {@code ROUNDS_TO_JUDGE_SQL}의 "미판정 수행이 남아 있을 것"을 더는 만족하지 못하므로 다시 잡히지
	 * 않는다.
	 *
	 * <p>{@code rma.terminal_at > r.updated_at}이 이 큐를 끝나게 하는 조건이다. 사유 하나를 재시험
	 * 결과와 맞춰 본 뒤에는 {@code updated_at}이 그 재시험보다 뒤가 되므로, <b>더 새로운</b> 재시험이
	 * 끝나기 전에는 같은 회차가 다시 잡히지 않는다. 이 조건이 없으면 끝내 풀리지 않는 사유를 가진 회차가
	 * 매 주기 목록에 남아 {@code LIMIT}을 차지하고 새 회차를 굶긴다. 갱신은
	 * {@link #MARK_RESOLUTION_EVALUATED_SQL}이 한다.
	 *
	 * <p>오래 기다린 회차부터 돌린다. {@code LIMIT}에 걸려 밀리더라도 순서가 돌아온다.
	 */
	private static final String ROUNDS_TO_RESOLVE_SQL = """
			SELECT c.assessment_round_id
			  FROM interview_candidate_reason r
			  JOIN interview_candidate c ON c.candidate_id = r.candidate_id
			 WHERE r.reason_status = 'ACTIVE'
			   AND r.reason_code IN (%1$s)
			   AND EXISTS (
			     SELECT 1 FROM measurement_attempt rma
			      WHERE rma.assessment_round_id = c.assessment_round_id
			        AND rma.user_id = c.user_id
			        AND rma.attempt_type = 'RETRY'
			        AND rma.status = 'COMPLETED'
			        AND rma.terminal_at > r.updated_at)
			 GROUP BY c.assessment_round_id
			 ORDER BY MIN(r.updated_at)
			 LIMIT ?
			""".formatted(RESOLVABLE_REASON_CODES);

	/**
	 * 해소 판정을 시도했다는 표시.
	 *
	 * <p>{@link #RESOLVE_SQL} <b>뒤에</b> 돌아야 한다. 그때까지 살아남은 ACTIVE 사유가 "재시험 결과와
	 * 맞춰 봤지만 풀리지 않은" 것들이고, 이 갱신이 {@link #ROUNDS_TO_RESOLVE_SQL}의 워터마크를 밀어
	 * 회차를 큐에서 내린다.
	 *
	 * <p>{@code row_version}은 건드리지 않는다. 낙관적 잠금 값이라 사유의 내용이 바뀌지 않았는데 올리면
	 * 같은 행을 들고 있던 편집이 근거 없이 충돌한다.
	 */
	private static final String MARK_RESOLUTION_EVALUATED_SQL = """
			UPDATE interview_candidate_reason r
			   SET updated_at = CURRENT_TIMESTAMP
			  FROM interview_candidate c
			 WHERE c.candidate_id = r.candidate_id
			   AND c.assessment_round_id = ?
			   AND r.reason_status = 'ACTIVE'
			   AND r.reason_code IN (%1$s)
			   AND EXISTS (
			     SELECT 1 FROM measurement_attempt rma
			      WHERE rma.assessment_round_id = c.assessment_round_id
			        AND rma.user_id = c.user_id
			        AND rma.attempt_type = 'RETRY'
			        AND rma.status = 'COMPLETED'
			        AND rma.terminal_at > r.updated_at)
			""".formatted(RESOLVABLE_REASON_CODES);

	/**
	 * 재시험 해소 (정책 §5B).
	 *
	 * <p>게이트를 만든 문제가 <b>전부</b> 2단 이상에 도달해야 닫는다. 하나라도 남으면 게이트가 여전히
	 * 성립하므로 유지한다. 재시험을 안 봤거나 다시 실패해도 그대로 둔다.
	 */
	private static final String RESOLVE_SQL = """
			WITH active AS (
			    SELECT r.candidate_reason_id, r.effective_from, c.assessment_round_id, c.user_id,
			           ma.outcome_verdict->'gate'->'lowProblems' AS low_problems
			      FROM interview_candidate_reason r
			      JOIN interview_candidate c ON c.candidate_id = r.candidate_id
			      JOIN measurement_attempt ma ON ma.attempt_id = r.source_attempt_id
			     WHERE r.reason_status = 'ACTIVE'
			       AND r.reason_code IN (%1$s)
			       AND c.assessment_round_id = ?
			       AND ma.outcome_verdict IS NOT NULL
			),
			retry_level AS (
			    SELECT a.candidate_reason_id, ap.problem_no,
			           COALESCE(MAX(SUBSTRING(ps.axis_code FROM 2)::int)
			                    FILTER (WHERE ps.status='PASSED'),0)::int AS reach_level
			      FROM active a
			      JOIN measurement_attempt rma
			        ON rma.assessment_round_id = a.assessment_round_id AND rma.user_id = a.user_id
			       AND rma.attempt_type = 'RETRY' AND rma.status = 'COMPLETED'
			      JOIN LATERAL (
			        SELECT s.session_id FROM assessment_session s
			         WHERE s.attempt_id = rma.attempt_id AND s.status <> 'SUPERSEDED'
			         ORDER BY s.started_at DESC NULLS LAST, s.session_id LIMIT 1
			      ) sess ON TRUE
			      -- 재시험은 새 문제를 만들지 않는다. uq_assessment_problem_code_analysis_id_problem_no 가
			      -- 같은 분석의 같은 문항 번호를 한 행으로 묶으므로 재시험 답안은 원 회차와 같은
			      -- problem_id 를 가리킨다. 그래서 수행이 아니라 세션에서 문제로 거슬러 올라간다.
			      JOIN problem_stage ps ON ps.session_id = sess.session_id
			      JOIN assessment_problem ap ON ap.problem_id = ps.problem_id
			     GROUP BY a.candidate_reason_id, ap.problem_no
			),
			resolved AS (
			    SELECT a.candidate_reason_id, a.effective_from
			      FROM active a
			     WHERE JSONB_ARRAY_LENGTH(a.low_problems) > 0
			       AND NOT EXISTS (
			         SELECT 1 FROM JSONB_ARRAY_ELEMENTS(a.low_problems) lp
			          WHERE NOT EXISTS (
			            SELECT 1 FROM retry_level rt
			             WHERE rt.candidate_reason_id = a.candidate_reason_id
			               AND rt.problem_no = (lp->>'problemNo')::int
			               AND rt.reach_level >= 2))
			)
			UPDATE interview_candidate_reason r
			   SET reason_status = 'RESOLVED',
			       -- ck_interview_candidate_reason_reason_status_2 가 effective_to > effective_from 을
			       -- 요구한다. 판정과 해소가 같은 순간이면 같은 값이 되어 걸린다.
			       effective_to = GREATEST(CURRENT_TIMESTAMP, x.effective_from + INTERVAL '1 microsecond'),
			       resolution_code = 'RETRY_PASSED',
			       updated_at = CURRENT_TIMESTAMP,
			       row_version = r.row_version + 1
			  FROM resolved x
			 WHERE r.candidate_reason_id = x.candidate_reason_id
			""".formatted(RESOLVABLE_REASON_CODES);

	/**
	 * 판정 유예. 회차의 <b>마지막 응시 마감</b> 이후 이만큼 지나야 그 회차를 판정한다.
	 *
	 * <p>1시간은 세션 상한({@code session.time-limit-minutes}, 기본 60분)에서 온다. 상한은 세션을 여는
	 * 순간부터 재고({@code AssessmentSessionService#start}) 응시 마감으로 잘리지 않으므로, 마감 직전에
	 * 연 세션은 마감을 넘겨 최대 60분까지 살아 있다. 1분은 그 경계에 정확히 걸치지 않기 위한 여유다.
	 *
	 * <p>세션 상한을 늘리면 이 값도 같이 올려야 한다. 짧으면 아직 응시 중인 교육생이 기수 평균의
	 * 모수에서 빠진 채 다른 교육생의 판정이 확정된다.
	 */
	private static final String JUDGE_GRACE_INTERVAL = "INTERVAL '1 hour 1 minute'";

	/**
	 * 판정할 회차 찾기.
	 *
	 * <p>회차의 응시 마감은 교육생마다 다르다 — {@code measurement_attempt.assessment_close_at}은 팀
	 * 분석이 끝난 시점부터 열려 개인별로 찍힌다. 그래서 앵커는 회차 레벨의
	 * {@code assessment_due_at}이 아니라 그 회차 INITIAL 수행 중 <b>가장 늦은</b> 마감이다. 마지막
	 * 교육생이 마칠 수 있는 시각이 지나야 회차 모집단이 확정된다.
	 *
	 * <p>{@code MAX}가 NULL이면(분석이 한 건도 세션을 열지 못한 회차) 회차 레벨 마감으로 물러선다.
	 * 🔴 <b>그 뒷받침이 2026-08-16에 사라졌다</b> — 회차 응시 창이 폐기돼 {@code assessment_due_at}은
	 * 전 행 NULL이다. 즉 <b>개인 창이 하나도 없는 회차는 판정 대상에서 빠진다.</b> 분석이 전부 실패한
	 * 회차가 그런 모양인데, 그때는 셀 응시가 없으므로 결과가 달라지지 않는다. 응시가 있는데 창이 비는
	 * 경로는 없다({@code JdbcAssessmentSessionPreparer}가 세션과 창을 함께 쓴다).
	 *
	 * <p>RETRY는 앵커에서 뺀다. 재시험은 판정 뒤에 열리므로 포함하면 재시험이 발급될 때마다 앵커가
	 * 뒤로 밀려 INITIAL 판정이 영영 시작되지 않는다.
	 */
	private static final String ROUNDS_TO_JUDGE_SQL = """
			SELECT r.assessment_round_id
			  FROM project_assessment_round r
			  JOIN project p ON p.project_id = r.project_id
			 WHERE p.project_category = 'MINI_PROJECT'
			   AND now() >= COALESCE(
			         (SELECT MAX(ma2.assessment_close_at)
			            FROM measurement_attempt ma2
			           WHERE ma2.assessment_round_id = r.assessment_round_id
			             AND ma2.attempt_type = 'INITIAL'),
			         r.assessment_due_at) + %1$s
			   AND EXISTS (
			     SELECT 1 FROM measurement_attempt ma
			      WHERE ma.assessment_round_id = r.assessment_round_id
			        AND ma.attempt_type = 'INITIAL'
			        AND ma.status = 'COMPLETED'
			        AND ma.outcome_judged_at IS NULL
			        AND ma.validity_review_status <> 'PENDING')
			 ORDER BY p.sequence_no, r.round_no
			 LIMIT ?
			""".formatted(JUDGE_GRACE_INTERVAL);

	private final JdbcTemplate jdbcTemplate;

	@Override
	public int judgeRound(UUID assessmentRoundId, int policyVersion) {
		return jdbcTemplate.update(JUDGE_SQL, assessmentRoundId, policyVersion, policyVersion);
	}

	@Override
	public int enrollCandidates(UUID assessmentRoundId) {
		return jdbcTemplate.update(ENROLL_CANDIDATE_SQL, assessmentRoundId);
	}

	@Override
	public int enrollReasons(UUID assessmentRoundId) {
		return jdbcTemplate.update(ENROLL_REASON_SQL, assessmentRoundId);
	}

	@Override
	public int resolveByRetry(UUID assessmentRoundId) {
		return jdbcTemplate.update(RESOLVE_SQL, assessmentRoundId);
	}

	@Override
	public int markResolutionEvaluated(UUID assessmentRoundId) {
		return jdbcTemplate.update(MARK_RESOLUTION_EVALUATED_SQL, assessmentRoundId);
	}

	@Override
	public List<UUID> findRoundsToJudge(int limit) {
		return jdbcTemplate.query(ROUNDS_TO_JUDGE_SQL,
				(rs, rowNum) -> rs.getObject("assessment_round_id", UUID.class), limit);
	}

	@Override
	public List<UUID> findRoundsToResolve(int limit) {
		return jdbcTemplate.query(ROUNDS_TO_RESOLVE_SQL,
				(rs, rowNum) -> rs.getObject("assessment_round_id", UUID.class), limit);
	}
}
