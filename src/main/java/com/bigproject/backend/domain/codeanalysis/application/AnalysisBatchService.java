package com.bigproject.backend.domain.codeanalysis.application;

import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient.AnalysisProgress;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient.AnalysisRequest;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisFailureCode;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJobStatus;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisDispatchRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisDispatchRepository.DispatchTarget;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisJobRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisModelRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisModelRepository.AnalysisModel;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAiUsageRecorder;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAssessmentSessionPreparer;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAnalysisResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

	private final AnalysisDispatchRepository dispatchRepository;
	private final AnalysisJobRepository analysisJobRepository;
	private final AnalysisModelRepository analysisModelRepository;
	private final JdbcAnalysisResultRepository analysisResultRepository;
	private final JdbcAiUsageRecorder aiUsageRecorder;
	private final JdbcAssessmentSessionPreparer sessionPreparer;
	private final AnalysisServerClient analysisServerClient;

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
			JdbcAssessmentSessionPreparer sessionPreparer,
			AnalysisServerClient analysisServerClient,
			@Value("${ai.analysis.model-code}") String analysisModelCode,
			@Value("${ai.analysis.max-attempts}") int maxAttempts) {
		this.dispatchRepository = dispatchRepository;
		this.analysisJobRepository = analysisJobRepository;
		this.analysisModelRepository = analysisModelRepository;
		this.analysisResultRepository = analysisResultRepository;
		this.aiUsageRecorder = aiUsageRecorder;
		this.sessionPreparer = sessionPreparer;
		this.analysisServerClient = analysisServerClient;
		this.analysisModelCode = analysisModelCode;
		this.maxAttempts = maxAttempts;
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
	 * <p>순서가 중요하다. <b>job 행을 먼저 QUEUED로 저장하고</b> AI를 부른다. 반대로 하면 202를 받고도
	 * 행이 없는 순간이 생기고, 그 사이에 프로세스가 죽으면 AI에는 실행이 있는데 우리 원장에는 없다.
	 *
	 * <p>{@code @Transactional}을 이 메서드에 걸지 않는다. {@link #dispatchSubmission}·
	 * {@link #dispatchDueSubmissions} 둘 다 같은 클래스 안에서 이 메서드를 호출하는데, Spring AOP는
	 * 프록시를 거치지 않는 자기 호출에 트랜잭션 어드바이스를 적용하지 못한다 — 붙여 봐야 조용히
	 * 무시된다. 대신 각 {@code save()}가 Spring Data JPA의 기본 동작으로 자기 완결적 트랜잭션이 되고,
	 * 그 편이 오히려 맞다: 하나의 트랜잭션으로 감싸면 AI 서버 HTTP 호출 동안 DB 커넥션을 붙들게 된다.
	 */
	private void dispatchOne(DispatchTarget target, Instant now) {
		String traceId = UUID.randomUUID().toString();
		// 모델 확정을 job 생성보다 먼저 한다. 여기서 실패하면 job 행을 남기지 않는 것이 맞다 --
		// findDispatchTarget·findDueSubmissions 둘 다 "analysis_job 행이 하나라도 있으면 제외"라
		// FAILED 행을 만들어 두면 설정을 고쳐도 그 제출은 두 번 다시 집히지 않는다.
		// 모델 미설정은 특정 제출의 문제가 아니라 기관 전체가 못 도는 설정 문제이므로,
		// 제출을 재시도 가능한 상태로 남겨 두는 편이 맞다.
		AnalysisModel model = resolveAnalysisModel();

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
		job = analysisJobRepository.save(job);

		try {
			UUID externalJobId = analysisServerClient.requestAnalysis(new AnalysisRequest(
					target.getMethod(),
					target.getSubmissionId(),
					null,
					target.getRepositoryUrl(),
					target.getRequestedBranch(),
					EXTRACTION_SCOPE_TOTAL,
					null,
					QUESTION_BUDGET,
					null,
					model.getProviderModelCode(),
					target.getArtifactStorageUri(),
					target.getArtifactFileName(),
					idempotencyKeyOf(target.getSubmissionId(), job.getExecutionNo()),
					traceId
			));
			job.acceptExternalJob(externalJobId);
			analysisJobRepository.save(job);
			// 요청이 접수됐다는 사실을 응시에도 남긴다. 교육생 홈의 "분석 중" 표시 근거다.
			sessionPreparer.markAttemptsAnalyzing(target.getAssessmentRoundId(), target.getTeamId());
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
	 * <p>AI 서버가 job을 메모리에만 두어 재시작하면 404가 난다(스펙 명시). 그건 오류가 아니라 재요청
	 * 신호이므로 실패로 기록하지 않고 {@code external_job_id}를 지워 다음 배치가 다시 보내게 한다.
	 *
	 * @return 상태가 바뀐 실행 수
	 */
	@Transactional
	public int pollActiveJobs() {
		List<AnalysisJob> active = analysisJobRepository.findByStatusIn(
				List.of(AnalysisJobStatus.QUEUED, AnalysisJobStatus.RUNNING));
		int updated = 0;
		for (AnalysisJob job : active) {
			if (job.getExternalJobId() == null) {
				continue;
			}
			if (applyProgress(job)) {
				updated++;
			}
		}
		return updated;
	}

	private boolean applyProgress(AnalysisJob job) {
		AnalysisProgress progress = analysisServerClient.fetchProgress(job.getExternalJobId()).orElse(null);
		if (progress == null) {
			// AI 서버가 모르는 job이다. 재시작으로 유실됐다는 뜻이라 같은 execution_no로 다시 보낸다.
			log.warn("AI 서버가 모르는 작업이다. 재요청 대상으로 되돌린다: jobId={}, externalJobId={}",
					job.getJobId(), job.getExternalJobId());
			job.acceptExternalJob(null);
			return true;
		}
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
				yield true;
			}
			case SUCCEEDED, PARTIAL -> {
				recordResult(job, progress);
				job.markCompleted(progress.status(), progress.startedAt(),
						progress.completedAt() == null ? Instant.now() : progress.completedAt());
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
				yield true;
			}
		};
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
			sessionPreparer.markAttemptsAnalysisFailed(job.getAssessmentRoundId(), job.getTeamId());
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
			aiUsageRecorder.record(job, progress.aiUsage());
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
	 */
	private void recordResult(AnalysisJob job, AnalysisProgress progress) {
		if (!progress.hasResult()) {
			log.warn("성공 응답에 result 가 없어 적재를 건너뛴다: jobId={}, status={}",
					job.getJobId(), progress.status());
			return;
		}
		try {
			UUID analysisId = analysisResultRepository.record(job, progress.result());
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
}
