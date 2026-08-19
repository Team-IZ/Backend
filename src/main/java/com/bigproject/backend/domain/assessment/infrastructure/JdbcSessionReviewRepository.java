package com.bigproject.backend.domain.assessment.infrastructure;

import com.bigproject.backend.domain.assessment.domain.ReviewModels.ReviewSource;
import com.bigproject.backend.domain.assessment.domain.ReviewModels.ExistingReview;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 다시 보기(REVIEW) 응시를 리포트에서 파생시켜 만든다.
 *
 * <h2>왜 세션 저장소와 나눴는가</h2>
 *
 * <p>{@link JdbcSessionRepository}는 <b>진행 중인 세션 하나</b>를 읽고 쓴다. 이쪽은 리포트·1차 응시·
 * 문제 단계를 가로질러 <b>새 응시를 만든다</b> — 읽는 테이블도 쓰는 테이블도 겹치지 않아, 한 클래스에
 * 두면 "지금 세션"과 "새 응시"의 SQL이 뒤섞인다.
 *
 * <h2>대상 판정은 한 곳에만 있다</h2>
 *
 * <p>{@link #REVIEW_TARGET_PROBLEMS}가 유일한 정의다. 세는 쿼리와 복사하는 쿼리가 같은 문자열을 쓰므로
 * "셀 때는 대상인데 복사에서는 빠지는" 어긋남이 생길 수 없다. 도달 단계 산식
 * ({@code MAX(axis) FILTER (status='PASSED')})은 리포트·매니저 지표와 같은 것이다 —
 * {@code assessment_problem.best_success_stage}는 미니프로젝트에서 항상 NULL이라 쓸 수 없다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcSessionReviewRepository {

	/**
	 * 다시 볼 문제. <b>도달 단계가 기준 미만인 문제</b>이며 원천은 {@code problem_stage}다.
	 *
	 * <p>파라미터는 (원본 세션 ID, 기준 단계) 둘이다. 통과한 축이 하나도 없으면 0단이므로
	 * {@code COALESCE}가 필요하다 — 미응시로 단계가 전부 {@code NOT_REACHED}인 문제도 대상이다.
	 */
	private static final String REVIEW_TARGET_PROBLEMS = """
			SELECT t.problem_id
			  FROM problem_stage t
			 WHERE t.session_id = ?
			 GROUP BY t.problem_id
			HAVING COALESCE(MAX(SUBSTRING(t.axis_code FROM 2)::int)
			                FILTER (WHERE t.status = 'PASSED'), 0) < ?
			""";

	private final JdbcTemplate jdbc;

	/**
	 * 다시 보기의 근거가 되는 리포트와 그 회차의 1차 응시·세션을 한 번에 읽는다.
	 *
	 * <p>발행되지 않은 리포트는 <b>없는 것으로 본다.</b> {@code lifecycle_status='ACTIVE'}이고
	 * {@code published_at}이 있을 때만 파생시킨다 — 발행 전 리포트로 다시 보기를 열면
	 * 학생이 결과를 보기도 전에 재응시가 시작된다.
	 *
	 * <p>종전 조건은 {@code trainee_release_status='RELEASED'}였다. 공개/비공개가 폐지되면서
	 * (2026-08-19) 발행이 곧 공개가 됐고 {@code published_at}이 그 자리를 물려받았다.
	 *
	 * <p>{@code report_snapshot}을 함께 잡는 이유는 DDL이 요구하기 때문이다
	 * ({@code ck_measurement_attempt_attempt_type_2}는 REVIEW에 리포트와 스냅샷을 <b>둘 다</b>
	 * NOT NULL로 요구한다). 활성 스냅샷이 없으면 이 조회가 비고, 그 리포트로는 다시 보기를 열 수 없다.
	 */
	public Optional<ReviewSource> findReviewSource(UUID reportId, UUID userId) {
		List<ReviewSource> found = jdbc.query("""
				SELECT r.report_id, rs.snapshot_id, r.assessment_round_id,
				       a.attempt_id AS source_attempt_id, a.status AS source_attempt_status,
				       s.session_id AS source_session_id
				  FROM report r
				  JOIN report_snapshot rs ON rs.report_id = r.report_id AND rs.is_active
				  JOIN measurement_attempt a ON a.assessment_round_id = r.assessment_round_id
				   AND a.user_id = r.user_id AND a.attempt_type = 'INITIAL'
				  LEFT JOIN assessment_session s ON s.attempt_id = a.attempt_id
				 WHERE r.report_id = ? AND r.user_id = ?
				   AND r.lifecycle_status = 'ACTIVE'
				   AND r.published_at IS NOT NULL
				""", (rs, rowNum) -> new ReviewSource(
						rs.getObject("report_id", UUID.class),
						rs.getObject("snapshot_id", UUID.class),
						rs.getObject("assessment_round_id", UUID.class),
						rs.getObject("source_attempt_id", UUID.class),
						rs.getString("source_attempt_status"),
						rs.getObject("source_session_id", UUID.class)),
				reportId, userId);
		return found.stream().findFirst();
	}

	/**
	 * 이 회차에 이미 만들어진 다시 보기. <b>회차당 한 번</b>이라 있으면 새로 만들지 않는다.
	 *
	 * <p>여러 건이 있을 수 없지만(정책상 한 번) 최신 하나만 본다 — 운영 작업으로 늘어난 경우에도
	 * 학생이 이어서 할 것은 마지막 것이다.
	 */
	public Optional<ExistingReview> findExistingReview(UUID assessmentRoundId, UUID userId) {
		List<ExistingReview> found = jdbc.query("""
				SELECT a.attempt_id, a.status AS attempt_status, s.session_id, s.status AS session_status
				  FROM measurement_attempt a
				  LEFT JOIN assessment_session s ON s.attempt_id = a.attempt_id
				 WHERE a.assessment_round_id = ? AND a.user_id = ? AND a.attempt_type = 'REVIEW'
				 ORDER BY a.attempt_sequence_no DESC
				 LIMIT 1
				""", (rs, rowNum) -> new ExistingReview(
						rs.getObject("attempt_id", UUID.class),
						rs.getString("attempt_status"),
						rs.getObject("session_id", UUID.class),
						rs.getString("session_status")),
				assessmentRoundId, userId);
		return found.stream().findFirst();
	}

	/** 다시 볼 문제가 몇 개인가. 0이면 만들 것이 없다 — 전부 기준 단계를 넘겼다는 뜻이다. */
	public int countReviewTargets(UUID sourceSessionId, int belowLevel) {
		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM (" + REVIEW_TARGET_PROBLEMS + ") targets",
				Integer.class, sourceSessionId, belowLevel);
		return count == null ? 0 : count;
	}

	/**
	 * 1차 응시를 원천으로 REVIEW 응시를 만든다.
	 *
	 * <p>식별 정보를 인자로 받지 않고 <b>원본 행에서 그대로 복사한다.</b> 기관·기수·프로젝트를 호출부가
	 * 다시 조립하면 1차와 다른 값을 가리키는 응시가 만들어질 수 있고, 그러면 같은 학생의 두 응시가
	 * 서로 다른 기수에 붙는다.
	 *
	 * <p>{@code source_submission_id}·{@code code_analysis_id}도 반드시 함께 복사한다 — 세션 조회가
	 * 코드 원문을 {@code submission.code_snippets}에서 {@code source_submission_id}로 찾기 때문에,
	 * 비면 다시 보기 화면의 코드 패널이 통째로 빈다.
	 *
	 * <p>{@code attempt_sequence_no}는 같은 회차·사용자의 최대값 + 1이다. 1차가 1이므로 첫 다시 보기는
	 * 보통 2가 된다.
	 */
	public UUID insertReviewAttempt(UUID sourceAttemptId, UUID assignedBy, UUID reportId,
			UUID snapshotId, Instant reviewDueAt) {
		return jdbc.queryForObject("""
				INSERT INTO measurement_attempt (
				    org_id, cohort_id, assessment_round_id, project_id, user_id,
				    source_submission_id, code_analysis_id,
				    attempt_type, source_attempt_id, attempt_sequence_no,
				    assigned_at, assigned_by, review_source_report_id, review_source_report_snapshot_id,
				    review_due_at, status, validity_review_status, analysis_completed_at,
				    assessment_open_at, assessment_close_at)
				SELECT a.org_id, a.cohort_id, a.assessment_round_id, a.project_id, a.user_id,
				       a.source_submission_id, a.code_analysis_id,
				       'REVIEW', a.attempt_id,
				       (SELECT COALESCE(MAX(x.attempt_sequence_no), 0) + 1
				          FROM measurement_attempt x
				         WHERE x.assessment_round_id = a.assessment_round_id AND x.user_id = a.user_id),
				       now(), ?, ?, ?,
				       ?, 'SESSION_READY', 'NOT_REQUIRED', a.analysis_completed_at,
				       now(), ?
				  FROM measurement_attempt a
				 WHERE a.attempt_id = ?
				RETURNING attempt_id
				""", UUID.class,
				assignedBy, reportId, snapshotId,
				timestamp(reviewDueAt), timestamp(reviewDueAt), sourceAttemptId);
	}

	/** 응시당 세션 1건({@code uq_assessment_session_attempt_id}). 커서는 비워 둔다 — {@code POST /start}가 세운다. */
	public UUID insertReviewSession(UUID attemptId) {
		return jdbc.queryForObject("""
				INSERT INTO assessment_session (org_id, attempt_id, status,
				    window_leave_count, total_away_seconds, connection_loss_count, total_disconnected_seconds)
				SELECT a.org_id, a.attempt_id, 'READY', 0, 0, 0, 0
				  FROM measurement_attempt a
				 WHERE a.attempt_id = ?
				RETURNING session_id
				""", UUID.class, attemptId);
	}

	/**
	 * 1차 세션의 단계를 새 세션으로 <b>그대로</b> 복사한다. 같은 문제·같은 질문·같은 힌트다.
	 *
	 * <p>질문을 다시 만들지 않는 이유는 정의서가 "지난번과 같은 질문"을 요구하기 때문이다. AI를 다시
	 * 부르면 문구가 달라져 1차와 도달 단계를 비교할 수 없게 되고(매니저 지표가 그 비교를 읽는다)
	 * 비용도 다시 나간다.
	 *
	 * <p>답변·점수·힌트 표시 시각은 복사하지 않는다. 새 슬롯은 전부 비어 있고 상태는 {@code PREPARED}다 —
	 * {@code ck_problem_stage_status_2}가 {@code PREPARED}에 아홉 슬롯이 모두 NULL일 것을 요구한다.
	 *
	 * <p>{@code source_problem_stage_id}로 원본을 가리켜 둔다. 1차와 다시 보기의 같은 축을 잇는 유일한
	 * 연결이며, 매니저 화면의 "0단 → 1단" 비교가 이 값을 따라간다.
	 *
	 * @return 복사된 단계 수. 대상 문제 하나당 4축이므로 보통 문제 수 × 4
	 */
	public int copyStagesForReview(UUID newSessionId, UUID sourceSessionId, int belowLevel) {
		return jdbc.update("""
				INSERT INTO problem_stage (session_id, problem_id, source_problem_stage_id, axis_code,
				    question_sequence_no, question_text, first_hint_text, second_hint_text, status,
				    created_at, updated_at, row_version, is_flagged)
				SELECT ?, ps.problem_id, ps.problem_stage_id, ps.axis_code, ps.question_sequence_no,
				       ps.question_text, ps.first_hint_text, ps.second_hint_text, 'PREPARED',
				       now(), now(), 0, FALSE
				  FROM problem_stage ps
				 WHERE ps.session_id = ?
				   AND ps.problem_id IN (""" + REVIEW_TARGET_PROBLEMS + """
				)
				""", newSessionId, sourceSessionId, sourceSessionId, belowLevel);
	}

	private static Timestamp timestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}
}
