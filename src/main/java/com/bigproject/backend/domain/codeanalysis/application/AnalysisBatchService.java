package com.bigproject.backend.domain.codeanalysis.application;

import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient.AnalysisProgress;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient.AnalysisRequest;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient.RequirementItem;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient.TeachItem;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisFailureCode;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJobStatus;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisDispatchRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisDispatchRepository.DispatchTarget;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisJobRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisModelRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisModelRepository.AnalysisModel;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAiUsageRecorder;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAnalysisJobDiagnostics;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAnalysisJobDiagnostics.JobSnapshot;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAnalysisRequestContextRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAssessmentSessionPreparer;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAnalysisResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 코드 분석 배치.
 *
 * <p><b>교육생에게 분석 실행 API를 주지 않는다.</b> 실행 주체는 이 배치뿐이고, 트리거는
 * {@link com.bigproject.backend.domain.submission.domain.SubmissionAcceptedEvent}다(2026-08-07).
 * 종전에는 "마감 후 배치 1회"였지만, 저장소 URL 오타를 마감 후에야 알게 되는 문제(P-09)를 해소하기
 * 위해 제출 즉시로 바꿨다. 팀이 마감 전 재제출할 때마다 AI 호출 비용이 다시 드는 것은 감수한다 —
 * 재시도 제한은 두지 않기로 했다.
 *
 * <p>여전히 지키는 것: 분석은 팀 단위 실행이라 개인이 화면에서 직접 실행 버튼을 누르는 구조는 아니고,
 * {@code monthly_ai_budget}은 이 배치가 대신 쥔다.
 *
 * <p>지금은 <b>상태 추적까지만</b> 한다. 분석 결과(문제·근거·커밋 이력)를 DB에 넣는 부분은
 * {@code gitHistory} 필드 이름이 AI 회신으로 확정된 뒤에 붙인다.
 */
@Service
public class AnalysisBatchService {

	private static final Logger log = LoggerFactory.getLogger(AnalysisBatchService.class);

	/** analysis_job.job_type. 값 집합 CHECK가 없어(D-03 보류) 문자열을 여기서 고정한다. */
	private static final String JOB_TYPE_CODE_ANALYSIS = "CODE_ANALYSIS";

	/** {@code ck_analysis_job_execution_no}가 0 이하를 막는다. 재시도는 여기서부터 1씩 올린다. */
	private static final int FIRST_EXECUTION = 1;

	/**
	 * 계획 문제 수. {@code ck_assessment_problem_problem_no}가 1~3만 허용하므로 <b>3을 넘길 수 없다.</b>
	 * AI 쪽 {@code questionBudget}에는 상한이 없어 이 제약은 우리만 알고 있다.
	 */
	private static final int QUESTION_BUDGET = 3;

	/** 추출 범위. 회차별 {@code project_extraction_scope}를 읽기 전까지는 팀 전체 기준이다. */
	private static final String EXTRACTION_SCOPE_TOTAL = "TOTAL";

	/**
	 * AI 요청의 {@code problemScope}. 지금은 이 값 하나만 보낸다 —
	 * {@code INDIVIDUAL_OWN_COMMIT}(개인 모드)은 P5에서 다룬다. {@code project.project_category}로
	 * 나눠 읽기 전까지는 미니프로젝트만 이 배치가 처리한다는 전제와 같은 선상의 고정값이다.
	 */
	private static final String PROBLEM_SCOPE_TEAM_SHARED_PROBLEM = "TEAM_SHARED_PROBLEM";

	private static final String METHOD_GITHUB_URL = "GITHUB_URL";

	private static final List<AnalysisJobStatus> ACTIVE_JOB_STATUSES =
			List.of(AnalysisJobStatus.QUEUED, AnalysisJobStatus.RUNNING);

	private final AnalysisDispatchRepository dispatchRepository;
	private final AnalysisJobRepository analysisJobRepository;
	private final AnalysisModelRepository analysisModelRepository;
	private final JdbcAnalysisResultRepository analysisResultRepository;
	private final JdbcAiUsageRecorder aiUsageRecorder;
	private final JdbcAnalysisJobDiagnostics analysisJobDiagnostics;
	private final JdbcAssessmentSessionPreparer sessionPreparer;
	private final AnalysisServerClient analysisServerClient;
	private final JdbcAnalysisRequestContextRepository requestContextRepository;

	/**
	 * 쓰기 단위마다 트랜잭션을 여닫는다.
	 *
	 * <p>{@code @Transactional}이 아니라 이걸 쓰는 이유: 필요한 경계가 <b>메서드 하나가 아니라 메서드
	 * 안의 여러 구간</b>이다({@link #pollActiveJobs} 참조). 애너테이션으로 나누려면 구간마다 별도 빈으로
	 * 쪼개야 하는데 — 같은 클래스 안의 자기 호출에는 프록시가 끼지 않아 조용히 무시된다 —
	 * 그렇게 나눈 빈들은 폴링 흐름을 읽기 어렵게만 만든다.
	 *
	 * <p>빈 주입이 아니라 생성자에서 직접 만든다. {@code TransactionTemplate} 빈은 자동 구성에 기대는
	 * 값이라, 없으면 컨텍스트가 뜨지 않는 실패를 런타임까지 미루게 된다.
	 */
	private final TransactionTemplate transactions;

	/**
	 * 코드 분석에 쓸 모델의 {@code ai_model.model_code}.
	 *
	 * <p>{@code CODE_ANALYSIS}용 모델을 정하는 정책 테이블이 없어서 설정값으로 둔다 —
	 * {@code platform_ai_tier_model_policy}는 {@code CHECK (feature_code IN ('CODE_SESSION'))}으로
	 * 세션 전용이다. 정책화가 필요해지면 그 CHECK를 넓히고 이 값을 정책 조회로 교체한다.
	 */
	private final String analysisModelCode;

	/**
	 * 한 제출에 허용하는 총 분석 시도 횟수. 재시도가 아니라 <b>총합</b>이다(첫 시도 포함).
	 *
	 * <p>상한이 필요한 이유: AI 서버가 계속 죽어 있으면 안전망이 돌 때마다 전 제출을 다시 요청한다.
	 * 분석 1회가 LLM 호출 20여 건이라 그 낭비가 작지 않다. 3이면 일시적 장애는 넘기고 지속 장애는
	 * 곧 멈춘다.
	 */
	private final int maxAttempts;

	/**
	 * AI가 404로 "모르는 작업"이라고 답할 때 얼마나 기다려 주는가. {@link #handleUnknownJob} 참조.
	 *
	 * <p>아주 크게 잡으면(예: {@code P30D}) 사실상 "절대 포기하지 않고 계속 폴링"이 된다. 그 경우
	 * AI가 정말 유실한 job은 수동 개입 전까지 활성으로 남고 그 제출은 결과가 나오지 않는다.
	 */
	private final Duration unknownJobGrace;

	/** 이 횟수만큼 JOB_NOT_FOUND가 연속되면 유예시간 전이라도 유실로 확정한다. */
	private final int unknownJobMaxConsecutive;

	/**
	 * AI 앱의 {@code JOB_NOT_FOUND} 관측 상태. DB 스키마를 변경하지 않기 위해 프로세스 메모리에만 둔다.
	 * 따라서 백엔드가 재시작되거나 여러 인스턴스가 서로 다른 요청을 받으면 관측 횟수와 최초 시각은
	 * 각 인스턴스에서 다시 시작한다. {@code external_job_id} 자체는 DB에 계속 보존된다.
	 */
	private final ConcurrentMap<UUID, UnknownJobObservation> unknownJobObservations = new ConcurrentHashMap<>();

	/**
	 * 생성자를 직접 쓴다({@code @RequiredArgsConstructor}가 아니다). 설정값을 필드 {@code @Value}로
	 * 주입하면 단위 테스트에서 항상 기본값(null·0)이라 모델 조회와 재시도 상한이 조용히 빗나간다 —
	 * 생성자 인자로 받으면 테스트가 값을 명시하게 된다.
	 */
	public AnalysisBatchService(
			AnalysisDispatchRepository dispatchRepository,
			AnalysisJobRepository analysisJobRepository,
			AnalysisModelRepository analysisModelRepository,
			JdbcAnalysisResultRepository analysisResultRepository,
			JdbcAiUsageRecorder aiUsageRecorder,
			JdbcAnalysisJobDiagnostics analysisJobDiagnostics,
			JdbcAssessmentSessionPreparer sessionPreparer,
			AnalysisServerClient analysisServerClient,
			JdbcAnalysisRequestContextRepository requestContextRepository,
			PlatformTransactionManager transactionManager,
			@Value("${ai.analysis.model-code}") String analysisModelCode,
			@Value("${ai.analysis.max-attempts}") int maxAttempts,
			@Value("${ai.analysis.unknown-job-grace:PT10M}") Duration unknownJobGrace,
			@Value("${ai.analysis.unknown-job-max-consecutive:3}") int unknownJobMaxConsecutive) {
		this.dispatchRepository = dispatchRepository;
		this.analysisJobRepository = analysisJobRepository;
		this.analysisModelRepository = analysisModelRepository;
		this.analysisResultRepository = analysisResultRepository;
		this.aiUsageRecorder = aiUsageRecorder;
		this.analysisJobDiagnostics = analysisJobDiagnostics;
		this.sessionPreparer = sessionPreparer;
		this.analysisServerClient = analysisServerClient;
		this.requestContextRepository = requestContextRepository;
		this.transactions = new TransactionTemplate(transactionManager);
		this.analysisModelCode = analysisModelCode;
		this.maxAttempts = maxAttempts;
		if (unknownJobGrace.isNegative() || unknownJobGrace.isZero()) {
			throw new IllegalArgumentException("unknown-job-grace는 0보다 커야 한다: " + unknownJobGrace);
		}
		if (unknownJobMaxConsecutive < 1) {
			throw new IllegalArgumentException(
					"unknown-job-max-consecutive는 1 이상이어야 한다: " + unknownJobMaxConsecutive);
		}
		this.unknownJobGrace = unknownJobGrace;
		this.unknownJobMaxConsecutive = unknownJobMaxConsecutive;
	}

	/**
	 * 방금 접수된 제출 1건을 분석 요청한다. {@code SubmissionAcceptedEvent} 리스너가 부르는 주 진입점이다.
	 *
	 * <p>대상이 없으면(이미 처리됐거나 슈퍼시드됨) 조용히 넘어간다 — {@link AnalysisDispatchRepository#findDispatchTarget}
	 * 문서 참조. 그 밖의 실패는 로그로 남기고 삼킨다. 비동기 리스너에서 예외를 던지면 호출자(제출
	 * 트랜잭션)에는 이미 응답이 나간 뒤라 아무도 못 받고, {@link #retryPendingSubmissions} 안전망이
	 * 다음 기회에 다시 집는다.
	 */
	public void dispatchSubmission(UUID submissionId) {
		dispatchRepository.findDispatchTarget(submissionId, maxAttempts).ifPresentOrElse(
				target -> {
					try {
						dispatchOne(target, Instant.now());
					} catch (RuntimeException exception) {
						log.error("제출 직후 분석 요청 실패: submissionId={}", submissionId, exception);
					}
				},
				() -> log.debug("분석 대상이 아니다(이미 처리됐거나 슈퍼시드됨): submissionId={}", submissionId)
		);
	}

	/**
	 * 안전망 겸 재시도: 아직 분석이 성사되지 않은 현재 제출을 다시 요청한다.
	 *
	 * <p>두 종류가 걸린다. ① 이벤트 유실·앱 재시작으로 트리거를 놓친 제출 ② <b>일시적 실패로 끝난
	 * 제출</b>(AI 서버 장애·타임아웃 등). ②를 다시 집는 것이 2026-08-09에 추가된 부분이다 —
	 * 종전에는 FAILED 행이 남는 순간 그 제출이 영구히 제외돼, AI 서버가 잠깐 죽어 있는 동안 접수된
	 * 제출은 마감 후 재제출조차 막혀 복구 경로가 없었다.
	 *
	 * <p>어떤 실패를 다시 집는지는 {@link AnalysisDispatchRepository#BLOCKING_JOB_EXISTS}가 정하고,
	 * 무한 재시도는 {@code ai.analysis.max-attempts}가 막는다.
	 *
	 * <p>제출 1건마다 트랜잭션을 나눈다. 한 팀의 요청이 실패해도 나머지가 함께 롤백되면 안 되기 때문이다.
	 *
	 * @return 요청을 보낸 제출 수
	 */
	public int retryPendingSubmissions() {
		List<DispatchTarget> targets = dispatchRepository.findRetryableSubmissions(maxAttempts);
		Instant now = Instant.now();
		int dispatched = 0;
		for (DispatchTarget target : targets) {
			try {
				dispatchOne(target, now);
				dispatched++;
			} catch (RuntimeException exception) {
				// 여기서 삼키는 이유는 다음 팀을 계속 처리하기 위해서다. 실패한 제출은 job 행이
				// FAILED 로 남고, 그 사유가 재시도 대상이면 다음 배치가 다시 고른다.
				log.error("분석 요청 실패: submissionId={}", target.getSubmissionId(), exception);
			}
		}
		return dispatched;
	}

	/**
	 * 제출 1건을 요청한다.
	 *
	 * <p>순서가 중요하다. AI가 202로 준 작업 ID를 객체에 반영한 뒤 <b>ID를 포함해 최초 INSERT</b>한다.
	 * 정상 접수된 활성 행이 외부 ID 없이 DB에 존재하는 창 자체를 없애기 위해서다. AI 접수 직후
	 * 프로세스가 죽어 INSERT를 못 했더라도, 다음 안전망 요청은 같은 submissionId·executionNo 멱등키를
	 * 보내므로 AI가 기존 작업 ID를 다시 돌려주고 원장을 복구할 수 있다.
	 *
	 * <p>{@code @Transactional}을 이 메서드에 걸지 않는다. {@link #dispatchSubmission}·
	 * {@link #retryPendingSubmissions} 둘 다 같은 클래스 안에서 이 메서드를 호출하는데, Spring AOP는
	 * 프록시를 거치지 않는 자기 호출에 트랜잭션 어드바이스를 적용하지 못한다 — 붙여 봐야 조용히
	 * 무시된다. 대신 각 {@code save()}가 Spring Data JPA의 기본 동작으로 자기 완결적 트랜잭션이 되고,
	 * 그 편이 오히려 맞다: 하나의 트랜잭션으로 감싸면 AI 서버 HTTP 호출 동안 DB 커넥션을 붙들게 된다.
	 */
	private void dispatchOne(DispatchTarget target, Instant now) {
		String traceId = UUID.randomUUID().toString();
		// 모델 확정을 job 생성보다 먼저 한다. 여기서 실패하면 job 행을 남기지 않는 것이 맞다 --
		// 즉시 디스패치·안전망 조회 모두 blocking job이 있으면 제외하므로
		// FAILED 행을 만들어 두면 설정을 고쳐도 그 제출은 두 번 다시 집히지 않는다.
		// 모델 미설정은 특정 제출의 문제가 아니라 기관 전체가 못 도는 설정 문제이므로,
		// 제출을 재시도 가능한 상태로 남겨 두는 편이 맞다.
		AnalysisModel model = resolveAnalysisModel();
		// teaches도 모델과 같은 이유로 job 생성보다 먼저 확정한다 — TEAM_SHARED_PROBLEM은 teaches가
		// 비면 AI가 거부하므로(2026-08-10 확인), 검증 개념 미설정은 그 제출의 문제가 아니라 프로젝트
		// 설정 문제다. job 행을 남기면 설정을 고쳐도 재시도 대상에서 영구히 빠진다.
		List<TeachItem> teaches = requireTeaches(target);

		AnalysisJob job = AnalysisJob.queued(
				target.getOrgId(),
				target.getAssessmentRoundId(),
				target.getTeamId(),
				target.getSubmissionId(),
				batchKeyOf(target),
				JOB_TYPE_CODE_ANALYSIS,
				nextExecutionNo(target.getSubmissionId()),
				traceId
		);
		job.recordRequest(model.getModelId(), QUESTION_BUDGET);
		try {
			UUID externalJobId = analysisServerClient.requestAnalysis(new AnalysisRequest(
					target.getMethod(),
					target.getSubmissionId(),
					target.getRepositoryUrl(),
					target.getRequestedBranch(),
					PROBLEM_SCOPE_TEAM_SHARED_PROBLEM,
					EXTRACTION_SCOPE_TOTAL,
					// TEAM_SHARED_PROBLEM에서는 보내지 않는다 — 개인 모드(P5) 전용 필드다(2026-08-10 확인).
					null,
					QUESTION_BUDGET,
					requirementsFor(target),
					// focusItems도 TEAM_SHARED_PROBLEM에서는 항상 null이다 — teaches가 이미 출제 기준을
					// 정해서, 초점 후보까지 얹으면 기준이 둘로 갈린다.
					null,
					teaches,
					model.getModelCode(),
					target.getArtifactStorageUri(),
					target.getArtifactFileName(),
					idempotencyKeyOf(target.getSubmissionId(), job.getExecutionNo()),
					traceId
			));
			job.acceptExternalJob(externalJobId);
			job = analysisJobRepository.save(job);
			log.info("분석 작업 ID 저장: jobId={}, externalJobId={}, status={}, executionNo={}",
					job.getJobId(), externalJobId, job.getStatus(), job.getExecutionNo());
			// 🔍 진단용(2026-08-13). save() 가 예외 없이 끝나는데도 external_job_id 가 NULL 인 사례가
			// 있어, 커밋된 행을 새 트랜잭션에서 다시 읽어 확인한다. 원인이 잡히면 지운다.
			verifyPersisted("요청 접수 직후", job, externalJobId);
			// 요청이 접수됐다는 사실을 응시에도 남긴다. 교육생 홈의 "분석 중" 표시 근거다.
			sessionPreparer.markAttemptsAnalyzing(target.getAssessmentRoundId(), target.getTeamId());
			// 실DB에 measurement_attempt UPDATE 트리거가 남아 있는지 판별하는 경계다. 여기서 바로
			// NULL이면 위 UPDATE의 DB 부수효과이고, 여기서 유지된 뒤 폴링 때 NULL이면 다른 writer다.
			verifyPersisted("응시 ANALYZING 전이 직후", job, externalJobId);
		} catch (AnalysisServerException exception) {
			// 요청이 거절되면 그 자체가 분석 실패다. QUEUED로 남겨 두면 폴링이 영원히 붙들고 있는데,
			// external_job_id 가 없어 조회할 대상조차 없다.
			job.markFailed(exception.failureCode(), exception.getMessage(), now, now);
			analysisJobRepository.save(job);
			throw exception;
		}
	}

	/**
	 * 진행 중인 실행의 상태를 갱신한다.
	 *
	 * <h2>이 메서드에 {@code @Transactional}을 걸지 않는다 (2026-08-11)</h2>
	 *
	 * <p>종전에는 이 메서드 전체가 하나의 트랜잭션이었고, 안에서 <b>예외를 삼켰다.</b> 그 조합이
	 * Postgres에서는 성립하지 않는다 — 문 하나가 실패하는 순간 트랜잭션 전체가 abort 상태가 되어
	 * 이후 모든 문이 {@code current transaction is aborted}로 거부되고, 자바 쪽에서 예외를 잡아 둔들
	 * 커밋 시점에 전부 롤백된다. 결과적으로 <b>제약 위반 하나가 그 회차 폴링의 모든 job과
	 * 이미 적재된 분석 결과까지 통째로 되돌렸다.</b> "이 job만 건너뛰고 계속한다"는 의도가 실제로는
	 * 지켜지지 않았다.
	 *
	 * <p>그래서 트랜잭션을 job 단위, 그리고 job 안에서도 <b>독립적인 쓰기 단위</b>로 쪼갠다.
	 * 삼킨 예외마다 그 단위만 롤백되므로 격리 의도가 실제로 성립한다.
	 *
	 * <p>덤으로 <b>AI 서버 HTTP 호출이 트랜잭션 밖으로 나왔다.</b> 종전에는 job 수만큼의 순차 HTTP
	 * 왕복 내내 DB 커넥션을 붙들고 있었다 — {@link #dispatchOne}이 같은 이유로 피하던 것을 폴링에서는
	 * 하고 있었던 셈이다.
	 *
	 * <p>404는 즉시 판단하지 않는다. {@code external_job_id}는 어떤 경우에도 지우지 않는다 —
	 * 자세한 이유는 {@link #handleUnknownJob}에 있다.
	 *
	 * @return 상태가 바뀐 실행 수
	 */
	public int pollActiveJobs() {
		List<AnalysisJob> active = analysisJobRepository.findByStatusIn(ACTIVE_JOB_STATUSES);
		// 🔍 진단용(2026-08-13). 폴링이 DB에서 무엇을 읽었는지 그대로 남긴다. GET 이 나가는데
		// 컬럼이 NULL 이라면 이 줄과 DB 조회 결과가 어긋나는 지점이 곧 원인이다.
		active.forEach(candidate -> log.info("🔍 폴링 대상 조회: jobId={}, status={}, external_job_id={}",
				candidate.getJobId(), candidate.getStatus(), candidate.getExternalJobId()));
		int updated = 0;
		for (AnalysisJob job : active) {
			if (job.getExternalJobId() == null) {
				try {
					analysisJobDiagnostics.logExternalIdLoss(job.getJobId());
					if (closeJobMissingExternalId(job)) {
						updated++;
					}
				} catch (RuntimeException exception) {
					log.error("외부 작업 ID가 없는 분석 job 종료 실패. 다음 폴링에서 다시 시도한다: jobId={}",
							job.getJobId(), exception);
				}
				continue;
			}
			try {
				if (applyProgress(job)) {
					updated++;
				}
			} catch (RuntimeException exception) {
				log.error("분석 상태 갱신 실패. 이 job만 건너뛰고 계속한다: jobId={}", job.getJobId(), exception);
			}
		}
		return updated;
	}

	/**
	 * 접수 진행 중이 아닌 활성 job에 외부 ID가 없으면 더는 폴링할 수 없으므로 실패로 닫는다.
	 * 전역 스케줄러를 멈추는 대신 이 행만 활성 조회에서 제외해 다른 정상 job의 폴링은 계속한다.
	 */
	private boolean closeJobMissingExternalId(AnalysisJob job) {
		Instant now = Instant.now();
		log.error("활성 분석 job에 external_job_id가 없어 해당 job의 폴링을 종료한다: "
					+ "jobId={}, submissionId={}, status={}, executionNo={}",
				job.getJobId(), job.getSubmissionId(), job.getStatus(), job.getExecutionNo());
		markAttemptsFailedIfTerminal(job, AnalysisFailureCode.MODEL_ERROR);
		job.markFailed(AnalysisFailureCode.MODEL_ERROR,
				"AI 서버 작업 ID가 없어 상태 조회를 계속할 수 없다. 해당 분석 실행의 폴링을 종료한다.",
				job.getStartedAt(), now);
		saveJob(job);
		return true;
	}

	private boolean applyProgress(AnalysisJob job) {
		// 트랜잭션 밖이다. 이 호출이 5분 걸려도 붙들고 있는 DB 커넥션이 없다.
		AnalysisProgress progress = analysisServerClient.fetchProgress(job.getExternalJobId()).orElse(null);
		if (progress == null) {
			return handleUnknownJob(job);
		}
		unknownJobObservations.remove(job.getExternalJobId());
		// 사용량은 성공·실패를 가리지 않고 먼저 적재한다. 실패한 분석도 토큰은 이미 썼고,
		// 그게 monthly_ai_budget 집행 근거다 -- 실패했다고 빼면 예산이 새는 쪽으로 틀린다.
		recordUsage(job, progress);

		return switch (progress.status()) {
			case QUEUED -> false;
			case RUNNING -> {
				if (job.getStatus() == AnalysisJobStatus.RUNNING) {
					yield false;
				}
				job.markRunning(progress.startedAt() == null ? Instant.now() : progress.startedAt());
				saveJob(job);
				yield true;
			}
			case SUCCEEDED, PARTIAL -> {
				recordResult(job, progress);
				job.markCompleted(progress.status(), progress.startedAt(),
						progress.completedAt() == null ? Instant.now() : progress.completedAt());
				saveJob(job);
				yield true;
			}
			case FAILED -> {
				markAttemptsFailedIfTerminal(job, progress.failureCode());
				job.markFailed(
						// 값 집합 밖의 코드가 오면 저장이 CHECK 로 막힌다. 실패를 기록조차 못 하는
						// 것보다 MODEL_ERROR 로 남기고 원문을 사유에 붙이는 편이 낫다.
						progress.failureCode() == null ? AnalysisFailureCode.MODEL_ERROR : progress.failureCode(),
						progress.failureReason() == null || progress.failureReason().isBlank()
								? "AI 서버가 실패 사유를 주지 않았다." : progress.failureReason(),
						progress.startedAt(),
						progress.completedAt() == null ? Instant.now() : progress.completedAt());
				saveJob(job);
				yield true;
			}
		};
	}

	/**
	 * AI가 이 job을 모른다고(404) 했을 때. <b>{@code external_job_id}를 지우지 않는다.</b>
	 *
	 * <h2>왜 지우면 안 되는가 (2026-08-13)</h2>
	 *
	 * <p>종전에는 404를 "AI가 재시작해 job을 유실했다"는 확정 신호로 읽고 {@code external_job_id}를
	 * NULL로 되돌렸다. 그 전제가 틀렸다 — AI 쪽 모델 실행 오류나 지연으로도 404가 난다. 실제로
	 * {@code jobId=b34b532a}는 200 OK로 RUNNING까지 갔다가 뒤이은 404 한 번에 ID가 지워졌다.
	 *
	 * <p>게다가 지운 뒤 상태가 "재요청 대상"이 되지도 않았다. {@link #pollActiveJobs}는
	 * {@code external_job_id}가 NULL인 job을 건너뛰고, 디스패치는 QUEUED·RUNNING을 blocking job으로
	 * 보고 막는다({@code AnalysisDispatchRepository.BLOCKING_JOB_EXISTS}). 폴링도 재요청도 되지 않는
	 * <b>영구 정체</b>였다. 주석과 실제 동작이 어긋나 있었다.
	 *
	 * <h2>그래서 응답 출처·최초 시각·연속 횟수로 가른다</h2>
	 *
	 * <p>AI 앱이 명시한 {@code JOB_NOT_FOUND}만 센다. 최초 관측 시각부터
	 * {@code ai.analysis.unknown-job-grace}가 지나거나 연속 횟수가
	 * {@code ai.analysis.unknown-job-max-consecutive}에 먼저 도달하면 {@code TEMPORARY_ERROR}로
	 * 닫는다. 정상 응답이 한 번이라도 오면 시각·횟수를 초기화한다. 이 관측 상태는 DB 스키마를
	 * 바꾸지 않고 프로세스 메모리에만 저장하므로 백엔드 재시작 시 초기화된다. HTML·빈 본문의
	 * 프록시 404는 이 메서드에 들어오지 않고 웜업 후 재조회한다.
	 *
	 * <p>닫을 때도 {@code external_job_id}는 그대로 남긴다 — AI 로그와 대조할 유일한 열쇠다.
	 *
	 * @return 분석 상태가 종료 상태로 바뀌었는가. 관측 정보만 누적한 경우는 {@code false}.
	 */
	private boolean handleUnknownJob(AnalysisJob job) {
		Instant now = Instant.now();
		UnknownJobObservation observation = unknownJobObservations.compute(job.getExternalJobId(), (externalJobId, current) ->
				current == null
						? new UnknownJobObservation(now, 1)
						: new UnknownJobObservation(current.firstSeenAt(), current.consecutiveCount() + 1));
		Duration elapsed = Duration.between(observation.firstSeenAt(), now);
		boolean countExceeded = observation.consecutiveCount() >= unknownJobMaxConsecutive;
		boolean graceExceeded = elapsed.compareTo(unknownJobGrace) >= 0;
		if (!countExceeded && !graceExceeded) {
			log.warn("AI 앱이 JOB_NOT_FOUND를 반환했다. 외부 ID를 유지하고 다시 확인한다: "
							+ "jobId={}, externalJobId={}, 연속횟수={}/{}, 최초404={}, 경과={}, 최대유예={}",
					job.getJobId(), job.getExternalJobId(), observation.consecutiveCount(),
					unknownJobMaxConsecutive, observation.firstSeenAt(), elapsed, unknownJobGrace);
			return false;
		}

		unknownJobObservations.remove(job.getExternalJobId(), observation);
		log.error("AI 앱의 JOB_NOT_FOUND를 작업 유실로 확정해 재요청 대상으로 닫는다: "
						+ "jobId={}, externalJobId={}, executionNo={}, 연속횟수={}, 최초404={}, 경과={}, 최대유예={}",
				job.getJobId(), job.getExternalJobId(), job.getExecutionNo(),
				observation.consecutiveCount(), observation.firstSeenAt(), elapsed, unknownJobGrace);
		markAttemptsFailedIfTerminal(job, AnalysisFailureCode.TEMPORARY_ERROR);
		job.markFailed(AnalysisFailureCode.TEMPORARY_ERROR,
				"AI 서버가 JOB_NOT_FOUND를 연속 " + observation.consecutiveCount()
						+ "회 반환했다(최초 응답 후 " + elapsed + "). "
						+ "AI 쪽에서 유실된 것으로 보고 재요청 대상으로 닫는다: externalJobId=" + job.getExternalJobId(),
				job.getStartedAt(), now);
		saveJob(job);
		return true;
	}

	private record UnknownJobObservation(Instant firstSeenAt, int consecutiveCount) {
	}

	/**
	 * 상태 전이를 원장에 반영한다. <b>자기 트랜잭션</b>이다.
	 *
	 * <p>준영속 엔티티의 {@code save/merge}를 쓰지 않는다. 상태·시각·실패 정보만 SET하는 전용 쿼리를
	 * 사용하고 {@code external_job_id}는 외부 ID 일치 여부를 확인하는 WHERE 조건으로만 둔다. 이 구조면
	 * 폴링뿐 아니라 앞으로 상태 필드가 추가돼도 외부 ID를 NULL로 덮는 SQL을 만들 수 없다.
	 */
	private void saveJob(AnalysisJob job) {
		UUID externalJobId = job.getExternalJobId();
		Integer updated = transactions.execute(status -> externalJobId == null
				? analysisJobRepository.transitionActiveJobWithoutExternalId(
						job.getJobId(), job.getStatus(), job.getStartedAt(), job.getCompletedAt(),
						job.getFailureReason(), job.getFailureCode(), ACTIVE_JOB_STATUSES)
				: analysisJobRepository.transitionActiveJob(
						job.getJobId(), externalJobId, job.getStatus(), job.getStartedAt(), job.getCompletedAt(),
						job.getFailureReason(), job.getFailureCode(), ACTIVE_JOB_STATUSES));
		if (updated == null || updated != 1) {
			throw new IllegalStateException("분석 job 상태 전이 대상이 일치하지 않는다: jobId="
					+ job.getJobId() + ", externalJobId=" + externalJobId + ", status=" + job.getStatus());
		}
		// 🔍 진단용(2026-08-13). 상태 전이 저장이 external_job_id 를 함께 덮어쓰는지 본다.
		verifyPersisted("상태 전이 저장 직후(" + job.getStatus() + ")", job, externalJobId);
	}

	/**
	 * 🔍 진단용(2026-08-13). 커밋된 행을 JPA 캐시가 아닌 <b>raw JDBC</b>로 다시 읽어
	 * {@code external_job_id}와 PostgreSQL 행 버전({@code xmin})을 확인한다.
	 *
	 * <p>추론으로는 더 좁힐 수 없어서 넣었다. 정상 접수 건은 외부 ID를 포함해 INSERT하고,
	 * 후속 상태 전이는 이 컬럼을 SET 절에 넣지 않는 명시 쿼리만 사용한다.
	 *
	 * <p>원인이 잡히면 이 메서드와 호출부를 지운다 — 상태 전이마다 SELECT 가 한 번 더 나간다.
	 */
	private void verifyPersisted(String where, AnalysisJob job, UUID expected) {
		try {
			JobSnapshot snapshot = analysisJobDiagnostics.findSnapshot(job.getJobId()).orElse(null);
			if (snapshot == null) {
				log.error("🔍 {} raw DB 재조회에서 행이 없다: jobId={}, 메모리 external_job_id={}",
						where, job.getJobId(), expected);
				return;
			}
			UUID stored = snapshot.externalJobId();
			if (expected == null ? stored == null : expected.equals(stored)) {
				log.info("🔍 {} raw DB 재조회 일치: jobId={}, external_job_id={}, status={}, xmin={}, "
							+ "observerApplication={}, observerPid={}, observerClient={}",
						where, job.getJobId(), stored, snapshot.status(), snapshot.rowVersion(),
						snapshot.observerApplication(), snapshot.observerPid(), snapshot.observerClient());
				return;
			}
			log.error("🔍 {} raw DB 재조회 불일치! 메모리={} 인데 DB={} 다: jobId={}, status={}, xmin={}, "
							+ "observerApplication={}, observerPid={}, observerClient={}",
					where, expected, stored, job.getJobId(), snapshot.status(), snapshot.rowVersion(),
					snapshot.observerApplication(), snapshot.observerPid(), snapshot.observerClient());
			analysisJobDiagnostics.logExternalIdLoss(job.getJobId());
		} catch (RuntimeException exception) {
			// 진단 SELECT 실패가 정상 분석 요청이나 상태 전이를 되돌리면 안 된다.
			log.warn("🔍 {} raw DB 재조회 진단 실패: jobId={}", where, job.getJobId(), exception);
		}
	}

	/**
	 * 되돌릴 수 없는 실패일 때만 응시를 종료 처리한다.
	 *
	 * <p>재시도 가능한 실패(AI 서버 장애·타임아웃 등)에 종료 표시를 하면 위험 교육생 산식의
	 * 분모에서 그 사람이 빠지는데, 잠시 뒤 재시도가 성공하면 이미 틀린 집계가 남는다.
	 * 그래서 두 조건을 함께 본다 — 사유가 재시도 대상이 아니거나, 재시도 상한을 다 썼거나.
	 *
	 * <p>이 전이가 있어야 "미응시"({@code NOT_ATTENDED})와 "분석 실패로 응시 불가"
	 * ({@code ANALYSIS_FAILED})가 구분된다. 둘 다 위험 비율의 분모에서 빠지지만 원인이 다르고,
	 * 후자는 교육생 잘못이 아니라 운영이 봐야 할 사건이다.
	 */
	private void markAttemptsFailedIfTerminal(AnalysisJob job, AnalysisFailureCode failureCode) {
		boolean retryable = failureCode != null && failureCode.retryable();
		boolean attemptsLeft = job.getExecutionNo() != null && job.getExecutionNo() < maxAttempts;
		if (retryable && attemptsLeft) {
			return;
		}
		try {
			// 자기 트랜잭션이라야 이 catch 가 실제로 "상태 전이는 막지 않는다"가 된다. 같은
			// 트랜잭션에서 실패하면 삼켜도 뒤따르는 상태 전이가 함께 롤백된다.
			transactions.executeWithoutResult(status ->
					sessionPreparer.markAttemptsAnalysisFailed(job.getAssessmentRoundId(), job.getTeamId()));
		} catch (RuntimeException exception) {
			log.error("응시 실패 표시 실패: jobId={}", job.getJobId(), exception);
		}
	}

	/**
	 * AI가 보고한 LLM 사용량을 적재한다. 실패해도 상태 전이는 막지 않는다.
	 *
	 * <p>{@code ai_usage.model_code}가 FK라 카탈로그에 없는 모델은 그 행만 빠진다
	 * ({@link JdbcAiUsageRecorder} 참조). 여기서 예외가 새면 분석 결과 적재까지 함께 롤백된다.
	 */
	private void recordUsage(AnalysisJob job, AnalysisProgress progress) {
		if (progress.aiUsage() == null || progress.aiUsage().isEmpty()) {
			return;
		}
		try {
			// 자기 트랜잭션. FK 위반이 나도 이 단위만 롤백되고 결과 적재·상태 전이는 그대로 간다.
			transactions.executeWithoutResult(status -> aiUsageRecorder.record(job, progress.aiUsage()));
		} catch (RuntimeException exception) {
			log.error("AI 사용량 적재 실패: jobId={}", job.getJobId(), exception);
		}
	}

	/**
	 * 결과를 원장에 적재한다. 실패해도 상태 전이는 막지 않는다.
	 *
	 * <p><b>적재 실패로 job 을 FAILED 로 되돌리지 않는 이유.</b> AI 쪽 분석은 실제로 성공했고 비용도
	 * 이미 나갔다. FAILED 로 쓰면 재시도 대상이 되어 같은 분석을 또 돌리게 되는데, 적재가 깨진 원인이
	 * 우리 스키마·매핑이면 몇 번을 다시 불러도 같은 자리에서 깨진다. 상태는 사실대로 두고 적재 실패는
	 * 로그로 드러낸다 -- {@code analysis_id} 가 비어 있는 SUCCEEDED job 이 곧 그 신호다.
	 *
	 * <p><b>적재는 자기 트랜잭션에서 통째로 성공하거나 통째로 실패한다.</b> {@code code_analysis} 만
	 * 남고 문제가 없는 상태는 "분석은 됐는데 문항이 없다"로 보여 실패보다 나쁘다
	 * ({@link JdbcAnalysisResultRepository#record} 참조). 상태 전이를 같은 트랜잭션에 넣지 않는 것은
	 * 위 문단 때문이다 — 적재 실패로 전이까지 롤백되면 job 이 RUNNING 에 머물러 다음 폴링이 같은
	 * 실패를 무한히 반복한다.
	 *
	 * <p>{@code analysisId} 가 이미 있으면 건너뛴다. 적재는 성공했는데 그 뒤 상태 전이가 실패해 job 이
	 * 다음 폴링에 다시 걸리는 좁은 창이 있는데, 그때 다시 적재하면 {@code assessment_problem} 이
	 * AI 가 준 {@code problemId} 를 PK 로 쓰므로 같은 id 로 충돌한다.
	 */
	private void recordResult(AnalysisJob job, AnalysisProgress progress) {
		if (!progress.hasResult()) {
			log.warn("성공 응답에 result 가 없어 적재를 건너뛴다: jobId={}, status={}",
					job.getJobId(), progress.status());
			return;
		}
		if (job.getAnalysisId() != null) {
			log.warn("이미 적재된 결과가 있어 건너뛴다. 직전 상태 전이가 실패했을 수 있다: jobId={}, analysisId={}",
					job.getJobId(), job.getAnalysisId());
			return;
		}
		try {
			UUID analysisId = transactions.execute(status ->
					analysisResultRepository.record(job, progress.result()));
			job.attachAnalysis(analysisId);
		} catch (RuntimeException exception) {
			log.error("분석 결과 적재 실패. job 은 성공으로 남긴다: jobId={}", job.getJobId(), exception);
		}
	}

	/**
	 * {@code analysis_job.batch_key}. 정의서: "assessment_round_id·team_id·submission_id와 실행 목적을
	 * 안정적으로 포함한다."
	 *
	 * <p>{@code uq_analysis_job_active(batch_key, job_type)}가 이 값으로 동시 실행을 막으므로, 같은
	 * 제출에 대해 항상 같은 문자열이 나와야 한다.
	 */
	private String batchKeyOf(DispatchTarget target) {
		return "%s:%s:%s:%s".formatted(
				target.getAssessmentRoundId(), target.getTeamId(),
				target.getSubmissionId(), JOB_TYPE_CODE_ANALYSIS);
	}

	/**
	 * 이 제출의 다음 {@code execution_no}. 첫 시도는 1, 재시도는 직전 최대값 + 1이다.
	 *
	 * <p>이 값이 그대로 멱등키({@code submissionId:attemptNo})가 되므로 <b>재시도마다 달라져야
	 * 한다.</b> 1로 고정하면 AI가 앞선 실패 요청과 같은 키로 보고 중복으로 판정할 수 있어, 재시도가
	 * 요청조차 되지 않는다.
	 */
	private int nextExecutionNo(UUID submissionId) {
		Integer max = dispatchRepository.findMaxExecutionNo(submissionId);
		return max == null ? FIRST_EXECUTION : max + 1;
	}

	/** AI 서버 계약: {@code submissionId:attemptNo}. attemptNo는 우리 execution_no다. */
	private String idempotencyKeyOf(UUID submissionId, int executionNo) {
		return submissionId + ":" + executionNo;
	}

	/**
	 * 쓸 모델을 카탈로그에서 확정한다. <b>못 찾으면 요청을 보내지 않는다.</b>
	 *
	 * <p>모델을 생략하고 보내면 AI가 자기 기본 모델을 쓰고, 그 모델이 우리 {@code ai_model}에 없으면
	 * 응답의 {@code aiUsage[]}가 {@code ai_usage.model_code} FK를 위반해 <b>사용량 22행이 통째로
	 * 유실된다.</b> 사용량은 {@code monthly_ai_budget} 집행 근거라 조용히 비면 예산이 새는 쪽으로
	 * 틀린다. 분석 하나를 못 돌리는 편이 낫다.
	 *
	 * <p>{@code TEMPORARY_ERROR}인 이유: 카탈로그에 모델을 넣거나 설정을 고치면 재시도로 풀린다.
	 */
	private AnalysisModel resolveAnalysisModel() {
		return analysisModelRepository.findActiveByModelCode(analysisModelCode)
				.orElseThrow(() -> new AnalysisServerException(AnalysisFailureCode.TEMPORARY_ERROR,
						"ai.analysis.model-code 가 가리키는 ACTIVE 모델이 ai_model 에 없다: " + analysisModelCode));
	}

	/**
	 * TEAM_SHARED_PROBLEM 요청의 필수 재료. 비어 있으면 AI가 요청 자체를 거부하므로(2026-08-10 확인)
	 * 호출 전에 막는다. 원인은 특정 제출이 아니라 그 회차에 검증 개념(concept_set)이 아예 설정되지
	 * 않은 것이라 {@link #resolveAnalysisModel}과 같은 이유로 job 행을 남기지 않는다.
	 */
	private List<TeachItem> requireTeaches(DispatchTarget target) {
		List<TeachItem> teaches = requestContextRepository.findTeaches(
				target.getAssessmentRoundId(), target.getProjectId());
		if (teaches.isEmpty()) {
			throw new AnalysisServerException(AnalysisFailureCode.TEMPORARY_ERROR,
					"이 회차에 적용된 검증 개념(teaches)이 없다. TEAM_SHARED_PROBLEM은 teaches가 비면 AI가 "
							+ "거부한다: assessmentRoundId=" + target.getAssessmentRoundId());
		}
		return teaches;
	}

	/** 2026-08-10 확인: GitHub 제출에만 requirements를 싣는다. ZIP 제출은 null. */
	private List<RequirementItem> requirementsFor(DispatchTarget target) {
		return METHOD_GITHUB_URL.equals(target.getMethod())
				? requestContextRepository.findRequirements(target.getProjectId())
				: null;
	}
}
