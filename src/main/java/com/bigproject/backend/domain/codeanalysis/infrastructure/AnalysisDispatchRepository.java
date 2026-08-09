package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 분석 대상 제출을 찾는다.
 *
 * <p>엔티티를 새로 만들지 않고 네이티브 조회로 두는 이유는, 여기 필요한 것이 회차·팀·제출을 가로지르는
 * 읽기 한 번뿐이기 때문이다. 회차 엔티티를 만들면 이 배치 하나 때문에 매핑이 늘어난다.
 *
 * <p>주 트리거는 {@link #findDispatchTarget}이다(2026-08-07). GitHub 제출이 접수되면 즉시 분석을
 * 걸어, 마감 후에야 저장소 URL 오타를 알게 되는 사고를 막는다.
 * {@link #findRetryableSubmissions}는 <b>안전망 겸 재시도</b>다 — 이벤트 유실로 트리거를 놓친 제출과,
 * 일시적 실패로 끝난 제출을 다시 집는다.
 *
 * <h2>"이미 처리됨"의 판정 (2026-08-09 수정)</h2>
 *
 * <p>종전에는 두 쿼리 모두 "{@code analysis_job} 행이 하나라도 있으면 제외"였다. 그래서
 * <b>AI 서버가 잠깐 죽어 있는 동안 접수된 제출은 영구히 분석되지 않았다</b> — 요청이 거절돼 FAILED
 * 행이 남는 순간 두 쿼리 모두 그 제출을 다시는 집지 않았고, 마감이 지나면
 * {@code SUBMISSION_DEADLINE_PASSED} 때문에 재제출로도 복구할 수 없었다.
 *
 * <p>이제는 <b>어떤 실패였는지</b>로 가른다. 아래 {@link #BLOCKING_JOB_EXISTS}가 그 판정이고
 * 두 쿼리가 같은 문자열을 공유한다 — 한쪽만 고치면 "이벤트로는 걸리는데 안전망으로는 안 걸리는"
 * 상태가 되어 원인 추적이 어려워진다.
 */
public interface AnalysisDispatchRepository extends Repository<AnalysisJob, UUID> {

	/**
	 * 분석을 더 걸면 안 되는 job이 이미 있는가.
	 *
	 * <p>막는 것: 진행 중(QUEUED·RUNNING) · 이미 끝남(SUCCEEDED·PARTIAL) · <b>재시도해도 소용없는
	 * 실패</b>. 마지막 묶음은 저장소 URL이 틀렸거나 제출물 자체가 문제인 경우라, 같은 제출을 다시
	 * 분석해 봐야 결과가 같다. 그쪽의 복구 경로는 재시도가 아니라 교육생의 재제출이고, 재제출은
	 * 새 {@code submission} 행을 만들므로 분석이 자연히 다시 걸린다.
	 *
	 * <p>🔴 여기 나열한 4종은 {@code AnalysisFailureCode.retryable()}과 <b>같은 집합이어야 한다.</b>
	 * SQL에 enum을 넣을 수 없어 문자열이 중복돼 있다.
	 *
	 * <p>⚠️ 이 상수를 이어 붙이는 쪽은 {@code AND NOT\s"""}처럼 <b>{@code \s}로 공백을 지켜야 한다.</b>
	 * 텍스트 블록은 줄 끝 공백을 지우므로 {@code AND NOT """}로 쓰면 {@code AND NOTEXISTS}가 되어
	 * 붙는다. 컴파일도 되고 단위 테스트도 통과하지만 실행하면 Postgres 문법 오류다.
	 */
	String BLOCKING_JOB_EXISTS = """
			EXISTS (
			    SELECT 1 FROM analysis_job j
			     WHERE j.submission_id = s.submission_id
			       AND (j.status <> 'FAILED'
			            OR j.failure_code NOT IN ('TEMPORARY_ERROR', 'ANALYSIS_TIMEOUT',
			                                      'MODEL_ERROR', 'SOURCE_UNREACHABLE'))
			)
			""";

	/**
	 * 재시도 상한. 시도 횟수는 그 제출의 {@code analysis_job} 행 수로 센다.
	 *
	 * <p>상한이 없으면 AI 서버가 계속 죽어 있는 동안 안전망이 돌 때마다 전 제출을 다시 요청한다.
	 * 분석 1회가 LLM 호출 20여 건이라 그 낭비가 작지 않다.
	 */
	String UNDER_ATTEMPT_LIMIT = """
			(SELECT count(*) FROM analysis_job a
			  WHERE a.submission_id = s.submission_id) < :maxAttempts
			""";

	/**
	 * 방금 접수된 제출 하나의 분석 대상 컨텍스트. 제출 완료 이벤트가 이 메서드로 호출된다.
	 *
	 * <p>마감을 보지 않는다 — 마감 전 재제출마다 즉시 분석하는 것이 이 트리거의 목적이다.
	 *
	 * <p>결과가 비어 있을 수 있다. 이벤트가 중복 발행됐거나, 도착하기 전에 팀원이 다시 제출해
	 * {@code is_current}가 다른 행으로 넘어갔거나, 안전망이 먼저 처리했을 수 있다.
	 * 셋 다 오류가 아니라 "이미 처리됨"이다.
	 */
	@Query(value = """
			SELECT s.submission_id      AS submissionId,
			       s.org_id             AS orgId,
			       s.team_id            AS teamId,
			       s.assessment_round_id AS assessmentRoundId,
			       s.method             AS method,
			       s.requested_branch   AS requestedBranch,
			       r.repo_url           AS repositoryUrl,
			       art.storage_uri      AS artifactStorageUri,
			       art.original_file_name AS artifactFileName
			  FROM submission s
			  LEFT JOIN repository r ON r.repository_id = s.repository_id
			  LEFT JOIN submission_artifact art ON art.submission_id = s.submission_id
			 WHERE s.submission_id = :submissionId
			   AND s.is_current    = TRUE
			   AND s.status        = 'ACCEPTED'
			   AND NOT\s""" + BLOCKING_JOB_EXISTS + """
			   AND\s""" + UNDER_ATTEMPT_LIMIT, nativeQuery = true)
	Optional<DispatchTarget> findDispatchTarget(@Param("submissionId") UUID submissionId,
			@Param("maxAttempts") int maxAttempts);

	/**
	 * 안전망 겸 재시도: 아직 분석이 성사되지 않은 현재 제출들.
	 *
	 * <p><b>마감 조건을 걸지 않는다(2026-08-09).</b> 종전에는 {@code submission_due_at <= now}였는데,
	 * 그러면 마감이 며칠 뒤인 회차에서 일시적 실패가 난 제출이 <b>마감 때까지 방치</b>된다. 그동안
	 * 교육생 화면에는 실패 상태가 그대로 보인다. 분석 트리거가 "마감 후 배치"에서 "제출 즉시"로
	 * 바뀐 시점(2026-08-07)에 함께 걷어냈어야 할 조건이다.
	 *
	 * <p>{@code project_assessment_round} 조인은 정렬을 위해서만 남긴다 — 마감이 임박한 회차부터
	 * 처리해야 늦어도 마감 직후에는 결과가 있다.
	 *
	 * <p>{@code status='ACCEPTED'}만 고른다. ZIP 제출도 2026-08-09부터 접수 즉시 ACCEPTED다 --
	 * 내용 검증(EMPTY_CODE·GIT_LOG_MISSING)의 주체가 AI로 확정돼 백엔드가 더 볼 것이 없다.
	 */
	@Query(value = """
			SELECT s.submission_id      AS submissionId,
			       s.org_id             AS orgId,
			       s.team_id            AS teamId,
			       s.assessment_round_id AS assessmentRoundId,
			       s.method             AS method,
			       s.requested_branch   AS requestedBranch,
			       r.repo_url           AS repositoryUrl,
			       art.storage_uri      AS artifactStorageUri,
			       art.original_file_name AS artifactFileName
			  FROM submission s
			  JOIN project_assessment_round pr
			    ON pr.assessment_round_id = s.assessment_round_id
			   AND pr.deleted_at IS NULL
			  LEFT JOIN repository r ON r.repository_id = s.repository_id
			  LEFT JOIN submission_artifact art ON art.submission_id = s.submission_id
			 WHERE s.is_current = TRUE
			   AND s.status     = 'ACCEPTED'
			   AND NOT\s""" + BLOCKING_JOB_EXISTS + """
			   AND\s""" + UNDER_ATTEMPT_LIMIT + """
			 ORDER BY pr.submission_due_at, s.team_id
			""", nativeQuery = true)
	List<DispatchTarget> findRetryableSubmissions(@Param("maxAttempts") int maxAttempts);

	/**
	 * 이 제출의 가장 큰 {@code execution_no}. 재시도 job의 번호를 이어 붙이는 데 쓴다.
	 *
	 * <p>행이 없으면 결과가 {@code null}이다({@code max()}는 빈 집합에 NULL을 준다) —
	 * 호출부가 첫 실행 1로 시작한다. {@code ck_analysis_job_execution_no}가 0 이하를 막는다.
	 */
	@Query(value = """
			SELECT max(j.execution_no) FROM analysis_job j
			 WHERE j.submission_id = :submissionId
			""", nativeQuery = true)
	Integer findMaxExecutionNo(@Param("submissionId") UUID submissionId);

	interface DispatchTarget {
		UUID getSubmissionId();

		UUID getOrgId();

		UUID getTeamId();

		UUID getAssessmentRoundId();

		String getMethod();

		String getRequestedBranch();

		/** ZIP 제출은 repository 행이 없어 NULL이다. */
		String getRepositoryUrl();

		/**
		 * ZIP 본체의 저장 위치. GitHub 제출은 artifact 행이 없어 NULL이다.
		 * {@code uq_submission_artifact_submission_id} 덕분에 제출당 최대 1건이라 조인이 행을 늘리지 않는다.
		 *
		 * <p>별칭이 {@code art}인 이유: {@link #UNDER_ATTEMPT_LIMIT}의 서브쿼리가 {@code analysis_job a}로
		 * 이미 {@code a}를 쓴다. 겹쳐도 서브쿼리 쪽이 가려 동작은 하지만, 읽는 사람이 바깥 조인을
		 * 가리킨다고 오해하기 쉽다.
		 */
		String getArtifactStorageUri();

		/** 업로드 당시 파일 이름. multipart 로 실어 보낼 때 파트 파일명으로 쓴다. */
		String getArtifactFileName();
	}
}
