package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisModelRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisModelRepository.AnalysisModel;
import com.bigproject.backend.domain.reporting.domain.Report;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationItem;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationItemStatus;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationRun;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationRunStatus;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationTriggerType;
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
import com.bigproject.backend.global.ai.AiCallException;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 서버 없이 배치 로직을 검증한다.
 *
 * <p>여기서 <b>확인할 수 없는 것</b>이 하나 있다 — 대상 선별 규칙(회차 종료 시각, 정상 완료,
 * 종료 사유 6종 제외)은 전부 {@link ReportDispatchRepository}의 네이티브 SQL 안에 있어서,
 * 리포지토리를 대역으로 바꾸는 이 테스트로는 검증되지 않는다. 그쪽은 실제 Postgres에 DDL과
 * 시드를 올려 쿼리를 직접 실행해야 한다.
 */
class ReportBatchServiceTest {

	/** 문제 단위 조회 결과가 공유하는 세션 축. 한 세션의 문제들은 이 값이 같아야 한다. */
	private static final UUID ATTEMPT = UUID.randomUUID();
	private static final UUID USER = UUID.randomUUID();
	private static final UUID ORG = UUID.randomUUID();
	private static final UUID COHORT = UUID.randomUUID();
	private static final UUID PROJECT = UUID.randomUUID();
	private static final UUID ROUND = UUID.randomUUID();
	private static final UUID ANALYSIS = UUID.randomUUID();

	private static final String MODEL_CODE = "minimaxai/minimax-m3";
	private static final String PROVIDER_MODEL_CODE = "minimax-m3";
	private static final int MAX_ATTEMPTS = 3;
	private static final Duration ITEM_TIMEOUT = Duration.ofMinutes(10);
	private static final int BATCH_SIZE = 20;
	/** 전환 컷오프. 테스트 대상은 이 시각 이후에 시작한 것으로 본다. */
	private static final Instant CUTOFF_AT = Instant.parse("2026-08-16T05:45:00Z");

	private ReportDispatchRepository dispatchRepository;
	private ReportRepository reportRepository;
	private ReportGenerationRunRepository runRepository;
	private ReportGenerationItemRepository itemRepository;
	private JdbcReportPayloadRepository payloadRepository;
	private ReportGenerationAiClient aiClient;
	private AnalysisModelRepository modelRepository;
	private ReportRunFinalizer finalizer;
	private ReportBatchService service;

	@BeforeEach
	void setUp() {
		dispatchRepository = mock(ReportDispatchRepository.class);
		reportRepository = mock(ReportRepository.class);
		runRepository = mock(ReportGenerationRunRepository.class);
		itemRepository = mock(ReportGenerationItemRepository.class);
		payloadRepository = mock(JdbcReportPayloadRepository.class);
		aiClient = mock(ReportGenerationAiClient.class);
		modelRepository = mock(AnalysisModelRepository.class);
		finalizer = mock(ReportRunFinalizer.class);

		service = new ReportBatchService(dispatchRepository, reportRepository, runRepository,
				itemRepository, payloadRepository, aiClient, modelRepository, finalizer,
				new ObjectMapper(), MODEL_CODE, MAX_ATTEMPTS, ITEM_TIMEOUT, BATCH_SIZE, CUTOFF_AT);

		/*
		 * 저장은 인자를 그대로 돌려주되 PK를 채워 준다.
		 *
		 * 세 엔티티 모두 @UuidGenerator라 실제 저장에서는 Hibernate가 persist 시점에 PK를 넣는다.
		 * 배치는 그 값을 바로 이어 쓴다 — run PK는 scoreRunId가 되고(AI 멱등키의 절반이다)
		 * item PK는 traceId·ai_usage.context_id가 된다. mock이 PK를 안 채우면 그 자리에서 NPE라
		 * 실제 동작과 다른 이유로 테스트가 깨진다.
		 */
		when(reportRepository.save(any())).thenAnswer(call -> withId(call.getArgument(0), "reportId"));
		when(runRepository.save(any())).thenAnswer(call -> withId(call.getArgument(0), "generationRunId"));
		when(itemRepository.save(any())).thenAnswer(call -> withId(call.getArgument(0), "generationItemId"));
	}

	// ------------------------------------------------------------------ dispatch

	@Test
	void createsOneItemPerProblemAndCallsAiForEach() {
		catalogHasTheConfiguredModel();
		ReportTarget target = target();
		when(dispatchRepository.findDueSessions(MAX_ATTEMPTS, CUTOFF_AT, BATCH_SIZE)).thenReturn(List.of(target));
		when(dispatchRepository.findSessionProblems(target.getSessionId()))
				.thenReturn(List.of(problem(1), problem(2), problem(3)));
		when(reportRepository.findRoundReports(any(), any(), any())).thenReturn(List.of());
		when(aiClient.requestGeneration(any(), any()))
				.thenAnswer(call -> new ReportGenerationJob.Accepted(UUID.randomUUID().toString(), "QUEUED"));

		int dispatched = service.dispatchDueSessions();

		assertThat(dispatched).isEqualTo(1);
		verify(runRepository, times(2)).save(any());  // QUEUED 저장 + RUNNING 전이
		verify(aiClient, times(3)).requestGeneration(any(), any());

		ArgumentCaptor<ReportGenerationItem> items = ArgumentCaptor.forClass(ReportGenerationItem.class);
		verify(itemRepository, times(6)).save(items.capture());  // 문제당 생성 + jobId 반영
		assertThat(items.getAllValues())
				.extracting(ReportGenerationItem::getStatus)
				.contains(ReportGenerationItemStatus.RUNNING);
	}

	/**
	 * 🔴 scoreRunId가 비면 AI 멱등키가 문제당 상수가 되고, 재생성 때 AI가 이전 jobId를 그대로
	 * 돌려줘 uq_report_generation_item_external_job_id 가 터진다. 실행 ID여야 한다.
	 */
	@Test
	void sendsTheRunIdAsScoreRunIdSoRegenerationGetsAFreshJob() {
		catalogHasTheConfiguredModel();
		ReportTarget target = target();
		when(dispatchRepository.findDueSessions(MAX_ATTEMPTS, CUTOFF_AT, BATCH_SIZE)).thenReturn(List.of(target));
		when(dispatchRepository.findSessionProblems(any())).thenReturn(List.of(problem(1)));
		when(reportRepository.findRoundReports(any(), any(), any())).thenReturn(List.of());
		when(aiClient.requestGeneration(any(), any()))
				.thenReturn(new ReportGenerationJob.Accepted(UUID.randomUUID().toString(), "QUEUED"));

		service.dispatchDueSessions();

		ArgumentCaptor<ReportGenerationRun> runs = ArgumentCaptor.forClass(ReportGenerationRun.class);
		verify(runRepository, times(2)).save(runs.capture());
		String runId = runs.getAllValues().get(0).getGenerationRunId().toString();

		ArgumentCaptor<ReportGenerationRequest> requests =
				ArgumentCaptor.forClass(ReportGenerationRequest.class);
		verify(aiClient).requestGeneration(requests.capture(), any());
		assertThat(requests.getValue().scoreRunId()).isEqualTo(runId);
		assertThat(requests.getValue().idempotencyKey()).endsWith(":" + runId);
	}

	/**
	 * 🔴 AI에 보내는 모델 식별자는 <b>접두어가 붙은 쪽</b>이어야 한다.
	 *
	 * <p>필드 이름은 {@code providerModelCode}지만 값은 설정값({@code ai.report.model-code})을 쓴다 —
	 * 운영 DB의 {@code provider_model_code}에 접두어가 빠져 있고({@code minimax-m3}),
	 * 공급자가 요구하는 형식은 {@code minimaxai/minimax-m3}이기 때문이다.
	 *
	 * <p>이 테스트가 그 의도적 어긋남을 고정한다. 데이터가 정리되면 이 테스트를 먼저 고치고
	 * {@code getProviderModelCode()}로 되돌리면 된다 — 그 전에 되돌리면 AI가 모델을 못 찾는데
	 * 응답이 200이라 조용히 기본 모델로 대체될 수 있다.
	 */
	@Test
	void sendsThePrefixedModelCodeBecauseProviderModelCodeLacksIt() {
		catalogHasTheConfiguredModel();
		ReportTarget target = target();
		when(dispatchRepository.findDueSessions(MAX_ATTEMPTS, CUTOFF_AT, BATCH_SIZE)).thenReturn(List.of(target));
		when(dispatchRepository.findSessionProblems(any())).thenReturn(List.of(problem(1)));
		when(reportRepository.findRoundReports(any(), any(), any())).thenReturn(List.of());
		when(aiClient.requestGeneration(any(), any()))
				.thenReturn(new ReportGenerationJob.Accepted(UUID.randomUUID().toString(), "QUEUED"));

		service.dispatchDueSessions();

		ArgumentCaptor<ReportGenerationRequest> requests =
				ArgumentCaptor.forClass(ReportGenerationRequest.class);
		verify(aiClient).requestGeneration(requests.capture(), any());
		assertThat(requests.getValue().providerModelCode())
				.as("접두어가 빠지면 AI가 모델을 못 찾는다")
				.isEqualTo(MODEL_CODE)
				.isNotEqualTo(PROVIDER_MODEL_CODE);
	}

	/** 모델을 못 찾으면 아무 행도 남기지 않는다. run을 만들면 설정을 고쳐도 재시도 상한만 깎인다. */
	@Test
	void writesNothingWhenTheConfiguredModelIsMissingFromTheCatalog() {
		when(dispatchRepository.findDueSessions(MAX_ATTEMPTS, CUTOFF_AT, BATCH_SIZE)).thenReturn(List.of(target()));
		when(modelRepository.findActiveByModelCode(MODEL_CODE)).thenReturn(Optional.empty());

		assertThat(service.dispatchDueSessions()).isZero();
		verify(runRepository, never()).save(any());
		verify(aiClient, never()).requestGeneration(any(), any());
	}

	/** 문제가 없으면 run을 만들지 않는다. 만들면 영원히 확정되지 않는 실행이 남는다. */
	@Test
	void skipsSessionsThatHaveNoProblems() {
		catalogHasTheConfiguredModel();
		when(dispatchRepository.findDueSessions(MAX_ATTEMPTS, CUTOFF_AT, BATCH_SIZE)).thenReturn(List.of(target()));
		when(dispatchRepository.findSessionProblems(any())).thenReturn(List.of());

		service.dispatchDueSessions();

		verify(runRepository, never()).save(any());
	}

	/** 요청이 거절돼도 그 item만 닫고 나머지 문제는 계속 보낸다. */
	@Test
	void failsOnlyTheRejectedItemAndKeepsSendingTheRest() {
		catalogHasTheConfiguredModel();
		when(dispatchRepository.findDueSessions(MAX_ATTEMPTS, CUTOFF_AT, BATCH_SIZE)).thenReturn(List.of(target()));
		when(dispatchRepository.findSessionProblems(any())).thenReturn(List.of(problem(1), problem(2)));
		when(reportRepository.findRoundReports(any(), any(), any())).thenReturn(List.of());
		when(aiClient.requestGeneration(any(), any()))
				.thenThrow(new AiCallException(null, "PROVIDER_ERROR", true, "AI 서버 오류"))
				.thenReturn(new ReportGenerationJob.Accepted(UUID.randomUUID().toString(), "QUEUED"));

		assertThat(service.dispatchDueSessions()).isEqualTo(1);
		verify(aiClient, times(2)).requestGeneration(any(), any());

		ArgumentCaptor<ReportGenerationItem> items = ArgumentCaptor.forClass(ReportGenerationItem.class);
		verify(itemRepository, times(4)).save(items.capture());
		assertThat(items.getAllValues())
				.extracting(ReportGenerationItem::getStatus)
				.contains(ReportGenerationItemStatus.FAILED, ReportGenerationItemStatus.RUNNING);
	}

	/** AI가 UUID가 아닌 jobId를 주면 저장 전에 걸러 item을 실패로 닫는다. */
	@Test
	void failsTheItemWhenTheJobIdIsNotAUuid() {
		catalogHasTheConfiguredModel();
		when(dispatchRepository.findDueSessions(MAX_ATTEMPTS, CUTOFF_AT, BATCH_SIZE)).thenReturn(List.of(target()));
		when(dispatchRepository.findSessionProblems(any())).thenReturn(List.of(problem(1)));
		when(reportRepository.findRoundReports(any(), any(), any())).thenReturn(List.of());
		when(aiClient.requestGeneration(any(), any()))
				.thenReturn(new ReportGenerationJob.Accepted("job-not-a-uuid", "QUEUED"));

		service.dispatchDueSessions();

		ArgumentCaptor<ReportGenerationItem> items = ArgumentCaptor.forClass(ReportGenerationItem.class);
		verify(itemRepository, times(2)).save(items.capture());
		assertThat(items.getAllValues().get(1).getStatus()).isEqualTo(ReportGenerationItemStatus.FAILED);
	}

	/**
	 * 기존 리포트가 있으면 재사용한다. 새로 만들면 uq_report_active_user(회차·교육생·타입, ACTIVE
	 * 한정) 때문에 두 번째 발행이 23505로 막힌다.
	 */
	@Test
	void reusesTheExistingReportRowInsteadOfCreatingASecondOne() {
		catalogHasTheConfiguredModel();
		ReportTarget target = target();
		Report existing = Report.forTrainee(target.getOrgId(), target.getCohortId(),
				target.getUserId(), target.getAssessmentRoundId());
		when(dispatchRepository.findDueSessions(MAX_ATTEMPTS, CUTOFF_AT, BATCH_SIZE)).thenReturn(List.of(target));
		when(dispatchRepository.findSessionProblems(any())).thenReturn(List.of(problem(1)));
		when(reportRepository.findRoundReports(any(), any(), any())).thenReturn(List.of(existing));
		when(aiClient.requestGeneration(any(), any()))
				.thenReturn(new ReportGenerationJob.Accepted(UUID.randomUUID().toString(), "QUEUED"));

		service.dispatchDueSessions();

		verify(reportRepository, never()).save(any());
		ArgumentCaptor<ReportGenerationRun> runs = ArgumentCaptor.forClass(ReportGenerationRun.class);
		verify(runRepository, times(2)).save(runs.capture());
		assertThat(runs.getAllValues().get(0).getReportId()).isEqualTo(existing.getReportId());
	}

	/**
	 * 정리되지 않은 stage 때문에 빠진 세션은 <b>반드시 로그로 드러나야 한다.</b>
	 *
	 * <p>{@code NO_UNFINISHED_STAGE}는 네이티브 SQL 안에 있어 이 테스트로 검증되지 않는다.
	 * 검증할 수 있는 것은 <b>"조용히 걸러지지 않는가"</b>뿐이고, 그게 실제로 중요한 부분이다 —
	 * 대상이 0건이어도 원인을 세러 가야 한다. 세션 종료 쪽 문제라 이쪽에서 고칠 수 없고,
	 * 로그가 유일한 단서이기 때문이다.
	 */
	@Test
	void countsBlockedSessionsEvenWhenThereIsNothingToDispatch() {
		when(dispatchRepository.findDueSessions(MAX_ATTEMPTS, CUTOFF_AT, BATCH_SIZE)).thenReturn(List.of());
		when(dispatchRepository.countSessionsWithUnfinishedStages(CUTOFF_AT)).thenReturn(4L);

		assertThat(service.dispatchDueSessions()).isZero();

		verify(dispatchRepository).countSessionsWithUnfinishedStages(CUTOFF_AT);
	}

	/** 경고를 못 남긴 것이 요청을 막을 이유는 없다. */
	@Test
	void keepsDispatchingWhenTheBlockedSessionCountFails() {
		catalogHasTheConfiguredModel();
		ReportTarget target = target();
		when(dispatchRepository.findDueSessions(MAX_ATTEMPTS, CUTOFF_AT, BATCH_SIZE)).thenReturn(List.of(target));
		when(dispatchRepository.countSessionsWithUnfinishedStages(CUTOFF_AT))
				.thenThrow(new IllegalStateException("집계 실패"));
		when(dispatchRepository.findSessionProblems(any())).thenReturn(List.of(problem(1)));
		when(reportRepository.findRoundReports(any(), any(), any())).thenReturn(List.of());
		when(aiClient.requestGeneration(any(), any()))
				.thenReturn(new ReportGenerationJob.Accepted(UUID.randomUUID().toString(), "QUEUED"));

		assertThat(service.dispatchDueSessions()).isEqualTo(1);

		verify(aiClient).requestGeneration(any(), any());
	}

	// --------------------------------------------------------- 문제 단위 dispatch

	/**
	 * 🔴 같은 세션의 문제 여러 개가 한 번에 걸려도 <b>실행(run)은 하나</b>여야 한다.
	 *
	 * <p>문제마다 run을 만들면 {@code uq_report_snapshot_generation_run_id}(run 1건당 스냅샷 1건)
	 * 때문에 스냅샷이 문제 수만큼 생기고, 리포트는 회차당 1건이라 합치는 단계가 새로 필요해진다.
	 */
	@Test
	void keepsOneRunPerStudentEvenWhenSeveralProblemsFinishTogether() {
		catalogHasTheConfiguredModel();
		UUID sessionId = UUID.randomUUID();
		when(dispatchRepository.findDueProblems(MAX_ATTEMPTS, CUTOFF_AT, BATCH_SIZE))
				.thenReturn(List.of(dueProblem(sessionId, 1), dueProblem(sessionId, 2)));
		when(reportRepository.findRoundReports(any(), any(), any())).thenReturn(List.of());
		when(runRepository.findActiveByReportIdAndTriggerType(any(), any())).thenReturn(Optional.empty());
		when(aiClient.requestGeneration(any(), any()))
				.thenAnswer(call -> new ReportGenerationJob.Accepted(UUID.randomUUID().toString(), "QUEUED"));

		assertThat(service.dispatchDueProblems()).isEqualTo(2);

		ArgumentCaptor<ReportGenerationRun> runs = ArgumentCaptor.forClass(ReportGenerationRun.class);
		verify(runRepository, times(2)).save(runs.capture());   // QUEUED 저장 + RUNNING 전이
		assertThat(runs.getAllValues())
				.extracting(ReportGenerationRun::getGenerationRunId)
				.containsOnly(runs.getAllValues().get(0).getGenerationRunId());
		verify(aiClient, times(2)).requestGeneration(any(), any());
	}

	/**
	 * 🔴 나중에 끝난 문제는 <b>기존 실행에 붙는다</b>. 새 run을 만들면 안 된다.
	 *
	 * <p>문제 1이 이미 나가 있고 문제 2가 이제 끝난 상황이다. {@code uq_report_generation_run_active}가
	 * (report_id, trigger_type)에 걸려 있어 새로 만들면 DB도 거부한다.
	 */
	@Test
	void attachesLaterProblemsToTheRunThatIsAlreadyRunning() {
		catalogHasTheConfiguredModel();
		UUID sessionId = UUID.randomUUID();
		Report report = withId(Report.forTrainee(ORG, COHORT, USER, ROUND), "reportId");
		ReportGenerationRun existing = withId(ReportGenerationRun.queued(
				report.getReportId(), ReportGenerationTriggerType.SCHEDULED,
				"key", 1, 1, "f".repeat(64)), "generationRunId");

		when(dispatchRepository.findDueProblems(MAX_ATTEMPTS, CUTOFF_AT, BATCH_SIZE))
				.thenReturn(List.of(dueProblem(sessionId, 2)));
		when(reportRepository.findRoundReports(any(), any(), any())).thenReturn(List.of(report));
		when(runRepository.findActiveByReportIdAndTriggerType(
				report.getReportId(), ReportGenerationTriggerType.SCHEDULED))
				.thenReturn(Optional.of(existing));
		when(aiClient.requestGeneration(any(), any()))
				.thenReturn(new ReportGenerationJob.Accepted(UUID.randomUUID().toString(), "QUEUED"));

		assertThat(service.dispatchDueProblems()).isEqualTo(1);

		ArgumentCaptor<ReportGenerationItem> items = ArgumentCaptor.forClass(ReportGenerationItem.class);
		verify(itemRepository, atLeastOnce()).save(items.capture());
		assertThat(items.getAllValues())
				.extracting(ReportGenerationItem::getGenerationRunId)
				.containsOnly(existing.getGenerationRunId());
	}

	/** 서로 다른 세션은 각자 run을 갖는다. 묶는 기준이 세션이라는 것을 못 박는다. */
	@Test
	void givesEachSessionItsOwnRun() {
		catalogHasTheConfiguredModel();
		when(dispatchRepository.findDueProblems(MAX_ATTEMPTS, CUTOFF_AT, BATCH_SIZE))
				.thenReturn(List.of(dueProblem(UUID.randomUUID(), 1), dueProblem(UUID.randomUUID(), 1)));
		when(reportRepository.findRoundReports(any(), any(), any())).thenReturn(List.of());
		when(runRepository.findActiveByReportIdAndTriggerType(any(), any())).thenReturn(Optional.empty());
		when(aiClient.requestGeneration(any(), any()))
				.thenAnswer(call -> new ReportGenerationJob.Accepted(UUID.randomUUID().toString(), "QUEUED"));

		assertThat(service.dispatchDueProblems()).isEqualTo(2);

		// 세션 2개 × (QUEUED 저장 + RUNNING 전이)
		verify(runRepository, times(4)).save(any());
	}

	// ------------------------------------------------------------- 운영자 재생성

	/**
	 * 배치가 놓친 대상을 푸는 유일한 경로다. 두 가지를 못 박는다.
	 *
	 * <p><b>① 배치 대상 조회를 타지 않는다.</b> {@code findDueSessions}는
	 * {@code BLOCKING_RUN_EXISTS}·{@code UNDER_ATTEMPT_LIMIT}로 걸러진 목록이라, 재생성이 그걸
	 * 거치면 <b>정확히 고쳐야 할 대상만 빠진다</b>(상한을 소진했거나 PARTIAL로 닫힌 것들).
	 *
	 * <p><b>② run이 {@code USER_REQUESTED}로 남는다.</b> 이 값이 {@code SCHEDULED}로 새면
	 * {@code UNDER_ATTEMPT_LIMIT}가 그 실행까지 세서, 재생성을 누를수록 배치가 그 대상을 더 빨리
	 * 포기하게 된다 — 도구가 문제를 악화시키는 방향이다.
	 */
	@Test
	void regeneratesBypassingTheBatchGatesAndMarksTheRunUserRequested() {
		catalogHasTheConfiguredModel();
		ReportTarget target = target();
		when(dispatchRepository.findTargetBySession(target.getSessionId())).thenReturn(Optional.of(target));
		when(dispatchRepository.findSessionProblems(target.getSessionId()))
				.thenReturn(List.of(problem(1), problem(2), problem(3)));
		when(reportRepository.findRoundReports(any(), any(), any())).thenReturn(List.of());
		when(aiClient.requestGeneration(any(), any()))
				.thenAnswer(call -> new ReportGenerationJob.Accepted(UUID.randomUUID().toString(), "QUEUED"));

		Optional<UUID> runId = service.regenerateSession(target.getSessionId(), "operator@example.com");

		assertThat(runId).isPresent();
		verify(dispatchRepository, never()).findDueSessions(anyInt(), any(), anyInt());
		verify(aiClient, times(3)).requestGeneration(any(), any());

		ArgumentCaptor<ReportGenerationRun> runs = ArgumentCaptor.forClass(ReportGenerationRun.class);
		verify(runRepository, times(2)).save(runs.capture());
		assertThat(runs.getAllValues().get(0).getTriggerType())
				.isEqualTo(ReportGenerationTriggerType.USER_REQUESTED);
	}

	/**
	 * 세션이 없거나 유효성 규칙에 안 맞으면 아무것도 만들지 않는다.
	 *
	 * <p>재생성이 푸는 것은 <b>"얼마나 자주"이지 "누구를"이 아니다.</b> 무효 응시나 미완료 세션은
	 * 조회가 빈 값을 내므로, 여기서 run을 만들면 영원히 확정되지 않는 실행이 남는다.
	 */
	@Test
	void doesNotRegenerateWhatIsNotAValidTarget() {
		when(dispatchRepository.findTargetBySession(any())).thenReturn(Optional.empty());

		assertThat(service.regenerateSession(UUID.randomUUID(), "operator@example.com")).isEmpty();

		verify(runRepository, never()).save(any());
		verify(aiClient, never()).requestGeneration(any(), any());
	}

	// ---------------------------------------------------------------------- poll

	@Test
	void marksTheItemSucceededAndFinalizesTheRun() {
		ReportGenerationItem item = runningItem();
		when(itemRepository.findByStatusIn(any())).thenReturn(List.of(item));
		when(aiClient.fetchJob(any(), any(), any(), any())).thenReturn(job("SUCCEEDED", false));

		assertThat(service.pollActiveItems()).isEqualTo(1);
		assertThat(item.getStatus()).isEqualTo(ReportGenerationItemStatus.SUCCEEDED);
		assertThat(item.getResponsePayload()).contains("reachedStage");
		verify(finalizer).finalizeIfComplete(eq(item.getGenerationRunId()), any());
	}

	/** AI의 PARTIAL은 "점수는 냈지만 서술이 없다"이다. 실패가 아니라 플래그로 남긴다. */
	@Test
	void treatsPartialAsSucceededWithTheNarrativeFlag() {
		ReportGenerationItem item = runningItem();
		when(itemRepository.findByStatusIn(any())).thenReturn(List.of(item));
		when(aiClient.fetchJob(any(), any(), any(), any())).thenReturn(job("PARTIAL", true));

		service.pollActiveItems();

		assertThat(item.getStatus()).isEqualTo(ReportGenerationItemStatus.SUCCEEDED);
		assertThat(item.getNarrativeFailed()).isTrue();
	}

	@Test
	void marksTheItemFailedWhenAiReportsFailure() {
		ReportGenerationItem item = runningItem();
		when(itemRepository.findByStatusIn(any())).thenReturn(List.of(item));
		when(aiClient.fetchJob(any(), any(), any(), any())).thenReturn(
				new ReportGenerationJob.Status(UUID.randomUUID().toString(), null, null, "FAILED",
						"컨텍스트 초과", Instant.now(), Instant.now(), null, List.of()));

		service.pollActiveItems();

		assertThat(item.getStatus()).isEqualTo(ReportGenerationItemStatus.FAILED);
		assertThat(item.getFailureReason()).isEqualTo("컨텍스트 초과");
	}

	/** 아직 진행 중이고 상한도 안 넘겼으면 건드리지 않는다. */
	@Test
	void leavesTheItemAloneWhileTheJobIsStillRunning() {
		ReportGenerationItem item = runningItem();
		when(itemRepository.findByStatusIn(any())).thenReturn(List.of(item));
		when(aiClient.fetchJob(any(), any(), any(), any())).thenReturn(job("RUNNING", false));

		assertThat(service.pollActiveItems()).isZero();
		assertThat(item.getStatus()).isEqualTo(ReportGenerationItemStatus.RUNNING);
		// 상태가 안 바뀌어도 확정 판정은 다시 받는다 — 앞선 폴링에서 확정이 실패했을 수 있다.
		verify(finalizer).finalizeIfComplete(any(), any());
	}

	/** 상한을 넘긴 job은 닫는다. 안 그러면 리포트가 영원히 `발행 전`으로 남는다. */
	@Test
	void failsTheItemOnceTheTimeoutHasPassed() {
		ReportGenerationItem item = itemStartedAt(Instant.now().minus(Duration.ofHours(1)));
		when(itemRepository.findByStatusIn(any())).thenReturn(List.of(item));
		when(aiClient.fetchJob(any(), any(), any(), any())).thenReturn(job("RUNNING", false));

		assertThat(service.pollActiveItems()).isEqualTo(1);
		assertThat(item.getStatus()).isEqualTo(ReportGenerationItemStatus.FAILED);
	}

	/** 재시도 가능한 장애면 남겨 둔다 — 다음 폴링이 다시 묻고, 무한 대기는 itemTimeout이 막는다. */
	@Test
	void keepsTheItemWhenTheStatusCallFailsTransiently() {
		ReportGenerationItem item = runningItem();
		when(itemRepository.findByStatusIn(any())).thenReturn(List.of(item));
		when(aiClient.fetchJob(any(), any(), any(), any()))
				.thenThrow(new AiCallException(null, "TIMEOUT", true, "일시적 장애"));

		assertThat(service.pollActiveItems()).isZero();
		assertThat(item.getStatus()).isEqualTo(ReportGenerationItemStatus.RUNNING);
	}

	/** AI가 모르는 job은 다시 물어도 같은 답이라 그 자리에서 닫는다. */
	@Test
	void failsTheItemWhenAiNoLongerKnowsTheJob() {
		ReportGenerationItem item = runningItem();
		when(itemRepository.findByStatusIn(any())).thenReturn(List.of(item));
		when(aiClient.fetchJob(any(), any(), any(), any()))
				.thenThrow(new AiCallException(null, "NOT_FOUND", false, "없는 작업"));

		assertThat(service.pollActiveItems()).isEqualTo(1);
		assertThat(item.getStatus()).isEqualTo(ReportGenerationItemStatus.FAILED);
	}

	// ------------------------------------------------------------------- fixture

	/** Hibernate가 persist에서 하는 일(PK 채우기)을 대신한다. 이미 있으면 그대로 둔다. */
	private static <T> T withId(T entity, String idField) {
		if (ReflectionTestUtils.getField(entity, idField) == null) {
			ReflectionTestUtils.setField(entity, idField, UUID.randomUUID());
		}
		return entity;
	}

	private void catalogHasTheConfiguredModel() {
		AnalysisModel model = mock(AnalysisModel.class);
		when(model.getProviderModelCode()).thenReturn(PROVIDER_MODEL_CODE);
		when(modelRepository.findActiveByModelCode(MODEL_CODE)).thenReturn(Optional.of(model));
	}

	/*
	 * 대상 projection은 mock이 아니라 구현 레코드로 만든다.
	 *
	 * mock(...)을 쓰면 그 안의 when()이 바깥 when(...).thenReturn(target()) 인자 평가 도중에
	 * 실행돼 Mockito가 "미완성 스터빙"으로 보고, 그 오염이 <b>다른 테스트까지</b> 실패시킨다.
	 */
	private static ReportTarget target() {
		return new TestReportTarget(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID());
	}

	private static ProblemTarget problem(int problemNo) {
		return new TestProblemTarget(UUID.randomUUID(), problemNo);
	}

	private record TestReportTarget(UUID sessionId, UUID attemptId, UUID userId, UUID orgId,
			UUID cohortId, UUID projectId, UUID assessmentRoundId, UUID codeAnalysisId)
			implements ReportTarget {

		@Override
		public UUID getSessionId() {
			return sessionId;
		}

		@Override
		public UUID getAttemptId() {
			return attemptId;
		}

		@Override
		public UUID getUserId() {
			return userId;
		}

		@Override
		public UUID getOrgId() {
			return orgId;
		}

		@Override
		public UUID getCohortId() {
			return cohortId;
		}

		@Override
		public UUID getProjectId() {
			return projectId;
		}

		@Override
		public UUID getAssessmentRoundId() {
			return assessmentRoundId;
		}

		@Override
		public UUID getCodeAnalysisId() {
			return codeAnalysisId;
		}

		@Override
		public Instant getReportPublishNotBeforeAt() {
			return null;
		}
	}

	private static ProblemDueTarget dueProblem(UUID sessionId, int problemNo) {
		return new TestProblemDueTarget(sessionId, UUID.randomUUID(), problemNo);
	}

	/** 문제 단위 조회 결과. 세션 축은 한 세션 안에서 같아야 해서 sessionId만 받는다. */
	private record TestProblemDueTarget(UUID sessionId, UUID problemId, Integer problemNo)
			implements ProblemDueTarget {

		@Override
		public UUID getSessionId() {
			return sessionId;
		}

		@Override
		public UUID getProblemId() {
			return problemId;
		}

		@Override
		public Integer getProblemNo() {
			return problemNo;
		}

		@Override
		public UUID getAttemptId() {
			return ATTEMPT;
		}

		@Override
		public UUID getUserId() {
			return USER;
		}

		@Override
		public UUID getOrgId() {
			return ORG;
		}

		@Override
		public UUID getCohortId() {
			return COHORT;
		}

		@Override
		public UUID getProjectId() {
			return PROJECT;
		}

		@Override
		public UUID getAssessmentRoundId() {
			return ROUND;
		}

		@Override
		public UUID getCodeAnalysisId() {
			return ANALYSIS;
		}

		@Override
		public Instant getReportPublishNotBeforeAt() {
			return null;
		}
	}

	private record TestProblemTarget(UUID problemId, Integer problemNo) implements ProblemTarget {

		@Override
		public UUID getProblemId() {
			return problemId;
		}

		@Override
		public Integer getProblemNo() {
			return problemNo;
		}
	}

	private ReportGenerationItem runningItem() {
		return itemStartedAt(Instant.now());
	}

	private ReportGenerationItem itemStartedAt(Instant startedAt) {
		ReportGenerationItem item = withId(ReportGenerationItem.queued(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
				"{}", "0".repeat(64), 1, UUID.randomUUID().toString()), "generationItemId");
		item.acceptExternalJob(UUID.randomUUID(), startedAt);
		return item;
	}

	/** 실제 응답 모양을 줄인 것. {@code problem.reachedStage}는 근거 생성이 읽는 값이다. */
	private ReportGenerationJob.Status job(String status, boolean narrativeFailed) {
		try {
			return new ReportGenerationJob.Status(
					UUID.randomUUID().toString(), null, null, status, null,
					Instant.now(), Instant.now(),
					new ObjectMapper().readTree("""
							{"problem":{"reachedStage":4},"retest":false,"narrativeFailed":%s}
							""".formatted(narrativeFailed)),
					List.of());
		} catch (tools.jackson.core.JacksonException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
