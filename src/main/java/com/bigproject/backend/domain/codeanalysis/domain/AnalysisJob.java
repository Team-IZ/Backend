package com.bigproject.backend.domain.codeanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * analysis_job 테이블 매핑 엔티티. 팀 코드 분석 실행 1회분의 진행 상태와 실패 사유를 갖는다.
 *
 * <p>폴링 대상이 {@code code_analysis}가 아니라 이쪽인 이유는, {@code code_analysis}가 성공했을 때에만
 * 생기는 결과물이라 "진행 중"과 "분석 없음"을 구분할 수 없고 실패 사유 컬럼도 없기 때문이다.
 *
 * <p>상태 전이는 {@code ck_analysis_job_status_2}가 상태별로 {@code startedAt}·{@code completedAt}·
 * {@code failureReason} 조합을 강제한다. 그래서 필드를 개별로 열지 않고 전이 메서드만 노출한다 —
 * 밖에서 하나씩 채우면 조합이 어긋난 채 저장을 시도하게 된다.
 */
@Getter
@Entity
@Table(name = "analysis_job")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisJob {

	@Id
	@UuidGenerator
	@Column(name = "job_id", updatable = false, nullable = false)
	private UUID jobId;

	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	@Column(name = "assessment_round_id", nullable = false, updatable = false)
	private UUID assessmentRoundId;

	@Column(name = "team_id", nullable = false, updatable = false)
	private UUID teamId;

	@Column(name = "submission_id", nullable = false, updatable = false)
	private UUID submissionId;

	/** 분석 성공 시 생성되는 code_analysis 행을 가리킨다. */
	@Column(name = "analysis_id")
	private UUID analysisId;

	@Column(name = "batch_key", nullable = false, columnDefinition = "text")
	private String batchKey;

	@Column(name = "job_type", nullable = false, length = 100)
	private String jobType;

	/** 재시도 회차. uq_analysis_job_active 때문에 동시에 진행 중인 job은 1개뿐이다. */
	@Column(name = "execution_no", nullable = false)
	private Integer executionNo;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 100)
	private AnalysisJobStatus status;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "failure_reason", columnDefinition = "text")
	private String failureReason;

	@Column(name = "trace_id", nullable = false, columnDefinition = "text")
	private String traceId;

	/** AI 서버가 202 응답으로 반환한 작업 ID. */
	@Column(name = "external_job_id")
	private UUID externalJobId;

	/** 15종. 분석 실행 5종 + 저장소 접근 5종(S-03) + ZIP 검증 5종(S-15). */
	@Enumerated(EnumType.STRING)
	@Column(name = "failure_code", length = 100)
	private AnalysisFailureCode failureCode;

	@Column(name = "created_at", nullable = false, updatable = false, insertable = false)
	private Instant createdAt;

	private AnalysisJob(UUID orgId, UUID assessmentRoundId, UUID teamId, UUID submissionId,
			String batchKey, String jobType, int executionNo, String traceId) {
		this.orgId = orgId;
		this.assessmentRoundId = assessmentRoundId;
		this.teamId = teamId;
		this.submissionId = submissionId;
		this.batchKey = batchKey;
		this.jobType = jobType;
		this.executionNo = executionNo;
		this.traceId = traceId;
		this.status = AnalysisJobStatus.QUEUED;
	}

	/**
	 * 분석 요청을 접수 대기 상태로 만든다. AI 서버를 부르기 <b>전에</b> 저장한다.
	 *
	 * <p>호출 후에 만들면 202를 받고도 행이 없는 순간이 생기고, 그 사이 프로세스가 죽으면 AI 쪽에는
	 * 실행이 있는데 우리 원장에는 없는 상태가 된다. 먼저 QUEUED로 남겨 두면 최악이라도 고아 job이
	 * 남을 뿐이고, 그건 폴링이 정리할 수 있다.
	 *
	 * <p>{@code uq_analysis_job_active(batch_key, job_type) WHERE status IN ('QUEUED','RUNNING')}가
	 * 같은 제출의 동시 실행을 DB에서 막는다.
	 */
	public static AnalysisJob queued(UUID orgId, UUID assessmentRoundId, UUID teamId, UUID submissionId,
			String batchKey, String jobType, int executionNo, String traceId) {
		return new AnalysisJob(orgId, assessmentRoundId, teamId, submissionId,
				batchKey, jobType, executionNo, traceId);
	}

	/** AI 서버가 202로 준 작업 ID를 붙인다. uq_analysis_job_external_job_id 가 중복을 막는다. */
	public void acceptExternalJob(UUID externalJobId) {
		this.externalJobId = externalJobId;
	}

	/** QUEUED → RUNNING. CHECK가 이 상태에서 completedAt·failureReason을 NULL로 요구한다. */
	public void markRunning(Instant startedAt) {
		this.status = AnalysisJobStatus.RUNNING;
		this.startedAt = startedAt;
		this.completedAt = null;
		this.failureReason = null;
		this.failureCode = null;
	}

	/**
	 * SUCCEEDED 또는 PARTIAL로 끝낸다.
	 *
	 * <p>PARTIAL은 일부 개념만 문제 생성에 성공한 경우다. FAILED로 뭉뚱그리면 교육생에게 "일부 결과
	 * 있음"을 안내할 수 없다. CHECK가 두 상태 모두 failureReason을 NULL로 요구한다.
	 */
	public void markCompleted(AnalysisJobStatus terminal, Instant startedAt, Instant completedAt) {
		if (terminal != AnalysisJobStatus.SUCCEEDED && terminal != AnalysisJobStatus.PARTIAL) {
			throw new IllegalArgumentException("성공 종료 상태가 아니다: " + terminal);
		}
		this.status = terminal;
		this.startedAt = startedAt == null ? this.startedAt : startedAt;
		this.completedAt = completedAt;
		this.failureReason = null;
		this.failureCode = null;
	}

	/**
	 * FAILED로 끝낸다.
	 *
	 * <p>{@code ck_analysis_job_failure_code}가 FAILED일 때 failureCode를 NOT NULL로,
	 * {@code ck_analysis_job_status_2}가 failureReason을 NOT NULL로 요구한다. 둘 중 하나라도 없으면
	 * 실패 자체를 기록할 수 없으므로 여기서 먼저 막는다 — INSERT까지 가면 원인이 스택트레이스에 묻힌다.
	 *
	 * <p>{@code startedAt}도 NOT NULL이다. QUEUED에서 곧바로 실패하면(예: 요청 자체가 거절됨)
	 * 시작 시각이 없으므로 완료 시각으로 채운다.
	 */
	public void markFailed(AnalysisFailureCode failureCode, String failureReason,
			Instant startedAt, Instant completedAt) {
		if (failureCode == null) {
			throw new IllegalArgumentException("FAILED는 failureCode가 필수다(ck_analysis_job_failure_code).");
		}
		if (failureReason == null || failureReason.isBlank()) {
			throw new IllegalArgumentException("FAILED는 failureReason이 필수다(ck_analysis_job_status_2).");
		}
		this.status = AnalysisJobStatus.FAILED;
		this.startedAt = firstNonNull(startedAt, this.startedAt, completedAt);
		this.completedAt = completedAt;
		this.failureCode = failureCode;
		this.failureReason = failureReason;
	}

	/** 분석 성공 결과를 가리킨다. */
	public void attachAnalysis(UUID analysisId) {
		this.analysisId = analysisId;
	}

	public boolean isActive() {
		return status == AnalysisJobStatus.QUEUED || status == AnalysisJobStatus.RUNNING;
	}

	private static Instant firstNonNull(Instant... candidates) {
		for (Instant candidate : candidates) {
			if (candidate != null) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("started_at 을 채울 시각이 하나도 없다.");
	}
}
