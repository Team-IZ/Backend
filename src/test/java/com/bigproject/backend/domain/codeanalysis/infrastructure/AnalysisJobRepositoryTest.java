package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.domain.AnalysisFailureCode;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 Hibernate UPDATE를 실행해 external_job_id가 후속 상태 전이에서 보존되는지 검증한다. */
@DataJpaTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.datasource.url=jdbc:h2:mem:analysis-job-repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password="
})
@Sql(statements = {
		"DROP TABLE IF EXISTS analysis_job",
		"""
		CREATE TABLE analysis_job (
			job_id UUID PRIMARY KEY,
			org_id UUID NOT NULL,
			assessment_round_id UUID NOT NULL,
			team_id UUID NOT NULL,
			submission_id UUID NOT NULL,
			analysis_id UUID,
			batch_key VARCHAR(1000) NOT NULL,
			job_type VARCHAR(100) NOT NULL,
			execution_no INTEGER NOT NULL,
			status VARCHAR(100) NOT NULL,
			started_at TIMESTAMP WITH TIME ZONE,
			completed_at TIMESTAMP WITH TIME ZONE,
			failure_reason CLOB,
			trace_id VARCHAR(1000) NOT NULL,
			external_job_id UUID,
			failure_code VARCHAR(100),
			requested_model_id UUID,
			question_budget SMALLINT,
			created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
		);
		"""
})
class AnalysisJobRepositoryTest {

	private static final List<AnalysisJobStatus> ACTIVE_STATUSES =
			List.of(AnalysisJobStatus.QUEUED, AnalysisJobStatus.RUNNING);

	@Autowired
	private AnalysisJobRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void insertsAnAcceptedJobWithItsExternalIdAndNeverUpdatesThatColumnDuringPolling() {
		UUID externalJobId = UUID.randomUUID();
		AnalysisJob job = queued();
		job.acceptExternalJob(externalJobId);
		job = repository.saveAndFlush(job);

		assertThat(storedExternalJobId(job.getJobId())).isEqualTo(externalJobId);

		Instant startedAt = Instant.parse("2026-08-13T09:46:08Z");
		int updated = repository.transitionActiveJob(
				job.getJobId(), externalJobId, AnalysisJobStatus.RUNNING, startedAt,
				null, null, null, ACTIVE_STATUSES);

		assertThat(updated).isEqualTo(1);
		assertThat(storedExternalJobId(job.getJobId())).isEqualTo(externalJobId);
		assertThat(storedStatus(job.getJobId())).isEqualTo("RUNNING");
	}

	@Test
	void refusesAStalePollingUpdateWhenTheStoredExternalIdNoLongerMatches() {
		UUID externalJobId = UUID.randomUUID();
		AnalysisJob job = queued();
		job.acceptExternalJob(externalJobId);
		job = repository.saveAndFlush(job);

		// 이 SQL은 다른 프로세스·수동 SQL을 흉내 낸다. 애플리케이션의 폴링 쿼리는 기대한 ID를
		// WHERE 조건으로 검증하므로, 이런 손상이 생긴 행의 나머지 상태까지 오래된 응답으로 덮지 않는다.
		jdbc.update("UPDATE analysis_job SET external_job_id = NULL WHERE job_id = ?", job.getJobId());

		int updated = repository.transitionActiveJob(
				job.getJobId(), externalJobId, AnalysisJobStatus.RUNNING, Instant.now(),
				null, null, null, ACTIVE_STATUSES);

		assertThat(updated).isZero();
		assertThat(storedExternalJobId(job.getJobId())).isNull();
		assertThat(storedStatus(job.getJobId())).isEqualTo("QUEUED");
	}

	@Test
	void closesOnlyLegacyActiveJobsWhoseExternalIdIsActuallyMissing() {
		AnalysisJob job = repository.saveAndFlush(queued());
		Instant completedAt = Instant.parse("2026-08-13T09:47:17Z");

		int updated = repository.transitionActiveJobWithoutExternalId(
				job.getJobId(), AnalysisJobStatus.FAILED, completedAt, completedAt,
				"AI 서버 작업 ID가 없다.", AnalysisFailureCode.MODEL_ERROR, ACTIVE_STATUSES);

		assertThat(updated).isEqualTo(1);
		assertThat(storedExternalJobId(job.getJobId())).isNull();
		assertThat(storedStatus(job.getJobId())).isEqualTo("FAILED");
	}

	private AnalysisJob queued() {
		return AnalysisJob.queued(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				"round:team:submission:CODE_ANALYSIS", "CODE_ANALYSIS", 1, UUID.randomUUID().toString());
	}

	private UUID storedExternalJobId(UUID jobId) {
		return jdbc.queryForObject(
				"SELECT external_job_id FROM analysis_job WHERE job_id = ?", UUID.class, jobId);
	}

	private String storedStatus(UUID jobId) {
		return jdbc.queryForObject("SELECT status FROM analysis_job WHERE job_id = ?", String.class, jobId);
	}
}
