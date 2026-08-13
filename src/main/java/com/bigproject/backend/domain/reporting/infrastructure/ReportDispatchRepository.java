package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.domain.ReportGenerationRun;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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
	 * <p>🔴 <b>여기에는 {@code trigger_type} 조건을 넣지 않는다.</b> {@link #UNDER_ATTEMPT_LIMIT}에는
	 * 넣었는데 여기 없는 것이 실수처럼 보이지만 의도한 것이다 — 두 조건은 서로 다른 질문에 답한다.
	 *
	 * <table>
	 *   <caption>두 조건의 역할</caption>
	 *   <tr><th>조건</th><th>답하는 질문</th><th>{@code trigger_type}</th><th>이유</th></tr>
	 *   <tr>
	 *     <td>{@code UNDER_ATTEMPT_LIMIT}</td><td>"얼마나까지"</td><td>✅ {@code = 'SCHEDULED'}</td>
	 *     <td>상한은 사람 판단 없는 5분 주기 반복을 막는 것. 수동 재생성이 계수되면
	 *         상한을 소진한 대상을 <b>고치라고 만든 도구가 고치지 못한다</b></td>
	 *   </tr>
	 *   <tr>
	 *     <td>{@code BLOCKING_RUN_EXISTS}</td><td>"지금 걸어도 되나"</td><td>❌ 넣지 않음</td>
	 *     <td>수동 재생성이 {@code QUEUED}/{@code RUNNING}이거나 성공했으면 배치가 끼어들면 안 된다.
	 *         넣으면 <b>사람이 돌리는 중에 배치가 같은 대상을 또 건다</b></td>
	 *   </tr>
	 * </table>
	 *
	 * <p>"재생성은 상한에서 뺀다"는 원칙을 반사적으로 양쪽에 적용하는 것이 이 코드에서 가장 하기 쉬운
	 * 실수다. 수동 경로가 이 조건을 넘어서는 방법은 조건을 고치는 게 아니라
	 * <b>이 질의를 타지 않는 것</b>이다({@link #findTargetBySession}).
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
	 *
	 * <p><b>운영자 수동 재생성({@code USER_REQUESTED})은 세지 않는다.</b> 상한이 막으려는 것은 사람의
	 * 판단이 없는 반복인데, 수동 재생성은 운영자가 대상을 특정하고 누른 것이라 폭주하지 않는다.
	 * 더 결정적으로, 이 도구의 목적이 <b>상한을 소진해 리포트가 빈 대상을 구제하는 것</b>이라
	 * 계수하면 목적과 모순된다 — 고쳐야 할 대상에서만 안 듣는 도구가 된다.
	 *
	 * <p>대신 상한이 사람에게 옮겨간다. 같은 대상을 반복해서 누르고 있으면 그건 AI 쪽 문제이므로
	 * {@code ReportBatchService.regenerateSession}이 <b>누가·언제·몇 번째인지</b>를 로그로 남긴다.
	 *
	 * <p>{@link #BLOCKING_RUN_EXISTS}에는 같은 조건을 <b>넣지 않는다</b> — 이유는 그쪽 javadoc의 표.
	 */
	/**
	 * 아직 진행 중인 단계가 남아 있는 세션인가. <b>남아 있으면 리포트를 만들지 않는다.</b>
	 *
	 * <h2>왜 세션 상태만으로는 모자란가</h2>
	 *
	 * <p>대상 선별은 {@code assessment_session.status='COMPLETED'}를 보는데, 그것과 개별
	 * {@code problem_stage}의 정리는 다른 사건이다. 세션이 끝났다고 표시됐어도 stage가
	 * {@code PREPARED}·{@code IN_PROGRESS}로 남아 있을 수 있다 — 세션 종료 로직이 남은 단계를
	 * {@code NOT_REACHED}/{@code NOT_ANSWERED}로 정리한다는 <b>전제</b>에 기대고 있을 뿐이다
	 * (Assessment 지속 의무 2번).
	 *
	 * <h2>정리가 안 된 채로 보내면 두 곳이 동시에 틀린다</h2>
	 *
	 * <ol>
	 *   <li><b>AI</b> — {@code transcript[].status}를 <b>그대로 사용한다</b>(2026-08-10 AI 확인).
	 *       "아직 진행 중"이 리포트에 그대로 찍힌다</li>
	 *   <li><b>대표 축 판정</b> — {@code JdbcReportPayloadRepository.findConceptContext}의 미통과
	 *       집합에 그 둘이 없어 {@code block_level}이 NULL이 되고 <b>L4가 대표 축</b>이 된다.
	 *       학생이 도달조차 못 한 축을 "여기서 막혔다"고 보여주게 된다(한계 4)</li>
	 * </ol>
	 *
	 * <h2>안 보내는 쪽을 택한 이유</h2>
	 *
	 * <p>보내면 <b>틀린 리포트가 학생에게 가고</b>, 안 보내면 대상에서 빠져 로그로 드러난다.
	 * 뒤쪽은 고칠 수 있는 실패다. 다만 정리가 영영 안 되면 그 세션은 리포트가 없는 채로 남으므로,
	 * {@code ReportBatchService}가 <b>미정리 세션 수를 경고로 남긴다</b> — 조용히 묻히면 안 된다.
	 *
	 * <p>⚠️ {@link #BLOCKING_RUN_EXISTS}·{@link #UNDER_ATTEMPT_LIMIT}와 성격이 다르다. 그 둘은
	 * "얼마나 자주"를 막는 <b>조절기</b>라 운영자 수동 재생성이 넘어가지만, 이것은 "만들어도 되는
	 * 데이터인가"를 보는 <b>유효성 규칙</b>이다. 그래서 {@link #findTargetBySession}에도 그대로 있다.
	 */
	String NO_UNFINISHED_STAGE = """
			NOT EXISTS (
			    SELECT 1 FROM problem_stage ps2
			     WHERE ps2.session_id = s.session_id
			       AND ps2.status IN ('PREPARED', 'IN_PROGRESS')
			)
			""";

	String UNDER_ATTEMPT_LIMIT = """
			(SELECT count(*) FROM report rp2
			   JOIN report_generation_run run2 ON run2.report_id = rp2.report_id
			  WHERE rp2.user_id             = ma.user_id
			    AND rp2.assessment_round_id = ma.assessment_round_id
			    AND rp2.class_id IS NULL
			    AND run2.trigger_type       = 'SCHEDULED') < :maxAttempts
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
			   AND\s""" + NO_UNFINISHED_STAGE + """
			   AND NOT\s""" + BLOCKING_RUN_EXISTS + """
			   AND\s""" + UNDER_ATTEMPT_LIMIT + """
			 ORDER BY r.assessment_due_at, ma.user_id
			""", nativeQuery = true)
	List<ReportTarget> findDueSessions(int maxAttempts);

	/**
	 * 끝난 <b>문제</b>를 하나씩 집는다. 리포트 생성의 기본 경로다.
	 *
	 * <h2>왜 세션이 아니라 문제 단위인가</h2>
	 *
	 * <p>학생이 문제 1을 끝내고 문제 2로 넘어가는 동안 문제 1의 리포트를 만들어 두면, 마지막 문제가
	 * 끝났을 때 앞의 것들이 이미 준비돼 있다. 회차 마감 후 3개를 몰아 만들면 그만큼 기다린다 —
	 * AI 명세가 원래 의도한 방식이기도 하다.
	 *
	 * <p>{@link #findDueSessions}와 달리 <b>세션이 끝나기를 기다리지 않는다.</b> 그래서 조건이 둘
	 * 갈린다.
	 *
	 * <table>
	 *   <caption>두 조회의 차이</caption>
	 *   <tr><th></th><th>{@code findDueSessions}</th><th>이 조회</th></tr>
	 *   <tr><td>단위</td><td>세션</td><td><b>문제</b></td></tr>
	 *   <tr><td>시점</td><td>회차 마감 후</td><td><b>그 문제가 끝나는 즉시</b></td></tr>
	 *   <tr><td>세션 완료</td><td>필수</td><td><b>안 봄</b> — 아직 진행 중이다</td></tr>
	 *   <tr><td>마감 판정</td><td>{@code ended_at &lt; due_at}</td><td><b>{@code started_at &lt; due_at}</b></td></tr>
	 * </table>
	 *
	 * <h2>🔴 마감 기준이 "끝냈나"에서 "시작했나"로 바뀐다</h2>
	 *
	 * <p>{@code s.started_at < r.assessment_due_at} — <b>마감 전에 시작만 했으면</b> 그 세션의 모든
	 * 문제가 리포트를 받는다. 마감에 걸쳐 푸는 학생이 마지막 문제만 리포트를 못 받는 상황을 막는다
	 * (2026-08-11 결정).
	 *
	 * <h2>유효성은 여기서 다 보지 않는다</h2>
	 *
	 * <p>세션이 끝나기 전에 만들기 때문에 <b>세션 완료·종료 사유·최종 유효성을 이 시점엔 알 수 없다.</b>
	 * 이미 무효로 확정된 것({@code CONFIRMED_INVALID})만 거르고, 나머지 판정은
	 * {@code ReportRunFinalizer}가 <b>발행 직전에</b> 한다.
	 *
	 * <p>그래서 <b>무효로 끝난 세션의 리포트가 만들어질 수 있다</b> — 그 LLM 비용은 회수되지 않는다.
	 * 대신 발행되지 않고, {@code report_generation_run} 행이 남아 "왜 이 학생만 리포트가 없나"의
	 * 근거가 된다(2026-08-11 ⓐ안).
	 *
	 * <h2>🔴 인덱스 전제</h2>
	 *
	 * <p>{@code report_generation_run(report_id)} 인덱스가 있어야 한다. 없으면 판정마다
	 * 그 테이블을 통째로 훑어 <b>31초</b>가 걸린다(시드 기준 실측). 인덱스가 있으면 <b>527ms</b>다.
	 * FK만으로는 인덱스가 생기지 않고, {@code uq_report_generation_run_active}는 부분 인덱스라
	 * 종료된 run 조회에 쓰이지 않는다.
	 *
	 * @return 문제 1건이 행 1개. 같은 세션의 문제 여러 개가 함께 나올 수 있다
	 */
	@Query(value = """
			SELECT DISTINCT
			       s.session_id           AS sessionId,
			       ma.attempt_id          AS attemptId,
			       ma.user_id             AS userId,
			       ma.org_id              AS orgId,
			       ma.cohort_id           AS cohortId,
			       ma.project_id          AS projectId,
			       ma.assessment_round_id AS assessmentRoundId,
			       ma.code_analysis_id    AS codeAnalysisId,
			       r.report_publish_not_before_at AS reportPublishNotBeforeAt,
			       ps.problem_id          AS problemId,
			       ap.problem_no          AS problemNo
			  FROM problem_stage ps
			  JOIN assessment_session s
			    ON s.session_id = ps.session_id
			  JOIN measurement_attempt ma
			    ON ma.attempt_id = s.attempt_id
			  JOIN project_assessment_round r
			    ON r.assessment_round_id = ma.assessment_round_id
			   AND r.deleted_at IS NULL
			  JOIN assessment_problem ap
			    ON ap.problem_id = ps.problem_id
			 WHERE s.started_at IS NOT NULL
			   AND r.assessment_due_at IS NOT NULL
			   AND s.started_at < r.assessment_due_at
			   AND ma.validity_review_status <> 'CONFIRMED_INVALID'
			   AND NOT EXISTS (
			       SELECT 1 FROM problem_stage ps2
			        WHERE ps2.session_id = ps.session_id
			          AND ps2.problem_id = ps.problem_id
			          AND ps2.status IN ('PREPARED', 'IN_PROGRESS')
			   )
			   AND NOT EXISTS (
			       SELECT 1 FROM report_generation_item it
			         JOIN report_generation_run run ON run.generation_run_id = it.generation_run_id
			         JOIN report rp                 ON rp.report_id = run.report_id
			        WHERE it.problem_id          = ps.problem_id
			          AND rp.user_id             = ma.user_id
			          AND rp.assessment_round_id = ma.assessment_round_id
			          AND rp.class_id IS NULL
			          AND run.status <> 'FAILED'
			   )
			   AND\s""" + UNDER_ATTEMPT_LIMIT + """
			 ORDER BY ma.user_id, ap.problem_no
			""", nativeQuery = true)
	List<ProblemDueTarget> findDueProblems(int maxAttempts);

	/**
	 * 세션 1건의 맥락. <b>운영자 수동 재생성</b>이 쓴다.
	 *
	 * <h2>왜 별도 질의인가</h2>
	 *
	 * <p>{@link #findDueSessions}와 SELECT는 같지만 <b>{@link #BLOCKING_RUN_EXISTS}와
	 * {@link #UNDER_ATTEMPT_LIMIT}를 타지 않는다.</b> 그 둘이 막고 있는 대상을 푸는 것이 이 경로의
	 * 목적이기 때문이다 — 조건 안에서 예외를 만들면 배치 경로까지 헐거워진다.
	 *
	 * <p>수동 재생성이 실제로 필요해지는 두 경우가 모두 그 조건에 막혀 있다.
	 * <ul>
	 *   <li><b>한계 1</b> — {@code max-attempts}를 소진한 대상({@code UNDER_ATTEMPT_LIMIT})</li>
	 *   <li><b>한계 7</b> — 문제 1개만 실패해 {@code PARTIAL}로 닫힌 run. {@code BLOCKING_RUN_EXISTS}가
	 *       {@code status <> 'FAILED'}라 <b>상한과 무관하게</b> 영구히 다시 집히지 않는다.
	 *       상한 3회 소진보다 훨씬 흔하다</li>
	 * </ul>
	 *
	 * <h2>🔴 무엇을 빼지 <b>않는가</b></h2>
	 *
	 * <p>두 조건만 빼고 <b>유효성 규칙은 그대로 둔다</b> — 세션·응시 완료, 무효 응시 제외,
	 * 종료 사유 6종 제외, 그리고 <b>발행 예정 시각</b>({@code report_publish_not_before_at})까지.
	 * 운영자가 누른다고 아직 안 끝난 세션이나 무효 응시로 리포트를 만들 이유는 없고, 발행 시각을
	 * 넘기면 확정 트랜잭션이 {@code report.publish()}까지 하므로 <b>정해 둔 시각보다 먼저 발행된다.</b>
	 * 이 경로가 푸는 것은 "얼마나 자주"이지 "누구를"이 아니다.
	 *
	 * @return 대상이 없으면 빈 값. <b>세션이 없어서인지 조건에 안 맞아서인지 구분되지 않으므로</b>
	 *         호출부는 "재생성할 수 없는 세션"으로만 다뤄야 한다.
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
			 WHERE s.session_id = :sessionId
			   AND s.status  = 'COMPLETED'
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
			   AND\s""" + NO_UNFINISHED_STAGE + """
			""", nativeQuery = true)
	java.util.Optional<ReportTarget> findTargetBySession(UUID sessionId);

	/**
	 * 정리되지 않은 stage 때문에 대상에서 빠진 세션 수. 경고에만 쓴다.
	 *
	 * <p>{@link #NO_UNFINISHED_STAGE}가 조용히 걸러 버리면 "리포트가 왜 안 생기지"를 되짚을
	 * 단서가 없다. 대상 조회와 같은 유효성 규칙을 적용한 뒤 <b>그 조건 하나만 뒤집어</b> 센다 —
	 * 이미 리포트를 만든 세션까지 세면 숫자가 늘 커서 신호가 되지 않으므로 실행 게이트도 함께 본다.
	 */
	@Query(value = """
			SELECT count(*)
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
			   AND NOT\s""" + NO_UNFINISHED_STAGE + """
			   AND NOT\s""" + BLOCKING_RUN_EXISTS + """
			""", nativeQuery = true)
	long countSessionsWithUnfinishedStages();

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

	/**
	 * 확정 직전에 필요한 세 가지를 한 번에 읽는다.
	 *
	 * <h2>왜 확정 시점에 다시 보나</h2>
	 *
	 * <p>문제 단위 dispatch는 <b>세션이 끝나기 전에</b> 요청을 보낸다({@link #findDueProblems}).
	 * 그래서 세션 완료·종료 사유·최종 유효성을 그 시점엔 알 수 없다. 그 판정을 여기로 미룬다.
	 *
	 * <ul>
	 *   <li>{@code eligible} — 세션·응시가 정상 완료됐고 무효가 아닌가.
	 *       거짓이면 <b>스냅샷은 만들되 발행하지 않는다</b></li>
	 *   <li>{@code publishNotBeforeAt} — 이 시각 전에는 발행하지 않는다.
	 *       종전에는 대상 조회가 "그때까지 만들지 않음"으로 지켰는데, 즉시 생성으로 바뀌면서
	 *       <b>지킬 곳이 여기밖에 없다</b></li>
	 *   <li>{@code problemCount} — 이 세션이 다룬 문제 수.
	 *       🔴 <b>item이 이 수만큼 모여야 확정한다</b> — 안 그러면 문제 1개만 끝났을 때
	 *       "item 전부 종료"로 읽혀 <b>개념 카드 1장짜리 리포트가 발행된다</b></li>
	 * </ul>
	 *
	 * <p>{@code problemCount}는 {@link #findSessionProblems}와 같은 기준으로 센다
	 * ({@code problem_stage}의 서로 다른 {@code problem_id} 수).
	 */
	@Query(value = """
			SELECT (s.status = 'COMPLETED'
			        AND ma.status = 'COMPLETED'
			        AND ma.validity_review_status <> 'CONFIRMED_INVALID'
			        AND (s.end_reason_code IS NULL OR s.end_reason_code NOT IN (
			                'POLICY_TIME_LIMIT_EXCEEDED', 'ASSESSMENT_WINDOW_EXPIRED',
			                'REVIEW_DUE_AT_EXPIRED', 'DATA_INTEGRITY_INVALID',
			                'ADMIN_INVALIDATED', 'TECHNICAL_FAILURE'))
			       )                                                            AS eligible,
			       COALESCE(r.report_publish_not_before_at, r.assessment_due_at) AS publishNotBeforeAt,
			       (SELECT count(DISTINCT ps.problem_id)
			          FROM problem_stage ps WHERE ps.session_id = s.session_id)  AS problemCount
			  FROM assessment_session s
			  JOIN measurement_attempt ma
			    ON ma.attempt_id = s.attempt_id
			  JOIN project_assessment_round r
			    ON r.assessment_round_id = ma.assessment_round_id
			   AND r.deleted_at IS NULL
			 WHERE s.session_id = :sessionId
			""", nativeQuery = true)
	java.util.Optional<FinalizeContext> findFinalizeContext(@Param("sessionId") UUID sessionId);

	/**
	 * 확정은 끝났는데 <b>발행 예정 시각 때문에 보류</b>된 리포트. 발행 배치가 쓴다.
	 *
	 * <p>{@code ReportRunFinalizer}가 스냅샷·근거까지 만들어 두고 {@code published_at}만 비워 둔
	 * 상태다. 시각이 지나면 이 조회가 집어 발행한다.
	 *
	 * <h2>조건 하나하나가 다른 것을 막는다</h2>
	 *
	 * <ul>
	 *   <li>{@code published_at IS NULL} — 이미 발행된 것을 다시 건드리지 않는다</li>
	 *   <li>{@code is_active} 스냅샷이 있다 — <b>본문이 없는 리포트를 발행하지 않는다.</b>
	 *       화면 뷰가 스냅샷을 INNER JOIN하므로 없으면 발행해도 빈 화면이다</li>
	 *   <li>{@code lifecycle_status <> 'SUPERSEDED'} — 재생성으로 대체된 리포트는 대상이 아니다</li>
	 *   <li>{@code class_id IS NULL} — 교육생 리포트만. 기수·반 리포트는 발행 경로가 다르다</li>
	 * </ul>
	 *
	 * <p>🔴 <b>세션 유효성은 여기서 보지 않는다.</b> 무효 세션은 {@code ReportRunFinalizer}가 이미
	 * 걸렀고, 그때 스냅샷은 만들어 두므로 이 조회에 걸린다. 그러나 무효인 리포트를 나중에
	 * 발행하면 안 되므로 <b>발행 배치가 다시 확인한다</b>({@link #findFinalizeContext}).
	 */
	@Query(value = """
			SELECT rp.report_id AS reportId
			  FROM report rp
			  JOIN report_snapshot rs
			    ON rs.report_id = rp.report_id
			   AND rs.is_active
			  JOIN project_assessment_round r
			    ON r.assessment_round_id = rp.assessment_round_id
			   AND r.deleted_at IS NULL
			 WHERE rp.published_at IS NULL
			   AND rp.class_id IS NULL
			   AND rp.lifecycle_status <> 'SUPERSEDED'
			   AND now() >= COALESCE(r.report_publish_not_before_at, r.assessment_due_at)
			 ORDER BY rp.report_id
			""", nativeQuery = true)
	List<UUID> findReportsAwaitingPublish();

	/**
	 * 이 리포트를 만든 세션. 발행 배치가 유효성을 다시 볼 때 쓴다.
	 *
	 * <p>{@code report}에는 세션 축이 없어 item을 거쳐 찾는다. 한 리포트의 item은 모두 같은
	 * 세션이므로 아무거나 하나면 된다.
	 */
	@Query(value = """
			SELECT it.session_id
			  FROM report_generation_run run
			  JOIN report_generation_item it ON it.generation_run_id = run.generation_run_id
			 WHERE run.report_id = :reportId
			 ORDER BY it.created_at
			 LIMIT 1
			""", nativeQuery = true)
	java.util.Optional<UUID> findSessionIdByReport(@Param("reportId") UUID reportId);

	/** 확정 판정에 쓰는 맥락. {@link #findFinalizeContext} 참고. */
	interface FinalizeContext {

		/** 세션·응시가 정상 완료됐고 무효가 아닌가. 거짓이면 발행하지 않는다. */
		boolean getEligible();

		/** 이 시각 전에는 발행하지 않는다. 회차에 마감이 없으면 NULL이다. */
		java.time.Instant getPublishNotBeforeAt();

		/** 이 세션이 다룬 문제 수. item이 이만큼 모여야 확정한다. */
		int getProblemCount();
	}

	/**
	 * 끝난 문제 1건. {@link ReportTarget}(세션 맥락)에 문제 축을 더한 것이다.
	 *
	 * <p>{@code ReportTarget}을 상속하는 이유는 {@code dispatchOne}이 그 타입을 받기 때문이다 —
	 * 세션 단위 경로(배치 안전망·운영자 재생성)와 조립 코드를 그대로 공유한다.
	 */
	interface ProblemDueTarget extends ReportTarget {

		UUID getProblemId();

		/** 1~3. 범위 밖이거나 미지정이면 NULL이다. */
		Integer getProblemNo();
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
