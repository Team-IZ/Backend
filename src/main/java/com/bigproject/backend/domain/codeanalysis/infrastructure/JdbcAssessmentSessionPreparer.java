package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload.Hint;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload.Problem;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload.ProblemStage;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 분석이 성공하면 응시를 SESSION_READY로 옮기고, 세션을 열어 AI가 동결해 준 질문·힌트를 깔아 둔다.
 *
 * <p><b>응시 자체는 여기서 만들지 않는다.</b> 제출 트랜잭션이
 * {@code JdbcMeasurementAttemptOpener}로 미리 열어 둔다 — 그래야 분석이 실패해도 응시가 남아
 * "미응시"와 "분석 실패로 응시 불가"가 구분된다.
 *
 * <h2>왜 세션을 미리 만드는가</h2>
 *
 * <p>AI는 4축 질문과 힌트 8개를 <b>분석 시점에 동결</b>해 보낸다("세션은 저장분을 꺼내 쓰기만 한다").
 * 그런데 {@code problem_stage.session_id}가 NOT NULL이고 {@code assessment_session.attempt_id}는
 * {@code measurement_attempt}를 가리키므로, 그 질문을 저장하려면 응시와 세션이 먼저 있어야 한다.
 * 그래서 분석 성공이 곧 "응시 준비 완료"가 된다.
 *
 * <h2>범위를 좁히는 것이 핵심이다</h2>
 *
 * <p>세 단계 모두 <b>같은 범위</b>(회차 · 팀 · INITIAL · SESSION_READY)로 한정한다. 초안에서
 * 단계 삽입만 {@code code_analysis_id}로 묶었더니, 같은 분석을 참조하는 <b>다른 회차의 세션과
 * 이미 COMPLETED된 응시에까지</b> 단계가 들어갔다(임시 Postgres 실측으로 확인). 끝난 시험에 문항이
 * 늘어나는 것은 조용히 일어나면서 결과를 통째로 왜곡한다.
 *
 * <p>세션도 {@code READY}만 고른다. 진행 중이거나 끝난 세션에 단계를 더하면 교육생이 이미 답하고
 * 있는 시험이 도중에 바뀐다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcAssessmentSessionPreparer {

	/**
	 * 4축이 곧 4단계이고 {@code question_sequence_no}는 그 순서다
	 * ({@code uq_problem_stage_session_id_problem_id_question_sequence_no}).
	 */
	private static final List<String> AXES = List.of("L1", "L2", "L3", "L4");

	/**
	 * 팀원 조회. {@code to_at IS NULL}인 팀 배정과 {@code status='ACTIVE'}인 프로젝트 참여만 센다 —
	 * 팀을 옮겼거나 이탈한 사람에게 응시를 열면 안 된다.
	 */
	private static final String TEAM_MEMBERS = """
			SELECT pm.user_id FROM team_membership tm
			  JOIN project_membership pm ON pm.project_membership_id = tm.project_membership_id
			   AND pm.status = 'ACTIVE'
			 WHERE tm.team_id = ? AND tm.to_at IS NULL
			""";

	private final JdbcTemplate jdbc;

	/** @return 새로 깔린 {@code problem_stage} 행 수 */
	public int prepare(AnalysisJob job, UUID analysisId, AnalysisResultPayload result) {
		int transitioned = markAttemptsReady(job, analysisId);
		int sessions = openSessions(job);
		int stages = insertStages(job, analysisId, result);

		if (transitioned == 0) {
			// 제출 시점에 응시를 열어 두므로 0건은 비정상이다. 팀 배정이 끊겼거나 이미 진행 중인
			// 응시뿐이라는 뜻이고, 그러면 세션도 단계도 만들어지지 않는다.
			log.warn("SESSION_READY 로 옮길 응시가 없다. 제출 시점 응시 개설을 확인해야 한다: analysisId={}",
					analysisId);
		}
		log.info("응시 준비 완료: analysisId={}, 전이={}, 세션={}, 단계={}",
				analysisId, transitioned, sessions, stages);
		return stages;
	}

	/**
	 * 이미 있는 응시를 SESSION_READY로 옮긴다.
	 *
	 * <p>응시 자체는 <b>제출 시점</b>에 {@code SUBMITTED}로 이미 열려 있다
	 * ({@code JdbcMeasurementAttemptOpener}). 여기서는 전이만 한다.
	 *
	 * <p>아직 시작 전 상태에서만 옮긴다. COMPLETED·FAILED·EXPIRED를 되돌리면
	 * {@code ck_measurement_attempt_status_2}가 요구하는 {@code terminal_reason_code}·
	 * {@code terminal_at} 조합이 깨지고, 무엇보다 끝난 응시를 다시 열게 된다.
	 */
	private int markAttemptsReady(AnalysisJob job, UUID analysisId) {
		return jdbc.update("""
				UPDATE measurement_attempt
				   SET status = 'SESSION_READY', code_analysis_id = ?, source_submission_id = ?,
				       analysis_completed_at = now(), updated_at = now()
				 WHERE assessment_round_id = ? AND attempt_type = 'INITIAL'
				   AND status IN ('NOT_STARTED', 'SUBMITTED', 'ANALYZING')
				   AND user_id IN (""" + TEAM_MEMBERS + ")",
				analysisId, job.getSubmissionId(), job.getAssessmentRoundId(), job.getTeamId());
	}

	/**
	 * 분석 요청을 보냈다는 사실을 응시에 남긴다. {@code SUBMITTED} → {@code ANALYZING}.
	 *
	 * <p>이 전이가 없으면 교육생 홈이 "제출함"과 "분석 중"을 구분할 수 없다.
	 */
	public int markAttemptsAnalyzing(UUID assessmentRoundId, UUID teamId) {
		return jdbc.update("""
				UPDATE measurement_attempt
				   SET status = 'ANALYZING', updated_at = now()
				 WHERE assessment_round_id = ? AND attempt_type = 'INITIAL'
				   AND status IN ('NOT_STARTED', 'SUBMITTED')
				   AND user_id IN (""" + TEAM_MEMBERS + ")",
				assessmentRoundId, teamId);
	}

	/**
	 * 분석이 <b>되돌릴 수 없게</b> 실패했음을 응시에 남긴다.
	 *
	 * <p>{@code ck_measurement_attempt_status_2}가 종료 상태에 {@code terminal_reason_code}·
	 * {@code terminal_at}을 함께 요구한다. {@code ANALYSIS_FAILED}가 그 값 집합에 있다.
	 *
	 * <p><b>재시도 가능한 실패에는 부르지 않는다.</b> 일시적 장애로 종료 표시를 하면 위험 교육생
	 * 산식의 분모에서 그 사람이 빠지는데(중단 3종), 잠시 뒤 재시도가 성공하면 이미 틀린 집계가
	 * 남는다. 재시도 여지가 없을 때만 부른다.
	 *
	 * <p>진행 중이거나 끝난 응시는 건드리지 않는다 — 이미 세션을 시작한 팀원의 시험을 분석 실패로
	 * 되돌릴 수는 없다.
	 */
	public int markAttemptsAnalysisFailed(UUID assessmentRoundId, UUID teamId) {
		return jdbc.update("""
				UPDATE measurement_attempt
				   SET status = 'FAILED', terminal_reason_code = 'ANALYSIS_FAILED',
				       terminal_at = now(), updated_at = now()
				 WHERE assessment_round_id = ? AND attempt_type = 'INITIAL'
				   AND status IN ('NOT_STARTED', 'SUBMITTED', 'ANALYZING')
				   AND user_id IN (""" + TEAM_MEMBERS + ")",
				assessmentRoundId, teamId);
	}

	/** 응시당 세션 1건({@code uq_assessment_session_attempt_id}). 카운터 넷은 NOT NULL이라 0으로 연다. */
	private int openSessions(AnalysisJob job) {
		return jdbc.update("""
				INSERT INTO assessment_session (org_id, attempt_id, status,
				    window_leave_count, total_away_seconds, connection_loss_count, total_disconnected_seconds)
				SELECT ?, a.attempt_id, 'READY', 0, 0, 0, 0
				  FROM measurement_attempt a
				 WHERE a.assessment_round_id = ? AND a.attempt_type = 'INITIAL'
				   AND a.status = 'SESSION_READY'
				   AND a.user_id IN (""" + TEAM_MEMBERS + """
				)
				   AND NOT EXISTS (SELECT 1 FROM assessment_session s WHERE s.attempt_id = a.attempt_id)
				""",
				job.getOrgId(), job.getAssessmentRoundId(), job.getTeamId());
	}

	/**
	 * AI가 동결한 질문·힌트를 세션마다 깔아 둔다.
	 *
	 * <p>{@code GENERATED} 문제만 대상이다 — 정의서가 NOT_GENERATED 슬롯에는 단계를 만들지 않는다고
	 * 못박고 있고, 애초에 물어볼 질문이 없다.
	 *
	 * <p>질문·힌트가 하나라도 비면 그 문제는 통째로 건너뛴다. {@code question_text}·
	 * {@code first_hint_text}·{@code second_hint_text}가 전부 NOT NULL이라 빈 값으로는 저장할 수
	 * 없고, 축 4개 중 일부만 깔면 세션이 중간에 끊긴다.
	 */
	private int insertStages(AnalysisJob job, UUID analysisId, AnalysisResultPayload result) {
		if (result.problems() == null) {
			return 0;
		}
		int inserted = 0;
		for (Problem problem : result.problems()) {
			Map<String, ProblemStage> byAxis = stagesByAxis(problem);
			if (byAxis == null) {
				log.warn("질문·힌트가 온전하지 않아 단계를 만들지 않는다: analysisId={}, problemNo={}",
						analysisId, problem.problemNo());
				continue;
			}
			for (int i = 0; i < AXES.size(); i++) {
				String axis = AXES.get(i);
				ProblemStage stage = byAxis.get(axis);
				inserted += jdbc.update("""
						INSERT INTO problem_stage (session_id, problem_id, axis_code, question_sequence_no,
						    question_text, first_hint_text, second_hint_text, status, is_flagged)
						SELECT s.session_id, p.problem_id, ?, ?, ?, ?, ?, 'PREPARED', ?
						  FROM assessment_session s
						  JOIN measurement_attempt a ON a.attempt_id = s.attempt_id
						   AND a.assessment_round_id = ? AND a.attempt_type = 'INITIAL'
						   AND a.status = 'SESSION_READY'
						   AND a.user_id IN (""" + TEAM_MEMBERS + """
						)
						  JOIN assessment_problem p ON p.code_analysis_id = ?
						   AND p.generation_status = 'GENERATED' AND p.problem_no = ?
						 WHERE s.status = 'READY'
						   AND NOT EXISTS (SELECT 1 FROM problem_stage ps
						                    WHERE ps.session_id = s.session_id AND ps.problem_id = p.problem_id
						                      AND ps.axis_code = ?)
						""",
						axis, i + 1, stage.questionText(),
						hintText(stage, 1), hintText(stage, 2),
						Boolean.TRUE.equals(stage.flagged()),
						job.getAssessmentRoundId(), job.getTeamId(),
						analysisId, problem.problemNo(), axis);
			}
		}
		return inserted;
	}

	/**
	 * 축별 단계를 모으되, 4축이 다 있고 각 축에 힌트 2개가 다 있을 때만 돌려준다.
	 *
	 * @return 온전하지 않으면 {@code null}
	 */
	private Map<String, ProblemStage> stagesByAxis(Problem problem) {
		if (problem.stages() == null) {
			return null;
		}
		Map<String, ProblemStage> byAxis = new java.util.HashMap<>();
		for (ProblemStage stage : problem.stages()) {
			if (stage.axisCode() != null) {
				byAxis.put(stage.axisCode(), stage);
			}
		}
		for (String axis : AXES) {
			ProblemStage stage = byAxis.get(axis);
			if (stage == null || stage.questionText() == null || stage.questionText().isBlank()
					|| hintText(stage, 1) == null || hintText(stage, 2) == null) {
				return null;
			}
		}
		return byAxis;
	}

	private static String hintText(ProblemStage stage, int level) {
		if (stage.hints() == null) {
			return null;
		}
		for (Hint hint : stage.hints()) {
			if (hint.hintLevel() != null && hint.hintLevel() == level
					&& hint.hintText() != null && !hint.hintText().isBlank()) {
				return hint.hintText();
			}
		}
		return null;
	}
}
