package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.domain.Report;
import com.bigproject.backend.domain.reporting.domain.ReportCompletionStatus;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationItem;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationRun;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationTriggerType;
import com.bigproject.backend.domain.reporting.domain.ReportSnapshot;
import com.bigproject.backend.domain.reporting.infrastructure.JdbcReportPayloadRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportDispatchRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportDispatchRepository.FinalizeContext;
import com.bigproject.backend.domain.reporting.infrastructure.ReportEvidenceRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportGenerationItemRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportGenerationRunRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportRepository;
import com.bigproject.backend.domain.reporting.infrastructure.ReportSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 확정 판정을 못 박는다. <b>문제 단위 생성으로 바뀌면서 새로 생긴 판정 세 가지</b>가 대상이다.
 *
 * <p>종전에는 "item이 전부 종료됐나" 하나만 봤다. 그때는 dispatch가 문제 3개를 한꺼번에 만들어
 * 그것으로 충분했다. 이제 item이 <b>시차를 두고</b> 붙으므로 같은 조건이 문제 1개가 끝난 순간에도
 * 참이 된다 — 그대로 두면 개념 카드 1장짜리 리포트가 발행된다.
 */
class ReportRunFinalizerTest {

	private static final UUID RUN = UUID.randomUUID();
	private static final UUID REPORT = UUID.randomUUID();
	private static final UUID SESSION = UUID.randomUUID();

	private ReportRepository reportRepository;
	private ReportGenerationRunRepository runRepository;
	private ReportGenerationItemRepository itemRepository;
	private ReportSnapshotRepository snapshotRepository;
	private ReportEvidenceRepository evidenceRepository;
	private JdbcReportPayloadRepository payloadRepository;
	private ReportDispatchRepository dispatchRepository;
	private ReportRunFinalizer finalizer;

	@BeforeEach
	void setUp() {
		reportRepository = mock(ReportRepository.class);
		runRepository = mock(ReportGenerationRunRepository.class);
		itemRepository = mock(ReportGenerationItemRepository.class);
		snapshotRepository = mock(ReportSnapshotRepository.class);
		evidenceRepository = mock(ReportEvidenceRepository.class);
		payloadRepository = mock(JdbcReportPayloadRepository.class);
		dispatchRepository = mock(ReportDispatchRepository.class);

		finalizer = new ReportRunFinalizer(reportRepository, runRepository, itemRepository,
				snapshotRepository, evidenceRepository, payloadRepository, dispatchRepository,
				new ReportEvidenceFactory(new ObjectMapper()), new ObjectMapper());

		when(runRepository.findById(RUN)).thenReturn(Optional.of(run()));
		when(reportRepository.findById(REPORT)).thenReturn(Optional.of(report()));
		when(snapshotRepository.findByReportIdAndIsActiveTrue(any())).thenReturn(Optional.empty());
		when(snapshotRepository.save(any())).thenAnswer(call -> withSnapshotId(call.getArgument(0)));
		when(payloadRepository.findConceptContext(any(), any())).thenReturn(Optional.empty());
	}

	/**
	 * 🔴 문제 3개 중 1개만 끝났으면 확정하지 않는다.
	 *
	 * <p>이 판정이 없으면 "item 전부 종료"가 참이 되어 <b>개념 카드 1장짜리 리포트가 발행된다.</b>
	 * 나머지 두 문제는 그 뒤에 도착하는데 그때는 run이 닫혀 있어 반영되지 않는다.
	 */
	@Test
	void waitsUntilEveryProblemOfTheSessionHasArrived() {
		when(itemRepository.findByGenerationRunId(RUN)).thenReturn(List.of(succeededItem()));
		givenSession(true, null, 3);

		assertThat(finalizer.finalizeIfComplete(RUN, Instant.now())).isFalse();

		verify(snapshotRepository, never()).save(any());
		verify(reportRepository, never()).save(any());
	}

	/** 다 모이면 확정하고 발행한다. 위 케이스의 대조군이다. */
	@Test
	void finalizesOnceAllProblemsArrived() {
		when(itemRepository.findByGenerationRunId(RUN))
				.thenReturn(List.of(succeededItem(), succeededItem(), succeededItem()));
		givenSession(true, null, 3);

		assertThat(finalizer.finalizeIfComplete(RUN, Instant.now())).isTrue();

		// writeSnapshot이 한 번, writeEvidence 뒤 applyRetryTargetCount가 붙어 한 번 더 저장한다.
		verify(snapshotRepository, times(2)).save(any());
		assertThat(savedReport().getPublishedAt()).isNotNull();
	}

	/**
	 * 🔴 세션이 무효로 끝났으면 <b>발행만</b> 막는다.
	 *
	 * <p>문제 단위 dispatch는 세션이 끝나기 전에 요청하므로, 만든 뒤에 세션이 무효로 끝날 수 있다.
	 * 스냅샷과 근거는 남긴다 — "왜 이 학생만 리포트가 없나"를 물었을 때 근거가 된다(ⓐ안).
	 */
	@Test
	void keepsTheSnapshotButDoesNotPublishWhenTheSessionEndedInvalid() {
		when(itemRepository.findByGenerationRunId(RUN))
				.thenReturn(List.of(succeededItem(), succeededItem(), succeededItem()));
		givenSession(false, null, 3);

		assertThat(finalizer.finalizeIfComplete(RUN, Instant.now())).isTrue();

		verify(snapshotRepository, times(2)).save(any());
		assertThat(savedReport().getPublishedAt())
				.as("무효 세션은 발행하지 않는다")
				.isNull();
	}

	/**
	 * 🔴 발행 예정 시각 전이면 보류한다.
	 *
	 * <p>종전에는 대상 조회가 "그때까지 만들지 않는 것"으로 이 규칙을 지켰다. 즉시 생성으로
	 * 바뀌면서 지킬 곳이 여기밖에 없다. 보류된 것은 {@code ReportPublishService}가 나중에 발행한다.
	 */
	@Test
	void defersPublishingUntilTheRoundsPublishTimeHasPassed() {
		when(itemRepository.findByGenerationRunId(RUN))
				.thenReturn(List.of(succeededItem(), succeededItem(), succeededItem()));
		givenSession(true, Instant.parse("2099-01-01T00:00:00Z"), 3);

		assertThat(finalizer.finalizeIfComplete(RUN, Instant.now())).isTrue();

		verify(snapshotRepository, times(2)).save(any());
		assertThat(savedReport().getPublishedAt())
				.as("발행 예정 시각 전에는 스냅샷만 만들고 발행은 미룬다")
				.isNull();
	}

	/** 세션 맥락을 못 읽으면 확정하지 않는다. 모르는 채로 발행하는 것보다 다시 보는 편이 낫다. */
	@Test
	void doesNotFinalizeWhenTheSessionContextIsMissing() {
		when(itemRepository.findByGenerationRunId(RUN)).thenReturn(List.of(succeededItem()));
		when(dispatchRepository.findFinalizeContext(any())).thenReturn(Optional.empty());

		assertThat(finalizer.finalizeIfComplete(RUN, Instant.now())).isFalse();

		verify(snapshotRepository, never()).save(any());
	}

	/**
	 * D-evidence-completeness(2026-08-21): concept context를 못 찾아 카드를 건너뛴 문제가 하나
	 * 있으면, AI가 셋 다 성공했어도 completion은 FULL이 아니라 PARTIAL이어야 하고 missing_count가
	 * 그 부족분을 반영해야 한다 — writeEvidence()의 written이 sample_count보다 작은데
	 * completion_status가 FULL로 남는 게 이 갭의 증상이었다.
	 */
	@Test
	void marksTheSnapshotPartialWhenOneConceptCardIsSkippedEvenIfAllItemsSucceeded() {
		UUID foundProblemId = UUID.randomUUID();
		UUID missingProblemId = UUID.randomUUID();
		when(itemRepository.findByGenerationRunId(RUN)).thenReturn(List.of(
				succeededItem(foundProblemId), succeededItem(foundProblemId), succeededItem(missingProblemId)));
		givenSession(true, null, 3);
		when(payloadRepository.findConceptContext(any(), org.mockito.ArgumentMatchers.eq(foundProblemId)))
				.thenReturn(Optional.of(conceptContext()));
		when(payloadRepository.findConceptContext(any(), org.mockito.ArgumentMatchers.eq(missingProblemId)))
				.thenReturn(Optional.empty());

		assertThat(finalizer.finalizeIfComplete(RUN, Instant.now())).isTrue();

		ReportSnapshot snapshot = savedSnapshot();
		assertThat(snapshot.getCompletionStatus()).isEqualTo(ReportCompletionStatus.PARTIAL);
		assertThat(snapshot.getMissingCount()).isEqualTo(1);
		verify(evidenceRepository, times(2)).save(any());
	}

	/** 카드가 하나도 안 빠지면 여전히 FULL이다 — 위 테스트의 대조군. */
	@Test
	void keepsTheSnapshotFullWhenEveryConceptCardIsWritten() {
		UUID problemId = UUID.randomUUID();
		when(itemRepository.findByGenerationRunId(RUN))
				.thenReturn(List.of(succeededItem(problemId), succeededItem(problemId), succeededItem(problemId)));
		givenSession(true, null, 3);
		when(payloadRepository.findConceptContext(any(), any())).thenReturn(Optional.of(conceptContext()));

		assertThat(finalizer.finalizeIfComplete(RUN, Instant.now())).isTrue();

		ReportSnapshot snapshot = savedSnapshot();
		assertThat(snapshot.getCompletionStatus()).isEqualTo(ReportCompletionStatus.FULL);
		assertThat(snapshot.getMissingCount()).isZero();
	}

	// ------------------------------------------------------------------ fixture

	private void givenSession(boolean eligible, Instant publishNotBefore, int problemCount) {
		FinalizeContext context = mock(FinalizeContext.class);
		when(context.getEligible()).thenReturn(eligible);
		when(context.getPublishNotBeforeAt()).thenReturn(publishNotBefore);
		when(context.getProblemCount()).thenReturn(problemCount);
		when(dispatchRepository.findFinalizeContext(SESSION)).thenReturn(Optional.of(context));
	}

	private Report savedReport() {
		org.mockito.ArgumentCaptor<Report> captor = org.mockito.ArgumentCaptor.forClass(Report.class);
		verify(reportRepository).save(captor.capture());
		return captor.getValue();
	}

	/** 마지막으로 저장된 스냅샷(applyEvidenceWritten·applyRetryTargetCount까지 반영된 최종본). */
	private ReportSnapshot savedSnapshot() {
		org.mockito.ArgumentCaptor<ReportSnapshot> captor = org.mockito.ArgumentCaptor.forClass(ReportSnapshot.class);
		verify(snapshotRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
		return captor.getValue();
	}

	private static JdbcReportPayloadRepository.ConceptContext conceptContext() {
		return new JdbcReportPayloadRepository.ConceptContext(
				UUID.randomUUID(), "L1", "COMPLETED", "발췌", 1, 1,
				"개념", 1, 1, "챕터", 1, 2);
	}

	private static ReportGenerationRun run() {
		ReportGenerationRun run = ReportGenerationRun.queued(REPORT,
				ReportGenerationTriggerType.SCHEDULED, "key", 1, 1, "a".repeat(64));
		ReflectionTestUtils.setField(run, "generationRunId", RUN);
		return run;
	}

	private static Report report() {
		Report report = Report.forTrainee(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID());
		ReflectionTestUtils.setField(report, "reportId", REPORT);
		return report;
	}

	private static ReportGenerationItem succeededItem() {
		return succeededItem(UUID.randomUUID());
	}

	private static ReportGenerationItem succeededItem(UUID problemId) {
		ReportGenerationItem item = ReportGenerationItem.queued(RUN, problemId, SESSION, 1,
				"{}", "b".repeat(64), 1, RUN.toString());
		ReflectionTestUtils.setField(item, "generationItemId", UUID.randomUUID());
		item.acceptExternalJob(UUID.randomUUID(), Instant.now());
		item.markSucceeded("{}", "c".repeat(64), false, Instant.now());
		return item;
	}

	private static ReportSnapshot withSnapshotId(ReportSnapshot snapshot) {
		ReflectionTestUtils.setField(snapshot, "snapshotId", UUID.randomUUID());
		return snapshot;
	}
}
