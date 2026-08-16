package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisModelRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisModelRepository.AnalysisModel;
import com.bigproject.backend.domain.reporting.domain.Report;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationItem;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationItemStatus;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationRun;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationTriggerType;
import com.bigproject.backend.domain.reporting.domain.ReportErrorCode;
import com.bigproject.backend.domain.reporting.domain.ReportException;
import com.bigproject.backend.domain.reporting.domain.ReportLifecycleStatus;
import com.bigproject.backend.domain.reporting.domain.ReportType;
import com.bigproject.backend.domain.reporting.infrastructure.JdbcReportPayloadRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportDispatchRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportDispatchRepository.ProblemDueTarget;
import com.bigproject.backend.domain.reporting.infrastructure.ReportDispatchRepository.ProblemTarget;
import com.bigproject.backend.domain.reporting.infrastructure.ReportDispatchRepository.ReportTarget;
import com.bigproject.backend.domain.reporting.infrastructure.ReportGenerationItemRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportGenerationRunRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ai.ReportGenerationAiClient;
import com.bigproject.backend.domain.reporting.infrastructure.ai.ReportGenerationJob;
import com.bigproject.backend.domain.reporting.infrastructure.ai.ReportGenerationRequest;
import com.bigproject.backend.domain.usagemetering.application.AiUsageAttribution;
import com.bigproject.backend.global.ai.AiCallException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 리포트 생성 배치.
 *
 * <p><b>대상은 "회차 종료 시각 이전에 응시해 정상 완료한 세션"</b>이고, 등록 시점은 회차 종료
 * 이후다. 판정은 전부 {@link ReportDispatchRepository#findDueSessions}가 SQL로 한다 — 어떤 세션이
 * 리포트를 받는지가 화면의 `완료` 집계와 어긋나면 안 되고, 그 정의는 뷰가 갖고 있기 때문이다.
 *
 * <h2>2단계인 이유</h2>
 *
 * <p>요청({@link #dispatchDueSessions})과 상태 갱신({@link #pollActiveItems})을 나눈다. AI 생성 1건이
 * <b>실측 129초</b>였고 리포트 하나에 문제 수만큼 호출이 나가므로, 한 스레드가 결과를 기다리는
 * 구조로는 회차 인원을 감당할 수 없다. {@code ReportGenerationServiceImpl}의 블로킹 폴링을 쓰지
 * 않는 이유이기도 하다(그쪽 상한은 2초 × 60회 = 120초라 위 실측을 이미 넘긴다).
 *
 * <h2>트랜잭션</h2>
 *
 * <p>이 클래스의 공개 메서드에 {@code @Transactional}을 걸지 않는다. 안에서 AI HTTP를 부르므로
 * 트랜잭션으로 감싸면 그동안 DB 커넥션이 풀에서 빠져나간 채로 묶인다. 각 {@code save()}가
 * 자기 완결적 트랜잭션이 되고, <b>여러 테이블을 한 번에 확정해야 하는 부분만</b> 별도 빈
 * ({@link ReportRunFinalizer})으로 뺐다.
 */
@Service
public class ReportBatchService {

	private static final Logger log = LoggerFactory.getLogger(ReportBatchService.class);

	/** {@code ck_report_generation_run_execution_no}가 0 이하를 막는다. */
	private static final int FIRST_EXECUTION = 1;

	/**
	 * 개인 회차 리포트의 타입.
	 *
	 * <p>{@code CHECKPOINT}가 아니라 이쪽인 이유: 미니프로젝트는 정의서가 {@code is_final=TRUE}를
	 * 요구하고 빅프로젝트는 제품에서 빠져서, 비최종 회차라는 것이 존재하지 않는다. 화면은
	 * {@code report_type}을 읽지 않으므로({@code RoundReportResponse}에 필드가 없고 뷰도 필터하지
	 * 않는다) 어느 값이든 그려지지만, 값이 갈리면 {@code uq_report_active_user}의 키가 갈려
	 * 같은 회차에 리포트가 둘 생길 수 있다.
	 */
	private static final ReportType REPORT_TYPE = ReportType.TRAINEE_FINAL;

	private final ReportDispatchRepository dispatchRepository;
	private final ReportRepository reportRepository;
	private final ReportGenerationRunRepository runRepository;
	private final ReportGenerationItemRepository itemRepository;
	private final JdbcReportPayloadRepository payloadRepository;
	private final ReportGenerationAiClient aiClient;
	private final AnalysisModelRepository modelRepository;
	private final ReportRunFinalizer finalizer;
	private final ObjectMapper objectMapper;

	/**
	 * 리포트 생성에 쓸 모델의 {@code ai_model.model_code}.
	 *
	 * <p>🔴 비워 두면 AI가 자기 기본 모델을 쓰고, 그 {@code modelCode}가 응답 {@code aiUsage[]}로
	 * 돌아온다. {@code ai_usage.model_code}는 {@code ai_model} FK라 카탈로그에 없으면 <b>사용량 행이
	 * 통째로 유실된다.</b> 그건 {@code monthly_ai_budget} 집행 근거가 조용히 비는 것이라 리포트
	 * 하나를 못 만드는 것보다 나쁘다.
	 */
	private final String modelCode;

	/** 회차·교육생 1건에 허용하는 총 실행 횟수(첫 시도 포함). */
	private final int maxAttempts;

	/**
	 * item 하나를 기다리는 상한. 넘기면 실패로 닫는다.
	 *
	 * <p>없으면 AI가 응답을 잃은 job이 {@code RUNNING}으로 영원히 남고, 그 실행에 묶인 리포트는
	 * 확정되지 않아 교육생 화면이 계속 `발행 전`이다.
	 */
	private final Duration itemTimeout;

	/**
	 * 설정값을 필드 {@code @Value}가 아니라 생성자 인자로 받는다. 필드 주입이면 단위 테스트에서
	 * 항상 기본값(null·0)이라 모델 조회와 상한이 조용히 빗나간다
	 * ({@code AnalysisBatchService}와 같은 이유).
	 */
	public ReportBatchService(
			ReportDispatchRepository dispatchRepository,
			ReportRepository reportRepository,
			ReportGenerationRunRepository runRepository,
			ReportGenerationItemRepository itemRepository,
			JdbcReportPayloadRepository payloadRepository,
			ReportGenerationAiClient aiClient,
			AnalysisModelRepository modelRepository,
			ReportRunFinalizer finalizer,
			ObjectMapper objectMapper,
			@Value("${ai.report.model-code}") String modelCode,
			@Value("${ai.report.max-attempts}") int maxAttempts,
			@Value("${ai.report.item-timeout}") Duration itemTimeout) {
		this.dispatchRepository = dispatchRepository;
		this.reportRepository = reportRepository;
		this.runRepository = runRepository;
		this.itemRepository = itemRepository;
		this.payloadRepository = payloadRepository;
		this.aiClient = aiClient;
		this.modelRepository = modelRepository;
		this.finalizer = finalizer;
		this.objectMapper = objectMapper;
		this.modelCode = modelCode;
		this.maxAttempts = maxAttempts;
		this.itemTimeout = itemTimeout;
	}

	/**
	 * 리포트를 만들 대상을 찾아 AI에 요청한다.
	 *
	 * <p>세션 1건마다 예외를 삼킨다 — 한 사람의 요청이 실패해도 같은 회차의 나머지는 나가야 한다.
	 * 실패한 대상은 run이 없거나 FAILED로 남고, {@link ReportDispatchRepository#BLOCKING_RUN_EXISTS}가
	 * FAILED를 막지 않으므로 다음 배치가 다시 집는다.
	 *
	 * @return 요청을 보낸 세션 수
	 */
	public int dispatchDueSessions() {
		List<ReportTarget> targets = dispatchRepository.findDueSessions(maxAttempts);
		warnAboutUnfinishedStages();
		if (targets.isEmpty()) {
			return 0;
		}

		AnalysisModel model = modelRepository.findActiveByModelCode(modelCode).orElse(null);
		if (model == null) {
			// 특정 대상의 문제가 아니라 기관 전체가 못 도는 설정 문제다. run 행을 만들어 두면
			// 설정을 고쳐도 재시도 상한만 깎이므로, 아무것도 남기지 않고 물러난다.
			log.error("ai.report.model-code 가 가리키는 ACTIVE 모델이 ai_model 에 없다. "
					+ "리포트 생성을 건너뛴다: modelCode={}, 대상={}건", modelCode, targets.size());
			return 0;
		}

		int dispatched = 0;
		for (ReportTarget target : targets) {
			try {
				dispatchOne(target, model, Instant.now(), ReportGenerationTriggerType.SCHEDULED);
				dispatched++;
			} catch (DataIntegrityViolationException exception) {
				// uq_report_generation_run_idempotency_key. 다른 인스턴스가 같은 대상을 먼저
				// 집었다는 뜻이라 오류가 아니다 — 스케줄러 락이 없는 상태의 정상 경로다.
				log.debug("이미 다른 실행이 접수했다: userId={}, roundId={}",
						target.getUserId(), target.getAssessmentRoundId());
			} catch (RuntimeException exception) {
				log.error("리포트 생성 요청 실패: userId={}, roundId={}, sessionId={}",
						target.getUserId(), target.getAssessmentRoundId(), target.getSessionId(), exception);
			}
		}
		return dispatched;
	}

	/**
	 * 끝난 <b>문제</b>를 찾아 AI에 요청한다. 리포트 생성의 기본 경로다.
	 *
	 * <h2>세션이 끝나기를 기다리지 않는다</h2>
	 *
	 * <p>학생이 문제 1을 끝내고 문제 2로 넘어가는 동안 문제 1을 만들어 둔다. 마지막 문제가 끝나면
	 * 앞의 것들이 이미 준비돼 있어 <b>학생이 기다리는 시간이 마지막 문제 하나로 줄어든다.</b>
	 *
	 * <h2>run은 학생당 하나다</h2>
	 *
	 * <p>문제마다 요청을 보내지만 <b>실행(run)은 재사용</b>한다 — 첫 문제에서 만들고 이후 문제는
	 * 같은 run에 item만 붙인다. 문제마다 run을 만들면 스냅샷이 문제 수만큼 생긴다
	 * ({@code uq_report_snapshot_generation_run_id}).
	 *
	 * <p>그래서 <b>같은 세션의 문제들을 묶어서</b> 처리한다. 한 번에 여러 문제가 걸려 나올 수 있는데
	 * (예: 배치가 잠깐 멈춘 사이 2·3번이 다 끝남) 그때도 run은 하나다.
	 *
	 * <h2>실패는 세션 단위로 삼킨다</h2>
	 *
	 * <p>한 학생의 요청이 실패해도 같은 회차의 나머지는 나가야 한다. 실패한 문제는 item이 없거나
	 * {@code FAILED}로 남고, 대상 조회가 <b>item이 없는 문제</b>를 다시 집으므로 다음 주기에 재시도된다.
	 *
	 * @return 요청을 보낸 문제 수
	 */
	public int dispatchDueProblems() {
		List<ProblemDueTarget> targets = dispatchRepository.findDueProblems(maxAttempts);
		if (targets.isEmpty()) {
			return 0;
		}

		AnalysisModel model = modelRepository.findActiveByModelCode(modelCode).orElse(null);
		if (model == null) {
			// 특정 대상의 문제가 아니라 기관 전체가 못 도는 설정 문제다. run 행을 만들어 두면
			// 설정을 고쳐도 재시도 상한만 깎이므로, 아무것도 남기지 않고 물러난다.
			log.error("ai.report.model-code 가 가리키는 ACTIVE 모델이 ai_model 에 없다. "
					+ "리포트 생성을 건너뛴다: modelCode={}, 대상={}건", modelCode, targets.size());
			return 0;
		}

		// 같은 세션의 문제를 한 묶음으로 — run 을 한 번만 확보하려고.
		Map<UUID, List<ProblemDueTarget>> bySession = targets.stream()
				.collect(Collectors.groupingBy(ProblemDueTarget::getSessionId, LinkedHashMap::new,
						Collectors.toList()));

		int dispatched = 0;
		for (List<ProblemDueTarget> group : bySession.values()) {
			try {
				dispatched += dispatchProblems(group, model, Instant.now());
			} catch (DataIntegrityViolationException exception) {
				// 다른 인스턴스가 같은 대상을 먼저 집었다는 뜻이라 오류가 아니다.
				log.debug("이미 다른 실행이 접수했다: sessionId={}", group.get(0).getSessionId());
			} catch (RuntimeException exception) {
				log.error("리포트 생성 요청 실패: sessionId={}, 문제 {}건",
						group.get(0).getSessionId(), group.size(), exception);
			}
		}
		return dispatched;
	}

	/**
	 * 한 세션에서 이번에 끝난 문제들을 요청한다.
	 *
	 * <p>순서는 세션 단위 경로와 같다 — <b>조립 → run·item 저장 → AI 호출</b>. 조립하다 실패하면
	 * run 행을 남기지 않는 편이 맞고(재시도 상한만 깎는다), item 행은 AI 호출보다 먼저 있어야 한다
	 * (202를 받았는데 행이 없는 순간이 생기면 jobId를 잃는다).
	 */
	private int dispatchProblems(List<ProblemDueTarget> problems, AnalysisModel model, Instant now) {
		ProblemDueTarget any = problems.get(0);

		List<PreparedRequest> prepared = problems.stream()
				.map(problem -> prepare(any, new SingleProblem(problem.getProblemId(), problem.getProblemNo()), model))
				.toList();

		Report report = resolveReport(any);
		ReportGenerationRun run = resolveRun(report, prepared, now);
		String scoreRunId = run.getGenerationRunId().toString();

		for (PreparedRequest request : prepared) {
			sendOne(request, run, any.getSessionId(), scoreRunId, now);
		}

		run.markRunning(now);
		runRepository.save(run);

		log.info("리포트 생성 요청(문제 단위): reportId={}, runId={}, 문제 {}건, 실행 {}회차",
				report.getReportId(), run.getGenerationRunId(), prepared.size(), run.getExecutionNo());
		return prepared.size();
	}

	/**
	 * 진행 중인 실행이 있으면 재사용하고, 없으면 만든다.
	 *
	 * <p>🔴 <b>재사용할 때 {@code request_fingerprint}는 갱신하지 않는다.</b> 그 값은 "같은
	 * idempotency_key 로 다른 요청이 오지 않았는가"를 보는 지문인데, 문제 단위 dispatch에서는
	 * item이 나중에 붙는 것이 정상이라 매번 달라진다. <b>첫 묶음 기준</b>으로 남겨 둔다.
	 */
	private ReportGenerationRun resolveRun(Report report, List<PreparedRequest> prepared, Instant now) {
		return runRepository
				.findActiveByReportIdAndTriggerType(report.getReportId(), ReportGenerationTriggerType.SCHEDULED)
				.orElseGet(() -> {
					int executionNo = nextExecutionNo(report.getReportId());
					return runRepository.save(ReportGenerationRun.queued(
							report.getReportId(),
							ReportGenerationTriggerType.SCHEDULED,
							idempotencyKeyOf(report.getReportId(), executionNo),
							ReportRunFinalizer.CALCULATION_VERSION,
							executionNo,
							fingerprintOf(prepared)
					));
				});
	}

	/** item 하나를 저장하고 AI에 보낸다. 세션 단위 경로와 같은 순서다. */
	private void sendOne(PreparedRequest request, ReportGenerationRun run, UUID sessionId,
			String scoreRunId, Instant now) {

		ReportGenerationRequest body = request.body().withScoreRunId(scoreRunId);
		String payload = ReportPayloads.toJson(objectMapper, body);

		ReportGenerationItem item = itemRepository.save(ReportGenerationItem.queued(
				run.getGenerationRunId(),
				request.problemId(),
				sessionId,
				request.problemNo(),
				payload,
				ReportPayloads.sha256Hex(payload),
				ReportRunFinalizer.PAYLOAD_SCHEMA_VERSION,
				scoreRunId
		));

		requestGeneration(item, body, now);
	}

	/** {@link ProblemTarget}을 만족시키는 최소 구현. 문제 단위 조회 결과를 조립 코드에 그대로 넘긴다. */
	private record SingleProblem(UUID problemId, Integer problemNo) implements ProblemTarget {

		@Override
		public UUID getProblemId() {
			return problemId;
		}

		@Override
		public Integer getProblemNo() {
			return problemNo;
		}
	}

	/**
	 * 정리되지 않은 stage 때문에 빠진 세션이 있으면 남긴다.
	 *
	 * <p>{@code NO_UNFINISHED_STAGE}가 조용히 걸러 버리면 "왜 리포트가 안 생기지"를 되짚을 단서가
	 * 없다. 세션은 끝났다고 표시됐는데 단계가 {@code PREPARED}/{@code IN_PROGRESS}로 남았다는 뜻이라
	 * <b>세션 종료 로직 쪽 문제</b>이고, 이쪽에서 고칠 수 있는 것이 아니다 — 그래서 대상 수가 아니라
	 * 원인을 로그에 적는다.
	 *
	 * <p>세는 것 자체가 실패해도 배치는 계속 간다. 경고를 못 남긴 것이 요청을 막을 이유는 없다.
	 */
	private void warnAboutUnfinishedStages() {
		try {
			long blocked = dispatchRepository.countSessionsWithUnfinishedStages();
			if (blocked > 0) {
				log.warn("정리되지 않은 단계가 남아 리포트를 만들지 않은 세션 {}건. "
						+ "세션 종료 시 남은 problem_stage 가 NOT_REACHED/NOT_ANSWERED 로 "
						+ "정리되지 않았다 — 그대로 보내면 도달하지 못한 축이 대표로 잡힌다", blocked);
			}
		} catch (RuntimeException exception) {
			log.warn("미정리 세션 수를 세지 못했다", exception);
		}
	}

	/**
	 * 세션 1건의 리포트를 <b>운영자 판단으로 다시 만든다.</b> 배치가 다시 집지 못하는 대상을 푸는
	 * 유일한 경로다.
	 *
	 * <h2>이것 말고 복구 수단이 없다</h2>
	 *
	 * <p>배치는 두 가지 이유로 대상을 영구히 놓칠 수 있고, 둘 다 이 메서드로만 풀린다.
	 * <ul>
	 *   <li><b>한계 1</b> — {@code ai.report.max-attempts}를 소진했다</li>
	 *   <li><b>한계 7</b> — 문제 1개만 실패해 run이 {@code PARTIAL}로 닫혔다.
	 *       {@code BLOCKING_RUN_EXISTS}가 {@code FAILED}만 통과시키므로 <b>상한과 무관하게</b>
	 *       다시 집히지 않는다. 상한 소진보다 흔하다</li>
	 * </ul>
	 *
	 * <p>대상을 찾는 방법은 통합본 §6의 모니터링 쿼리 두 개다.
	 *
	 * <h2>🔴 상한이 사라지는 게 아니라 사람에게 옮겨간다</h2>
	 *
	 * <p>{@code USER_REQUESTED} 실행은 {@code UNDER_ATTEMPT_LIMIT}가 세지 않으므로 <b>몇 번이든
	 * 다시 부를 수 있다.</b> 그래서 누가·언제·몇 번째인지를 로그로 남긴다 — 같은 대상을 반복해서
	 * 누르고 있으면 그건 이 도구가 아니라 AI 쪽 문제이고, 운영에서 그게 보여야 한다.
	 *
	 * <p>동기 경로({@code ReportGenerationService})로 부르고 싶어지는 지점이지만 그러면 안 된다.
	 * 여기도 202만 받고 끊고, 결과는 기존 {@link #pollActiveItems()}가 회수한다.
	 *
	 * <p>아직 화면이 없어 컨트롤러를 두지 않았다. 운영자 화면이 생기면 이 메서드를 부르는 얇은
	 * 엔드포인트 하나면 된다 — 매핑표에 자리가 잡힌 뒤에 붙이는 것이 맞다.
	 *
	 * @param sessionId   {@code assessment_session.session_id}
	 * @param requestedBy 누가 눌렀는지. 로그에만 쓴다 — 감사 원장은 이 경로의 책임이 아니다
	 * @return 요청을 보냈으면 그 {@code generation_run_id}. 대상이 아니거나 문제가 없으면 빈 값
	 */
	public Optional<UUID> regenerateSession(UUID sessionId, String requestedBy) {
		ReportTarget target = dispatchRepository.findTargetBySession(sessionId).orElse(null);
		if (target == null) {
			// 세션이 없는 것과 조건에 안 맞는 것을 구분하지 않는다 — 어느 쪽이든 만들면 안 된다.
			log.warn("재생성 대상이 아니다: sessionId={}, requestedBy={}", sessionId, requestedBy);
			return Optional.empty();
		}

		AnalysisModel model = modelRepository.findActiveByModelCode(modelCode).orElse(null);
		if (model == null) {
			log.error("ai.report.model-code 가 가리키는 ACTIVE 모델이 ai_model 에 없다. "
					+ "재생성을 건너뛴다: modelCode={}, sessionId={}", modelCode, sessionId);
			return Optional.empty();
		}

		Optional<UUID> runId;
		try {
			runId = dispatchOne(target, model, Instant.now(), ReportGenerationTriggerType.USER_REQUESTED);
		} catch (DataIntegrityViolationException exception) {
			/*
			 * uq_report_generation_run_active 는 (report_id, trigger_type) 부분 유니크다
			 * (status IN QUEUED·RUNNING·RETRYING).
			 *
			 * 즉 진행 중인 수동 재생성이 이미 있다는 뜻이다 — 운영자가 두 번 눌렀거나, 앞의 것이
			 * 아직 폴링 중이다. 오류가 아니라 "이미 돌고 있다"이므로 예외를 밖으로 던지지 않는다.
			 *
			 * SCHEDULED 실행과는 부딪히지 않는다. trigger_type 이 인덱스 키에 있어서, 배치가 돌고
			 * 있어도 수동 재생성은 걸린다 — 그게 이 도구가 필요한 상황이기도 하다.
			 */
			log.warn("이미 진행 중인 재생성이 있다: sessionId={}, requestedBy={}", sessionId, requestedBy);
			return Optional.empty();
		}

		runId.ifPresent(id -> log.info(
				"운영자 재생성 요청: sessionId={}, userId={}, roundId={}, runId={}, requestedBy={}",
				sessionId, target.getUserId(), target.getAssessmentRoundId(), id, requestedBy));
		return runId;
	}

	/**
	 * 세션 1건의 리포트를 <b>조건을 보지 않고 만든다.</b> 연동 시험 전용이다.
	 *
	 * <h2>{@link #regenerateSession}과 무엇이 다른가</h2>
	 *
	 * <table>
	 *   <caption>세 경로의 조건</caption>
	 *   <tr><th></th><th>배치</th><th>{@code regenerateSession}</th><th>이 메서드</th></tr>
	 *   <tr><td>횟수 상한·중복 차단</td><td>✅ 본다</td><td>❌ 안 본다</td><td>❌ 안 본다</td></tr>
	 *   <tr><td>세션·응시 완료</td><td>✅</td><td>✅</td><td><b>❌</b></td></tr>
	 *   <tr><td>종료 사유 6종</td><td>✅</td><td>✅</td><td><b>❌</b></td></tr>
	 *   <tr><td>무효 확인</td><td>✅</td><td>✅</td><td><b>❌</b></td></tr>
	 *   <tr><td>단계 정리</td><td>✅</td><td>✅</td><td><b>❌</b></td></tr>
	 *   <tr><td>발행 예정 시각</td><td>✅</td><td>✅</td><td><b>❌</b></td></tr>
	 * </table>
	 *
	 * <p>즉 <b>세션 ID만 맞으면 무조건 AI를 부른다.</b> 8필드 조립과 AI 계약을 확인하는 것이
	 * 목적이라, 회차 마감을 기다리거나 {@code assessment_due_at}을 손대는 대신 조건을 타지 않는
	 * 경로를 따로 둔다.
	 *
	 * <h2>🔴 그래도 발행은 막힌다</h2>
	 *
	 * <p>여기서 만든 리포트가 학생에게 나가지는 않는다. {@link ReportRunFinalizer}가 확정 직전에
	 * 세션 유효성과 발행 예정 시각을 <b>다시</b> 보고({@code findFinalizeContext}), 어긋나면
	 * 스냅샷만 만들고 {@code published_at}을 비워 둔다. 생성 게이트를 푸는 것과 발행 게이트를 푸는
	 * 것은 별개이고, 이 메서드는 앞의 것만 푼다.
	 *
	 * <p>⚠️ 그래서 <b>LLM 비용은 회수되지 않는다.</b> 무효 세션에도 요청이 나가고 결과는 발행되지
	 * 않는다. 이 경로를 여는 엔드포인트가 {@code ai.report.force-endpoint.enabled}로 꺼져 있는 이유다.
	 *
	 * @param sessionId   {@code assessment_session.session_id}
	 * @param requestedBy 누가 눌렀는지. 로그에만 쓴다
	 * @return 만든 실행의 {@code generation_run_id}
	 * @throws com.bigproject.backend.domain.reporting.domain.ReportException 세션이 없거나, 문제가
	 *         없거나, 이미 진행 중인 수동 실행이 있거나, 모델 설정이 어긋났을 때
	 */
	public UUID forceGenerateSession(UUID sessionId, String requestedBy) {
		ReportTarget target = dispatchRepository.findSessionContext(sessionId)
				.orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_SESSION_NOT_FOUND));

		AnalysisModel model = modelRepository.findActiveByModelCode(modelCode).orElse(null);
		if (model == null) {
			log.error("ai.report.model-code 가 가리키는 ACTIVE 모델이 ai_model 에 없다: modelCode={}", modelCode);
			throw new ReportException(ReportErrorCode.REPORT_MODEL_NOT_CONFIGURED);
		}

		log.warn("🔴 강제 리포트 생성: sessionId={}, userId={}, roundId={}, requestedBy={} "
						+ "— 세션 유효성·발행 시각을 보지 않는 경로다",
				sessionId, target.getUserId(), target.getAssessmentRoundId(), requestedBy);

		UUID runId;
		try {
			runId = dispatchOne(target, model, Instant.now(), ReportGenerationTriggerType.USER_REQUESTED)
					.orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_SESSION_HAS_NO_PROBLEM));
		} catch (DataIntegrityViolationException exception) {
			// uq_report_generation_run_active 는 (report_id, trigger_type) 부분 유니크다.
			// 앞의 수동 실행이 아직 QUEUED/RUNNING/RETRYING 이라는 뜻이다.
			throw new ReportException(ReportErrorCode.REPORT_GENERATION_ALREADY_RUNNING);
		}

		log.info("강제 리포트 생성 요청 완료: sessionId={}, runId={}, requestedBy={}",
				sessionId, runId, requestedBy);
		return runId;
	}

	/**
	 * 대상 1건을 요청한다.
	 *
	 * <p>순서가 중요하다. <b>run과 item을 먼저 저장하고</b> AI를 부른다. 반대로 하면 202를 받고도
	 * 행이 없는 순간이 생기고, 그 사이 프로세스가 죽으면 AI에는 실행이 있는데 우리 원장에는 없다.
	 * {@code request_payload}가 NOT NULL이라 스키마도 이 순서를 강제한다.
	 *
	 * @param triggerType {@code SCHEDULED}면 {@code UNDER_ATTEMPT_LIMIT}가 계수하고
	 *                    {@code USER_REQUESTED}면 세지 않는다({@code ReportDispatchRepository} 참고)
	 * @return 만든 실행의 {@code generation_run_id}. 세션에 문제가 없어 아무것도 만들지 않았으면 빈 값
	 */
	private Optional<UUID> dispatchOne(ReportTarget target, AnalysisModel model, Instant now,
			ReportGenerationTriggerType triggerType) {
		List<ProblemTarget> problems = dispatchRepository.findSessionProblems(target.getSessionId());
		if (problems.isEmpty()) {
			// 문제가 없으면 만들 리포트도 없다. run을 남기면 영원히 확정되지 않는 실행이 된다.
			log.warn("세션에 문제가 없어 리포트를 만들지 않는다: sessionId={}", target.getSessionId());
			return Optional.empty();
		}

		Report report = resolveReport(target);
		int executionNo = nextExecutionNo(report.getReportId());

		// 요청 본문을 먼저 다 만든다. 여기서 실패하면 run 행을 남기지 않는 편이 맞다 —
		// 재시도 상한만 깎고 아무것도 못 하기 때문이다.
		List<PreparedRequest> prepared = problems.stream()
				.map(problem -> prepare(target, problem, model))
				.toList();

		ReportGenerationRun run = runRepository.save(ReportGenerationRun.queued(
				report.getReportId(),
				triggerType,
				idempotencyKeyOf(report.getReportId(), executionNo),
				ReportRunFinalizer.CALCULATION_VERSION,
				executionNo,
				fingerprintOf(prepared)
		));

		/*
		 * scoreRunId를 generation_run_id로 둔다.
		 *
		 * 🔴 비워 두면 AI 멱등키({problemId}:{scoreRunId})가 문제당 상수가 된다. 그러면 재생성 때
		 * AI가 처음 jobId를 그대로 돌려주고, uq_report_generation_item_external_job_id 가 두 번째
		 * item을 23505로 거부한다. 실행마다 달라지는 값이어야 한다.
		 */
		String scoreRunId = run.getGenerationRunId().toString();

		for (PreparedRequest request : prepared) {
			ReportGenerationRequest body = request.body().withScoreRunId(scoreRunId);
			String payload = ReportPayloads.toJson(objectMapper, body);

			ReportGenerationItem item = itemRepository.save(ReportGenerationItem.queued(
					run.getGenerationRunId(),
					request.problemId(),
					target.getSessionId(),
					request.problemNo(),
					payload,
					ReportPayloads.sha256Hex(payload),
					ReportRunFinalizer.PAYLOAD_SCHEMA_VERSION,
					scoreRunId
			));

			requestGeneration(item, body, now);
		}

		run.markRunning(now);
		runRepository.save(run);

		// executionNo가 "이 리포트의 몇 번째 실행인가"다. 수동 재생성이 상한을 타지 않으므로
		// 이 값이 계속 오르고 있으면 재생성으로 덮이지 않는 문제가 있다는 뜻이다.
		log.info("리포트 생성 요청: reportId={}, runId={}, 문제 {}건, trigger={}, 실행 {}회차",
				report.getReportId(), run.getGenerationRunId(), prepared.size(), triggerType, executionNo);
		return Optional.of(run.getGenerationRunId());
	}

	/** item 하나를 AI에 보낸다. 실패는 그 item만 닫고 나머지 문제는 계속 보낸다. */
	private void requestGeneration(ReportGenerationItem item, ReportGenerationRequest body, Instant now) {
		try {
			ReportGenerationJob.Accepted accepted = aiClient.requestGeneration(body, traceIdOf(item));
			item.acceptExternalJob(accepted.externalJobId(), now);
		} catch (AiCallException exception) {
			// 요청이 거절되면 그 자체가 실패다. QUEUED로 두면 폴링이 붙들고 있는데
			// external_job_id 가 없어 조회할 대상조차 없다.
			item.markFailed("AI가 리포트 생성 요청을 접수하지 못했습니다: " + exception.getMessage(),
					null, null, now);
			log.warn("리포트 생성 요청 거절: problemId={}, retryable={}",
					item.getProblemId(), exception.retryable());
		}
		itemRepository.save(item);
	}

	/**
	 * 진행 중인 item의 상태를 갱신하고, 실행이 다 끝났으면 확정한다.
	 *
	 * @return 상태가 바뀐 item 수
	 */
	public int pollActiveItems() {
		List<ReportGenerationItem> active = itemRepository.findByStatusIn(
				List.of(ReportGenerationItemStatus.QUEUED, ReportGenerationItemStatus.RUNNING));
		if (active.isEmpty()) {
			return 0;
		}

		Instant now = Instant.now();
		Set<UUID> touchedRuns = new LinkedHashSet<>();
		int updated = 0;

		for (ReportGenerationItem item : active) {
			// 어느 item이 끝났든 그 실행은 확정 판정을 다시 받아야 한다. 이번 폴링에서 상태가
			// 안 바뀐 실행도 넣는다 — 앞선 폴링에서 마지막 item이 끝났는데 확정이 실패했을 수 있다.
			touchedRuns.add(item.getGenerationRunId());
			try {
				if (applyProgress(item, now)) {
					updated++;
				}
			} catch (RuntimeException exception) {
				log.error("리포트 상태 갱신 실패: itemId={}", item.getGenerationItemId(), exception);
			}
		}

		for (UUID runId : touchedRuns) {
			try {
				finalizer.finalizeIfComplete(runId, Instant.now());
			} catch (RuntimeException exception) {
				log.error("리포트 확정 실패: runId={}", runId, exception);
			}
		}
		return updated;
	}

	/** item 하나의 진행 상태를 읽어 반영한다. */
	private boolean applyProgress(ReportGenerationItem item, Instant now) {
		if (item.getExternalJobId() == null) {
			// 요청이 접수되지 않은 채 QUEUED로 남았다. 상한을 넘겼으면 닫는다.
			return failIfTimedOut(item, now, "AI가 작업을 접수하지 않았습니다.");
		}

		ReportGenerationJob.Status job;
		try {
			job = aiClient.fetchJob(item.getExternalJobId().toString(), traceIdOf(item),
					attributionOf(item), item.getGenerationItemId());
		} catch (AiCallException exception) {
			if (exception.retryable()) {
				// 일시적 장애다. 다음 폴링이 다시 묻고, 무한 대기는 itemTimeout이 막는다.
				return failIfTimedOut(item, now, "AI 상태 조회가 계속 실패했습니다: " + exception.getMessage());
			}
			// AI가 모르는 job(재시작으로 유실 등)이다. 다시 물어도 같은 답이라 여기서 닫는다.
			item.markFailed("AI가 작업을 찾지 못했습니다: " + exception.getMessage(), null, null, now);
			itemRepository.save(item);
			return true;
		}

		if (!job.isTerminal()) {
			return failIfTimedOut(item, now, "AI 리포트 생성이 제한 시간 안에 끝나지 않았습니다.");
		}

		String responsePayload = job.result() == null ? null : ReportPayloads.toJson(objectMapper, job.result());
		Instant completedAt = job.completedAt() == null ? now : job.completedAt();

		if (job.isFailed()) {
			item.markFailed(
					job.failureReason() == null || job.failureReason().isBlank()
							? "AI가 실패 사유를 주지 않았습니다." : job.failureReason(),
					responsePayload,
					responsePayload == null ? null : ReportPayloads.sha256Hex(responsePayload),
					completedAt);
		} else {
			// AI의 PARTIAL은 "점수는 냈지만 서술이 없다"이다. item은 SUCCEEDED로 두고
			// narrative_failed 로 표시한다 — 결과가 왔고 토큰도 태웠으므로 실패가 아니다.
			item.markSucceeded(
					responsePayload,
					responsePayload == null ? null : ReportPayloads.sha256Hex(responsePayload),
					narrativeFailed(job),
					completedAt);
		}
		itemRepository.save(item);
		return true;
	}

	/** 상한을 넘긴 item을 닫는다. 아직이면 그대로 둔다. */
	private boolean failIfTimedOut(ReportGenerationItem item, Instant now, String reason) {
		Instant since = item.getStartedAt() == null ? item.getCreatedAt() : item.getStartedAt();
		if (since == null || since.plus(itemTimeout).isAfter(now)) {
			return false;
		}
		item.markFailed(reason, null, null, now);
		itemRepository.save(item);
		log.warn("리포트 item 제한 시간 초과로 종료: itemId={}, problemId={}",
				item.getGenerationItemId(), item.getProblemId());
		return true;
	}

	/** AI가 서술 생성에 실패했는가. 응답 상태 {@code PARTIAL}과 결과의 플래그를 함께 본다. */
	private static boolean narrativeFailed(ReportGenerationJob.Status job) {
		if ("PARTIAL".equals(job.status())) {
			return true;
		}
		return job.result() != null && job.result().path("narrativeFailed").asBoolean(false);
	}

	/**
	 * 이 회차·교육생의 리포트를 찾거나 만든다.
	 *
	 * <p>기존 행이 있으면 <b>재사용한다.</b> {@code uq_report_active_user}가
	 * {@code (assessment_round_id, user_id, report_type)}에 걸려 있어(ACTIVE 한정), 재생성 때마다 새
	 * 행을 만들면 두 번째 발행이 23505로 막힌다. 같은 행에 새 스냅샷을 붙이는 것이 스키마의 의도다
	 * ({@code Report} javadoc: "재생성하면 새 스냅샷이 생기고 이전 것은 비활성으로 내려간다").
	 *
	 * <p>{@code SUPERSEDED}만 있으면 새로 만든다 — 그건 명시적으로 폐기된 이력이다.
	 */
	private Report resolveReport(ReportTarget target) {
		return reportRepository
				.findRoundReports(target.getAssessmentRoundId(), target.getUserId(), REPORT_TYPE)
				.stream()
				.filter(report -> report.getLifecycleStatus() != ReportLifecycleStatus.SUPERSEDED)
				// ACTIVE가 DRAFT보다 앞선다. 둘 다 있으면 화면이 보고 있는 쪽에 붙여야 한다.
				.min(Comparator.comparing(report -> report.getLifecycleStatus() == ReportLifecycleStatus.ACTIVE
						? 0 : 1))
				.orElseGet(() -> reportRepository.save(Report.forTrainee(
						target.getOrgId(), target.getCohortId(), target.getUserId(),
						target.getAssessmentRoundId())));
	}

	/** 요청 본문과 그 문제의 식별자를 함께 들고 다닌다. */
	private record PreparedRequest(UUID problemId, Integer problemNo, ReportGenerationRequest body) {
	}

	/**
	 * 문제 1건의 AI 요청 본문을 만든다.
	 *
	 * <p>{@code problemNo}를 반드시 넣는다 — 생략하면 AI가 1로 간주해서 2·3번 문제의 리포트가
	 * 모두 `문제 1`로 표시된다(AI 스키마 설명).
	 */
	private PreparedRequest prepare(ReportTarget target, ProblemTarget problem, AnalysisModel model) {
		ReportGenerationRequest body = new ReportGenerationRequest(
				problem.getProblemId(),
				problem.getProblemNo(),
				target.getSessionId(),
				// scoreRunId는 run을 만든 뒤에 채운다. 지금은 generation_run_id가 없다.
				null,
				/*
				 * 🔴 model.getProviderModelCode() 가 아니라 설정값(ai.report.model-code)을 보낸다.
				 *
				 * 정의상으로는 provider_model_code 가 맞다(DDL: "공급자 원본 모델 식별자").
				 * 그런데 운영 DB의 그 컬럼에는 접두어가 빠져 있고(minimax-m3), 공급자가 요구하는
				 * 형식은 접두어 포함이다(minimaxai/minimax-m3 — OpenRouter 슬러그).
				 * 설정값이 마침 그 형식이라 그대로 쓴다.
				 *
				 * ai_model 조회는 그대로 둔다 — "그 모델이 ACTIVE 로 존재하는가"를 확인하는 값이고,
				 * 그게 없으면 아예 요청하지 않는 편이 맞다.
				 *
				 * 데이터가 정리되면(provider_model_code 에 접두어 반영) getProviderModelCode() 로
				 * 되돌리는 것이 맞다. model_code 는 "화면·API·집계용 불변 키"라 우리 사정으로 바뀔
				 * 수 있고, 그때 두 값이 갈라지면 AI 에 우리 키가 나간다.
				 * 되돌릴 때는 sendsThePrefixedModelCode... 테스트를 먼저 고칠 것.
				 */
				modelCode,
				payloadRepository.findTranscript(target.getSessionId(), problem.getProblemId()),
				payloadRepository.findAnalysisDocuments(target.getCodeAnalysisId()),
				payloadRepository.findTeaches(problem.getProblemId())
		);
		return new PreparedRequest(problem.getProblemId(), problem.getProblemNo(), body);
	}

	/**
	 * 이 실행이 무엇을 요청했는지의 지문. 문제별 본문을 이어 붙여 한 번 해싱한다.
	 *
	 * <p>{@code scoreRunId}가 아직 없는 상태의 본문으로 계산한다 — 그 값은 실행마다 달라지므로
	 * 포함하면 <b>입력이 같아도 지문이 늘 달라져</b> "같은 채점을 다시 돌렸는가"를 답할 수 없다.
	 */
	private String fingerprintOf(List<PreparedRequest> prepared) {
		StringBuilder joined = new StringBuilder();
		for (PreparedRequest request : prepared) {
			joined.append(ReportPayloads.toJson(objectMapper, request.body())).append('\n');
		}
		return ReportPayloads.sha256Hex(joined.toString());
	}

	/**
	 * 이 리포트의 다음 {@code execution_no}. 첫 시도는 1, 재시도는 직전 최대값 + 1이다.
	 *
	 * <p>이 값이 {@code idempotency_key}의 일부라 <b>재시도마다 달라져야 한다.</b> 고정하면
	 * {@code uq_report_generation_run_idempotency_key}가 재시도 자체를 막는다.
	 */
	private int nextExecutionNo(UUID reportId) {
		Integer max = runRepository.findMaxExecutionNo(reportId);
		return max == null ? FIRST_EXECUTION : max + 1;
	}

	private static String idempotencyKeyOf(UUID reportId, int executionNo) {
		return "rgr:" + reportId + ":" + executionNo;
	}

	/**
	 * 분산 추적 ID. item PK를 그대로 쓴다 — {@code ai_usage.trace_id}로 남으므로, 원장 한 줄에서
	 * 어느 호출이었는지 바로 되짚을 수 있어야 한다.
	 */
	private static String traceIdOf(ReportGenerationItem item) {
		return item.getGenerationItemId().toString();
	}

	/**
	 * 사용량 원장의 귀속. {@code context_type}을 {@code REPORT_GENERATION_ITEM}으로 덮는다 —
	 * AI는 그 값을 보낼 수 없고(enum 5종), 스냅샷 단위로 묶으면 문제별 원가가 사라진다.
	 *
	 * <p>반 축({@code class_id})은 넣지 않는다. 대상 조회가 반을 읽지 않고, 리포트 비용은
	 * 기수·프로젝트 축으로 집계하면 충분하다 — {@code class_attribution_status}가
	 * {@code NOT_APPLICABLE}로 남아 "실패"가 아니라 "해당 없음"으로 드러난다.
	 */
	private AiUsageAttribution attributionOf(ReportGenerationItem item) {
		return dispatchRepository.findUsageContext(item.getGenerationRunId())
				.map(context -> AiUsageAttribution.reportGenerationItem(
						context.getOrgId(), context.getCohortId(), null, context.getProjectId(),
						item.getGenerationItemId()))
				// 맥락을 못 읽어도 원장은 남긴다. 미귀속으로 남는 편이 비용 기록 자체를 잃는 것보다 낫다.
				.orElseGet(() -> AiUsageAttribution.reportGenerationItem(
						null, null, null, null, item.getGenerationItemId()));
	}
}
