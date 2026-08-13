package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload.Hint;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload.Problem;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload.ProblemStage;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 분석이 성공하면 응시를 SESSION_READY로 옮기고, 세션을 열어 AI가 동결해 준 질문·힌트를 깔아 둔다.
 *
 * <h2>응시는 "전제"가 아니라 "보장"이다 (2026-08-10 변경)</h2>
 *
 * <p>응시({@code measurement_attempt})는 여전히 제출 트랜잭션이 먼저 연다
 * ({@code JdbcMeasurementAttemptOpener}) — 그래야 분석이 실패해도 응시가 남아 "미응시"와
 * "분석 실패로 응시 불가"가 구분되고, 교육생 홈이 분석 전에도 "제출함 / 분석 중"을 보여줄 수 있다.
 *
 * <p><b>다만 이쪽이 그 존재에 의존하지는 않는다.</b> 종전에는 여기서 전이({@code UPDATE})만 해서,
 * 응시 행이 없으면 UPDATE 0건 → 세션 0건 → 단계 0건으로 조용히 무너졌다. 문제
 * ({@code assessment_problem})는 {@code code_analysis_id}만 보고 저장되므로 "문제는 있는데 세션이
 * 없다"는 상태가 남았고, {@code trainee_home_round_view}는 응시 행이 <b>없을 때도</b>
 * {@code NOT_STARTED}를 보여줘(뷰의 {@code completion_status}) 원인 추적이 어려웠다.
 * 이제 {@link #ensureAttempts}가 없는 응시를 먼저 만들어 그 연쇄를 끊는다.
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

	/**
	 * 개인 응시 창의 길이(시간). <b>세션이 열린 순간부터 이만큼</b>이며 회차 응시 창과 무관하다.
	 *
	 * <h2>🔴 회차 응시 창을 판정에 쓰지 않는다 (2026-08-13 확정)</h2>
	 *
	 * <p>응답에는 회차 창({@code roundAssessmentOpenAt}·{@code roundAssessmentDueAt})도 함께 실리지만
	 * <b>그것은 일정 안내용</b>이고, 응시 가능 여부는 이 개인 창 하나로 정한다. 규칙은
	 * "제출 마감 전에 코드를 내고 분석이 끝나 세션이 열리면, 그 시점부터 24시간"이다.
	 *
	 * <p>정의서 주석은 개인 창을 {@code MAX(analysis_completed_at, 회차 open_at)}으로,
	 * 마감을 회차 {@code assessment_due_at}으로 적고 있어 <b>이 규칙과 다르다.</b> 운영 판단이
	 * 이쪽으로 확정됐으므로 코드를 기준으로 두고, 정의서 주석은 다음 개정 때 맞춘다.
	 */
	@Value("${assessment.window-hours:24}")
	private int assessmentWindowHours;

	private final JdbcTemplate jdbc;

	/**
	 * 응시를 확보하고 SESSION_READY로 옮긴 뒤 세션을 연다. <b>문제 적재보다 먼저</b> 불린다 —
	 * {@code assessment_session}은 {@code measurement_attempt}만 참조하므로 문제를 기다릴 이유가 없고,
	 * 이 순서라야 적재 코드가 "분석 → 세션 → 문제 → 단계"로 읽힌다.
	 *
	 * @return 새로 열린 {@code assessment_session} 행 수
	 */
	public int openSessions(AnalysisJob job, UUID analysisId) {
		int created = ensureAttempts(job, analysisId);
		int transitioned = markAttemptsReady(job, analysisId);
		int sessions = insertSessions(job);

		if (created > 0) {
			// 제출 시점에 열려 있어야 정상이다. 여기서 만들어졌다면 제출 경로가 응시를 만들지
			// 못했거나(이벤트 유실·예외) 운영 작업으로 지워진 것이다. 복구는 됐지만 원인은 남는다.
			log.warn("제출 시점에 없던 응시를 분석 시점에 만들었다. 제출 경로 점검이 필요하다: analysisId={}, 생성={}",
					analysisId, created);
		}
		if (created == 0 && transitioned == 0) {
			// 만들 것도 옮길 것도 없다. 팀 배정이 끊겼거나(to_at·project_membership.status) 이미
			// 진행 중·종료된 응시뿐이라는 뜻이고, 그러면 세션도 단계도 만들어지지 않는다.
			log.warn("SESSION_READY 로 옮길 응시가 없다. 팀 배정과 응시 상태를 확인해야 한다: analysisId={}",
					analysisId);
		}
		log.info("세션 개설: analysisId={}, 응시생성={}, 전이={}, 세션={}",
				analysisId, created, transitioned, sessions);
		return sessions;
	}

	/**
	 * 없는 응시를 만든다. 종전에는 이 단계가 없어 응시가 하나라도 비면 세션·단계가 통째로 생기지
	 * 않았다.
	 *
	 * <p><b>바로 {@code SESSION_READY}로 넣는다.</b> 분석이 이미 성공한 시점이라 거쳐 갈 중간 상태가
	 * 없고, {@code ck_measurement_attempt_status_2}는 비종료 상태에 {@code terminal_reason_code}·
	 * {@code terminal_at}가 NULL일 것만 요구하므로 둘을 채우지 않는 이 INSERT는 그 조합을 만족한다.
	 *
	 * <p>{@code ON CONFLICT ... WHERE attempt_type='INITIAL'}는 {@code uq_measurement_attempt_initial}
	 * (부분 UNIQUE 인덱스)의 추론 조건과 정확히 같다. {@code NOT EXISTS}로 거르지 않는 이유는 마감
	 * 직전 동시 제출에서 두 트랜잭션이 같은 사용자 행을 함께 만들려 할 수 있기 때문이다 — 그 경합은
	 * 인덱스가 판정해야 한다.
	 *
	 * <p>{@code cohort_id}·{@code project_id}는 {@code JdbcMeasurementAttemptOpener}와 같은 경로로
	 * 얻는다. 두 곳이 다른 기준으로 채우면 같은 사용자의 응시가 제출 경로와 분석 경로에서 다른 기수를
	 * 가리키게 된다.
	 */
	private int ensureAttempts(AnalysisJob job, UUID analysisId) {
		return jdbc.update("""
				INSERT INTO measurement_attempt (org_id, cohort_id, assessment_round_id, project_id, user_id,
				    source_submission_id, code_analysis_id, attempt_type, attempt_sequence_no,
				    status, validity_review_status, analysis_completed_at,
				    assessment_open_at, assessment_close_at)
				SELECT ?, c.cohort_id, ?, pm.project_id, pm.user_id, ?, ?, 'INITIAL', 1,
				       'SESSION_READY', 'NOT_REQUIRED', now(),
				       now(), now() + make_interval(hours => ?)
				  FROM team_membership tm
				  JOIN project_membership pm ON pm.project_membership_id = tm.project_membership_id
				   AND pm.status = 'ACTIVE'
				  JOIN class c ON c.class_id = pm.class_id
				 WHERE tm.team_id = ? AND tm.to_at IS NULL
				ON CONFLICT (assessment_round_id, user_id) WHERE attempt_type = 'INITIAL' DO NOTHING
				""",
				job.getOrgId(), job.getAssessmentRoundId(), job.getSubmissionId(), analysisId,
				assessmentWindowHours, job.getTeamId());
	}

	/**
	 * 이미 있는 응시를 SESSION_READY로 옮긴다.
	 *
	 * <p>아직 시작 전 상태에서만 옮긴다. COMPLETED·EXPIRED·SESSION_IN_PROGRESS를 되돌리면 끝났거나
	 * 풀고 있는 시험을 다시 열게 된다.
	 *
	 * <p><b>{@code ANALYSIS_FAILED}만 예외로 되돌린다(2026-08-10).</b> 일시적 장애로
	 * {@code ai.analysis.max-attempts}를 소진하면 {@link #markAttemptsAnalysisFailed}가 응시를
	 * {@code FAILED}로 닫는데, 종전에는 그 뒤 재제출로 분석이 성공해도 이 UPDATE의 {@code status IN}
	 * 목록에 {@code FAILED}가 없어 그 사람만 영영 응시하지 못했다. 분석이 실제로 성공한 이상 그
	 * 종료 표시는 사실이 아니다.
	 *
	 * <p>되돌릴 때 {@code terminal_reason_code}·{@code terminal_at}을 함께 NULL로 지운다 —
	 * {@code ck_measurement_attempt_status_2}가 비종료 상태에 두 컬럼이 모두 NULL일 것을 요구하므로,
	 * 하나라도 남기면 UPDATE 자체가 막힌다.
	 *
	 * <p>다른 사유의 {@code FAILED}(예: {@code INVALID})는 건드리지 않는다. 그쪽은 분석과 무관한
	 * 운영 판단이라 분석 성공이 뒤집을 근거가 되지 못한다.
	 */
	private int markAttemptsReady(AnalysisJob job, UUID analysisId) {
		return jdbc.update("""
				UPDATE measurement_attempt
				   SET status = 'SESSION_READY', code_analysis_id = ?, source_submission_id = ?,
				       analysis_completed_at = now(), updated_at = now(),
				       assessment_open_at = now(),
				       assessment_close_at = now() + make_interval(hours => ?),
				       terminal_reason_code = NULL, terminal_at = NULL
				 WHERE assessment_round_id = ? AND attempt_type = 'INITIAL'
				   AND (status IN ('NOT_STARTED', 'SUBMITTED', 'ANALYZING')
				        OR (status = 'FAILED' AND terminal_reason_code = 'ANALYSIS_FAILED'))
				   AND user_id IN (""" + TEAM_MEMBERS + ")",
				analysisId, job.getSubmissionId(), assessmentWindowHours,
				job.getAssessmentRoundId(), job.getTeamId());
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
	private int insertSessions(AnalysisJob job) {
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
	 *
	 * <p>{@link #openSessions} <b>뒤</b>, 문제 적재 뒤에 불린다 — {@code problem_stage}가
	 * {@code assessment_session}과 {@code assessment_problem}을 모두 참조한다.
	 *
	 * @return 새로 깔린 {@code problem_stage} 행 수
	 */
	public int insertStages(AnalysisJob job, UUID analysisId, AnalysisResultPayload result) {
		if (result.problems() == null) {
			return 0;
		}
		int inserted = 0;
		int attempted = 0;
		int skipped = 0;
		for (Problem problem : result.problems()) {
			Map<String, ProblemStage> byAxis = stagesByAxis(problem);
			if (byAxis == null) {
				log.warn("질문·힌트가 온전하지 않아 단계를 만들지 않는다: analysisId={}, problemNo={}",
						analysisId, problem.problemNo());
				skipped++;
				continue;
			}
			attempted++;
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
		verifyStageCount(job, analysisId, inserted, attempted, skipped);
		return inserted;
	}

	/**
	 * 깔린 단계 수를 기대치와 대조한다.
	 *
	 * <p>이 대조가 없으면 0건도 {@code 단계=0}이라는 info 로그 한 줄로만 남아 정상 실행과 구분되지
	 * 않는다. 실제로 응시가 지워져 세션·단계가 통째로 비었을 때 그 사실이 며칠 뒤에야 드러났다.
	 *
	 * <p>{@code inserted < expected}는 이상 신호일 뿐 반드시 사고는 아니다 — 재분석에서 이미 같은
	 * 축의 단계가 있으면 {@code NOT EXISTS}가 건너뛴다. 그래서 예외를 던지지 않고 세 숫자를 함께
	 * 남겨 어느 쪽인지 판별할 수 있게 한다.
	 */
	private void verifyStageCount(AnalysisJob job, UUID analysisId, int inserted, int attempted, int skipped) {
		int sessions = countReadySessions(job);
		int expected = sessions * attempted * AXES.size();
		if (inserted < expected) {
			log.error("단계가 기대보다 적게 깔렸다: analysisId={}, 기대={}, 실제={}, READY세션={}, 대상문제={}, 스킵문제={}",
					analysisId, expected, inserted, sessions, attempted, skipped);
			return;
		}
		log.info("단계 적재 완료: analysisId={}, 단계={}, READY세션={}, 대상문제={}, 스킵문제={}",
				analysisId, inserted, sessions, attempted, skipped);
	}

	/** 기대치의 분모. {@link #insertStages}의 INSERT가 고르는 세션과 <b>같은 조건</b>이어야 한다. */
	private int countReadySessions(AnalysisJob job) {
		Integer count = jdbc.queryForObject("""
				SELECT count(*)
				  FROM assessment_session s
				  JOIN measurement_attempt a ON a.attempt_id = s.attempt_id
				   AND a.assessment_round_id = ? AND a.attempt_type = 'INITIAL'
				   AND a.status = 'SESSION_READY'
				   AND a.user_id IN (""" + TEAM_MEMBERS + """
				)
				 WHERE s.status = 'READY'
				""",
				Integer.class, job.getAssessmentRoundId(), job.getTeamId());
		return count == null ? 0 : count;
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
