package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.submission.presentation.dto.SubmissionAnalysisResultResponse.HeadCommit;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionAnalysisResultResponse.Problem;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionAnalysisResultResponse.RequirementResult;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionAnalysisResultResponse.Session;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 교육생 화면이 읽는 분석 결과 조회.
 *
 * <p>쓰기({@link JdbcAnalysisResultRepository})와 파일을 나눈 이유는 수명이 다르기 때문이다. 쓰기는
 * 분석 배치가 한 번 하고 끝이고, 읽기는 화면이 바뀔 때마다 모양이 달라진다. 한 클래스에 두면 화면
 * 요구가 바뀔 때마다 적재 코드가 있는 파일을 열게 된다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcAnalysisResultQueryRepository {

	/**
	 * 문제 1건이 갖는 단계 수. {@code ck_problem_stage_axis_code}의 L1~L4가 곧 이 값이고
	 * {@code problem_stage} 정의서가 "총 행 수는 generated_problem_count × 4"로 못박고 있다.
	 */
	private static final int STAGE_AXIS_COUNT = 4;

	private final JdbcTemplate jdbc;

	/**
	 * 이 교육생이 실제로 응시할 수 있는 상태인가. 분석은 성공했는데 세션·단계가 깔리지 않은 경우를
	 * 잡는다.
	 *
	 * <p><b>왜 필요한가.</b> {@code analysis_job}이 SUCCEEDED여도 세션 준비는 따로 실패할 수 있다 —
	 * 응시가 지워졌거나, 팀 배정이 끊겼거나, AI가 4축 질문·힌트를 온전히 주지 않아 문제가 통째로
	 * 스킵된 경우다. 그때 job 상태만 보고 "분석 완료"를 돌려주면 교육생은 시작할 수 없는 화면을
	 * 계속 새로고침하게 된다. job을 FAILED로 쓰지 않는 이유는 분석 자체는 실제로 성공했고 비용도
	 * 이미 나갔기 때문이다({@code JdbcAnalysisResultRepository} 참조) — 사실은 원장에 그대로 두고,
	 * <b>교육생에게 보이는 값만</b> 실패로 바꾼다.
	 *
	 * <p>기대치를 {@code GENERATED} 문제 수 × 4로 계산하는 이유: 근거를 못 찾아 3슬롯이 전부
	 * {@code NOT_GENERATED}면 단계가 0인 것이 정상이다. 0건 자체를 실패로 보면 그 정상 결과까지
	 * 실패로 뒤집는다.
	 *
	 * @return 세션이 있고 단계가 기대만큼 깔렸으면 {@code true}. 현행 분석이 없어도 {@code false}다.
	 */
	public boolean isSessionPrepared(UUID submissionId, UUID userId) {
		return jdbc.query("""
				SELECT (SELECT count(*) FROM assessment_problem p
				         WHERE p.code_analysis_id = ca.analysis_id
				           AND p.generation_status = 'GENERATED')      AS generated_count,
				       s.session_id                                    AS session_id,
				       (SELECT count(*) FROM problem_stage ps
				         WHERE ps.session_id = s.session_id)           AS stage_count
				  FROM code_analysis ca
				  LEFT JOIN measurement_attempt a ON a.code_analysis_id = ca.analysis_id AND a.user_id = ?
				  LEFT JOIN assessment_session s ON s.attempt_id = a.attempt_id
				 WHERE ca.source_submission_id = ? AND ca.status = 'ACTIVE'
				""",
				(rs, rowNum) -> rs.getObject("session_id", UUID.class) != null
						&& rs.getInt("stage_count") == rs.getInt("generated_count") * STAGE_AXIS_COUNT,
				userId, submissionId).stream().findFirst().orElse(false);
	}

	/** 제출의 현행 분석 1건. {@code uq_code_analysis_active}가 ACTIVE 유일성을 보장한다. */
	public Optional<AnalysisSummary> findActiveAnalysis(UUID submissionId) {
		return jdbc.query("""
				SELECT analysis_id, applied_scope_code, scope_fallback, fallback_reason,
				       resolved_branch, head_commit_sha, head_commit_message, head_commit_committed_at,
				       created_at
				  FROM code_analysis
				 WHERE source_submission_id = ? AND status = 'ACTIVE'
				""", JdbcAnalysisResultQueryRepository::mapSummary, submissionId).stream().findFirst();
	}

	/**
	 * 문제 슬롯. 코드 원문은 {@code submission.code_snippets}에 있고 {@code source_snippet_key}로 잇는다.
	 *
	 * <p>🔴 JSON 키 이름({@code snippetKey}·{@code code})은 적재하는 쪽과 <b>같은 이름이어야 한다.</b>
	 * JSONB라 스키마가 강제되지 않아 어긋나도 에러 없이 null이 된다.
	 */
	public List<Problem> findProblems(UUID submissionId) {
		return jdbc.query("""
				SELECT p.problem_no, p.generation_status, p.not_generated_reason_detail,
				       p.title, p.problem_type, p.code_language, p.source_path,
				       p.source_line_start, p.source_line_end, sn.value ->> 'code' AS code_snippet
				  FROM code_analysis ca
				  JOIN assessment_problem p ON p.code_analysis_id = ca.analysis_id
				  JOIN submission s ON s.submission_id = ca.source_submission_id
				  LEFT JOIN LATERAL jsonb_array_elements(coalesce(s.code_snippets, '[]'::jsonb)) sn(value)
				    ON sn.value ->> 'snippetKey' = p.source_snippet_key
				 WHERE ca.source_submission_id = ? AND ca.status = 'ACTIVE'
				 ORDER BY p.problem_no
				""", (rs, rowNum) -> new Problem(
						rs.getInt("problem_no"),
						rs.getString("generation_status"),
						rs.getString("not_generated_reason_detail"),
						rs.getString("title"),
						rs.getString("problem_type"),
						rs.getString("code_language"),
						rs.getString("source_path"),
						nullableInt(rs, "source_line_start"),
						nullableInt(rs, "source_line_end"),
						rs.getString("code_snippet")),
				submissionId);
	}

	/** 요구사항 판정. 같은 요구사항이 여러 번 판정됐으면 최신 버전만 보여 준다. */
	public List<RequirementResult> findRequirementResults(UUID submissionId) {
		return jdbc.query("""
				SELECT r.requirement_key, r.title, a.result, a.evidence_summary,
				       a.assessed_by IS NULL AS judged_by_ai
				  FROM code_analysis ca
				  JOIN project_requirement_assessment a ON a.analysis_id = ca.analysis_id
				  JOIN project_requirement r ON r.requirement_id = a.requirement_id
				 WHERE ca.source_submission_id = ? AND ca.status = 'ACTIVE'
				   AND a.assessment_version = (
				       SELECT max(v.assessment_version) FROM project_requirement_assessment v
				        WHERE v.requirement_id = a.requirement_id
				          AND v.assessment_round_id = a.assessment_round_id
				          AND v.team_id = a.team_id)
				 ORDER BY r.sequence_no
				""", (rs, rowNum) -> new RequirementResult(
						rs.getString("requirement_key"),
						rs.getString("title"),
						rs.getString("result"),
						rs.getString("evidence_summary"),
						rs.getBoolean("judged_by_ai")),
				submissionId);
	}

	/**
	 * 조회한 교육생 <b>본인</b>의 세션.
	 *
	 * <p>제출은 팀 단위지만 응시는 개인 단위다. 여기서 사용자를 걸러 내지 않으면 팀원 아무의 세션이나
	 * 돌려주게 되고, 화면이 그 {@code sessionId}로 응시를 시작하면 남의 시험을 푸는 것이 된다.
	 */
	public Optional<Session> findMySession(UUID submissionId, UUID userId) {
		return jdbc.query("""
				SELECT s.session_id, s.status, count(ps.problem_stage_id) AS stage_count
				  FROM code_analysis ca
				  JOIN measurement_attempt a ON a.code_analysis_id = ca.analysis_id AND a.user_id = ?
				  JOIN assessment_session s ON s.attempt_id = a.attempt_id
				  LEFT JOIN problem_stage ps ON ps.session_id = s.session_id
				 WHERE ca.source_submission_id = ? AND ca.status = 'ACTIVE'
				 GROUP BY s.session_id, s.status
				""", (rs, rowNum) -> new Session(
						rs.getObject("session_id", UUID.class),
						rs.getString("status"),
						rs.getInt("stage_count")),
				userId, submissionId).stream().findFirst();
	}

	private static AnalysisSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
		Timestamp committedAt = rs.getTimestamp("head_commit_committed_at");
		String sha = rs.getString("head_commit_sha");
		return new AnalysisSummary(
				rs.getObject("analysis_id", UUID.class),
				rs.getString("applied_scope_code"),
				rs.getBoolean("scope_fallback"),
				rs.getString("fallback_reason"),
				rs.getString("resolved_branch"),
				sha == null ? null : new HeadCommit(sha, rs.getString("head_commit_message"),
						committedAt == null ? null : committedAt.toInstant()),
				rs.getTimestamp("created_at").toInstant());
	}

	private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	public record AnalysisSummary(
			UUID analysisId,
			String appliedScope,
			boolean scopeFallback,
			String fallbackReason,
			String resolvedBranch,
			HeadCommit headCommit,
			Instant analyzedAt
	) {
	}
}
