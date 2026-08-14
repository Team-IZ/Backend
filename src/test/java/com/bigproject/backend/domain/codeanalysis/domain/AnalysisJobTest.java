package com.bigproject.backend.domain.codeanalysis.domain;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ck_analysis_job_status_2}가 상태별로 {@code started_at}·{@code completed_at}·
 * {@code failure_reason} 조합을 강제하고, {@code ck_analysis_job_failure_code}가 FAILED일 때
 * {@code failure_code}를 NOT NULL로 요구한다. 그 조합을 엔티티가 지키는지 고정한다.
 *
 * <p>DB 없이 확인하는 이유는 조합이 어긋난 채 INSERT까지 가면 원인이 스택트레이스에 묻히기 때문이다.
 */
class AnalysisJobTest {

	private static final Instant STARTED = Instant.parse("2026-08-07T09:00:00Z");
	private static final Instant COMPLETED = Instant.parse("2026-08-07T09:05:00Z");

	private AnalysisJob queued() {
		return AnalysisJob.queued(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				"round:team:sub:CODE_ANALYSIS", "CODE_ANALYSIS", 1, "trace-1");
	}

	@Test
	void startsQueuedWithNoTimestamps() {
		AnalysisJob job = queued();

		// CHECK: QUEUED 이면 started_at·completed_at·failure_reason 이 전부 NULL 이어야 한다.
		assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.QUEUED);
		assertThat(job.getStartedAt()).isNull();
		assertThat(job.getCompletedAt()).isNull();
		assertThat(job.getFailureReason()).isNull();
		assertThat(job.isActive()).isTrue();
	}

	@Test
	void externalJobIdCanOnlyBeAssignedOnce() {
		AnalysisJob job = queued();
		UUID externalJobId = UUID.randomUUID();

		job.acceptExternalJob(externalJobId);
		job.acceptExternalJob(externalJobId);

		assertThat(job.getExternalJobId()).isEqualTo(externalJobId);
		assertThatThrownBy(() -> job.acceptExternalJob(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> job.acceptExternalJob(UUID.randomUUID()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void externalJobIdIsExcludedFromEveryJpaUpdate() throws NoSuchFieldException {
		Column column = AnalysisJob.class.getDeclaredField("externalJobId").getAnnotation(Column.class);

		assertThat(column.updatable()).isFalse();
	}

	@Test
	void runningKeepsCompletedAtNull() {
		AnalysisJob job = queued();
		job.markRunning(STARTED);

		assertThat(job.getStartedAt()).isEqualTo(STARTED);
		// CHECK: RUNNING 이면 completed_at 이 NULL 이어야 한다.
		assertThat(job.getCompletedAt()).isNull();
		assertThat(job.getFailureReason()).isNull();
	}

	@Test
	void partialIsATerminalSuccessNotAFailure() {
		AnalysisJob job = queued();
		job.markRunning(STARTED);
		job.markCompleted(AnalysisJobStatus.PARTIAL, STARTED, COMPLETED);

		// PARTIAL 을 FAILED 로 뭉뚱그리면 교육생에게 "일부 결과 있음" 을 안내할 수 없다.
		assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.PARTIAL);
		assertThat(job.getCompletedAt()).isEqualTo(COMPLETED);
		// CHECK: SUCCEEDED·PARTIAL 이면 failure_reason 이 NULL 이어야 한다.
		assertThat(job.getFailureReason()).isNull();
		assertThat(job.getFailureCode()).isNull();
		assertThat(job.isActive()).isFalse();
	}

	@Test
	void rejectsCompletingWithANonTerminalStatus() {
		AnalysisJob job = queued();

		assertThatThrownBy(() -> job.markCompleted(AnalysisJobStatus.RUNNING, STARTED, COMPLETED))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void failureRequiresBothCodeAndReason() {
		// 둘 중 하나라도 없으면 DB 가 INSERT 를 거부한다. 여기서 먼저 막아 원인을 드러낸다.
		assertThatThrownBy(() -> queued().markFailed(null, "사유", STARTED, COMPLETED))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("failureCode");

		assertThatThrownBy(() -> queued().markFailed(AnalysisFailureCode.MODEL_ERROR, "  ", STARTED, COMPLETED))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("failureReason");
	}

	@Test
	void failingStraightFromQueuedStillGetsAStartedAt() {
		AnalysisJob job = queued();

		// 요청 자체가 거절되면 시작한 적이 없다. 그래도 started_at 은 NOT NULL 이다.
		job.markFailed(AnalysisFailureCode.REPO_NOT_FOUND, "저장소를 찾을 수 없다.", null, COMPLETED);

		assertThat(job.getStartedAt()).isEqualTo(COMPLETED);
		assertThat(job.getCompletedAt()).isEqualTo(COMPLETED);
		assertThat(job.getFailureCode()).isEqualTo(AnalysisFailureCode.REPO_NOT_FOUND);
	}

	@Test
	void parsesOnlyTheFifteenAllowedFailureCodes() {
		assertThat(AnalysisFailureCode.values()).hasSize(15);
		assertThat(AnalysisFailureCode.parse("REPO_NOT_FOUND")).contains(AnalysisFailureCode.REPO_NOT_FOUND);
		// 2026-08-07 에 값 집합에서 뺐다. 그대로 저장하면 CHECK 위반이 된다.
		assertThat(AnalysisFailureCode.parse("EMPTY_CODE_EVIDENCE")).isEmpty();
		assertThat(AnalysisFailureCode.parse(null)).isEmpty();
	}
}
