package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.domain.ReportGenerationRun;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 리포트를 만들 대상 세션을 찾는다.
 *
 * <p>엔티티를 새로 만들지 않고 네이티브 조회로 두는 이유는 {@code AnalysisDispatchRepository}와 같다 —
 * 여기 필요한 것이 세션·응시·회차를 가로지르는 읽기 한 번뿐이라, 그 셋의 엔티티를 만들면 이 배치
 * 하나 때문에 매핑이 늘어난다.
 *
 * <h2>"응시했다"의 판정</h2>
 *
 * <p>{@code assessment_session.status='COMPLETED'}만으로는 부족하다. 세션은 끝났지만 응시 자체가
 * 무효로 확정될 수 있어서다. {@code assessment_round_attendance} 뷰가 쓰는 정의
 * ({@code measurement_attempt.status='COMPLETED'})를 그대로 따른다 — 화면이 `완료`로 세는 집합과
 * 리포트를 만드는 집합이 어긋나면, 완료로 보이는데 리포트가 없는 교육생이 생긴다.
 *
 * <h2>종료 사유 6종을 빼는 이유</h2>
 *
 * <p>{@code TERMINATED_AT_L1}~{@code L4}는 <b>빼지 않는다.</b> 규칙대로 조기 종료된 정상 세션이고,
 * 채점도 확정됐다. AI 응답 스펙에 {@code unreachedAxes}와 {@code retest}가 있는 것 자체가 그런
 * 세션에 리포트를 만들 것을 전제한 설계다. 리포트가 가장 필요한 대상이기도 하다.
 *
 * <p>빼는 것은 시간 초과·창 만료·데이터 무결성·관리자 무효화·기술적 실패 6종뿐이다.
 * 이쪽은 답변이 없거나 신뢰할 수 없어 서술을 만들 근거 자체가 없다.
 */
public interface ReportDispatchRepository extends Repository<ReportGenerationRun, UUID> {

	/**
	 * 리포트를 더 만들면 안 되는 실행이 이미 있는가.
	 *
	 * <p>막는 것: 진행 중(QUEUED·RUNNING) · 이미 끝남(COMPLETED·PARTIAL). <b>{@code FAILED}는 막지
	 * 않는다</b> — AI 서버 장애로 한 번 실패한 회차가 영구히 리포트 없이 남으면 복구 경로가 없다.
	 * 무한 재시도는 {@code ai.report.max-attempts}가 막는다.
	 *
	 * <p>⚠️ 이 상수를 이어 붙이는 쪽은 {@code AND NOT\s"""}처럼 <b>{@code \s}로 공백을 지켜야 한다.</b>
	 * 텍스트 블록은 줄 끝 공백을 지우므로 {@code AND NOT """}로 쓰면 {@code AND NOTEXISTS}가 되어
	 * 붙는다. 컴파일도 되고 단위 테스트도 통과하지만 실행하면 Postgres 문법 오류다.
	 */
	String BLOCKING_RUN_EXISTS = """
			EXISTS (
			    SELECT 1 FROM report rp
			      JOIN report_generation_run run ON run.report_id = rp.report_id
			     WHERE rp.user_id             = ma.user_id
			       AND rp.assessment_round_id = ma.assessment_round_id
			       AND rp.class_id IS NULL
			       AND run.status <> 'FAILED'
			)
			""";

	/**
	 * 이 회차·교육생에 허용하는 총 실행 횟수. 재시도가 아니라 <b>총합</b>이다(첫 시도 포함).
	 *
	 * <p>상한이 필요한 이유: AI 서버가 계속 죽어 있으면 배치가 돌 때마다 전 대상을 다시 요청한다.
	 * 리포트 1건이 문제 수만큼 LLM 호출이라 그 낭비가 작지 않다.
	 */
	String UNDER_ATTEMPT_LIMIT = """
			(SELECT count(*) FROM report rp2
			   JOIN report_generation_run run2 ON run2.report_id = rp2.report_id
			  WHERE rp2.user_id             = ma.user_id
			    AND rp2.assessment_round_id = ma.assessment_round_id
			    AND rp2.class_id IS NULL) < :maxAttempts
			""";

	/**
	 * 회차 종료 시각 이전에 응시해 정상 완료한 세션 중, 아직 리포트를 만들지 않은 것들.
	 *
	 * <p>배치 등록 시점은 {@code COALESCE(report_publish_not_before_at, assessment_due_at)}이다.
	 * 앞의 값은 운영자가 "이 시각 전에는 발행하지 않는다"로 정해 둔 것이고, DDL CHECK가
	 * {@code >= assessment_due_at}을 이미 보장하므로 COALESCE만으로 둘 다 존중된다.
	 *
	 * <p>정렬은 회차 종료가 이른 것부터다 — 밀린 회차가 있으면 오래된 쪽이 먼저 나가야 한다.
	 */
	@Query(value = """
			SELECT s.session_id           AS sessionId,
			       ma.attempt_id          AS attemptId,
			       ma.user_id             AS userId,
			       ma.org_id              AS orgId,
			       ma.cohort_id           AS cohortId,
			       ma.project_id          AS projectId,
			       ma.assessment_round_id AS assessmentRoundId,
			       ma.code_analysis_id    AS codeAnalysisId,
			       r.report_publish_not_before_at AS reportPublishNotBeforeAt
			  FROM assessment_session s
			  JOIN measurement_attempt ma
			    ON ma.attempt_id = s.attempt_id
			  JOIN project_assessment_round r
			    ON r.assessment_round_id = ma.assessment_round_id
			   AND r.deleted_at IS NULL
			 WHERE s.status  = 'COMPLETED'
			   AND ma.status = 'COMPLETED'
			   AND s.ended_at IS NOT NULL
			   AND r.assessment_due_at IS NOT NULL
			   AND s.ended_at < r.assessment_due_at
			   AND now() >= COALESCE(r.report_publish_not_before_at, r.assessment_due_at)
			   AND ma.validity_review_status <> 'CONFIRMED_INVALID'
			   AND (s.end_reason_code IS NULL OR s.end_reason_code NOT IN (
			           'POLICY_TIME_LIMIT_EXCEEDED', 'ASSESSMENT_WINDOW_EXPIRED',
			           'REVIEW_DUE_AT_EXPIRED', 'DATA_INTEGRITY_INVALID',
			           'ADMIN_INVALIDATED', 'TECHNICAL_FAILURE'))
			   AND NOT\s""" + BLOCKING_RUN_EXISTS + """
			   AND\s""" + UNDER_ATTEMPT_LIMIT + """
			 ORDER BY r.assessment_due_at, ma.user_id
			""", nativeQuery = true)
	List<ReportTarget> findDueSessions(int maxAttempts);

	/**
	 * 이 세션의 문제 목록. AI를 문제마다 부르므로 이 수만큼 item이 생긴다.
	 *
	 * <p>{@code problem_stage}에서 뽑는다 — {@code assessment_problem}에는 세션 축이 없고
	 * ({@code code_analysis} 단위로 만들어진다) 실제로 그 세션이 다룬 문제는 stage가 준비된 것뿐이다.
	 *
	 * <p>{@code problem_no}는 양쪽 CHECK가 모두 1~3이라({@code ck_assessment_problem_problem_no},
	 * {@code ck_report_generation_item_problem_no}) 그대로 옮겨도 안전하다.
	 *
	 * <p>AI에 {@code problemNo}를 반드시 넘겨야 한다 — 생략하면 AI가 1로 간주해서
	 * 2·3번 문제의 리포트가 모두 `문제 1`로 표시된다(AI 스키마 설명).
	 */
	@Query(value = """
			SELECT ps.problem_id AS problemId,
			       ap.problem_no AS problemNo
			  FROM problem_stage ps
			  JOIN assessment_problem ap ON ap.problem_id = ps.problem_id
			 WHERE ps.session_id = :sessionId
			 GROUP BY ps.problem_id, ap.problem_no
			 ORDER BY ap.problem_no, ps.problem_id
			""", nativeQuery = true)
	List<ProblemTarget> findSessionProblems(UUID sessionId);

	/**
	 * 실행 1건의 비용 귀속 축. 폴링이 {@code ai_usage}를 적재할 때 쓴다.
	 *
	 * <p>{@code report} 행에는 {@code project_id}가 없어({@code Report} 엔티티 참고) 회차를 거쳐
	 * 읽는다. 이 값이 비면 {@code attribution_status}가 {@code PARTIALLY_ALLOCATED}로 내려가
	 * 프로젝트별 원가 집계에서 그 호출이 빠진다.
	 */
	@Query(value = """
			SELECT rp.org_id     AS orgId,
			       rp.cohort_id  AS cohortId,
			       par.project_id AS projectId
			  FROM report_generation_run run
			  JOIN report rp ON rp.report_id = run.report_id
			  LEFT JOIN project_assessment_round par
			         ON par.assessment_round_id = rp.assessment_round_id
			 WHERE run.generation_run_id = :generationRunId
			""", nativeQuery = true)
	java.util.Optional<UsageContext> findUsageContext(UUID generationRunId);

	/** 대상 세션 1건의 맥락. AI 호출과 비용 귀속에 필요한 축을 함께 읽는다. */
	interface ReportTarget {
		UUID getSessionId();

		UUID getAttemptId();

		UUID getUserId();

		UUID getOrgId();

		UUID getCohortId();

		UUID getProjectId();

		UUID getAssessmentRoundId();

		/** 분석 결과({@code analysisDocuments})의 출처. 분석이 없으면 NULL이다. */
		UUID getCodeAnalysisId();

		java.time.Instant getReportPublishNotBeforeAt();
	}

	/** 세션이 다룬 문제 하나. */
	interface ProblemTarget {
		UUID getProblemId();

		/** 1~3. 범위 밖이거나 미지정이면 NULL이다. */
		Integer getProblemNo();
	}

	/** 사용량 원장의 귀속 축. */
	interface UsageContext {
		UUID getOrgId();

		UUID getCohortId();

		/** 회차가 지워졌으면 NULL이다. */
		UUID getProjectId();
	}
}
