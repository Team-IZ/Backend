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

	/**
	 * AI 서버가 202 응답으로 반환한 작업 ID.
	 *
	 * <p>{@code updatable = false}(2026-08-13).
	 *   WHY: 폴러 중복(소유자 필터 없는 {@code findByStatusIn})이 겹치면서 남의 job에 404를 받고
	 *        이 컬럼을 null로 되돌리는 버그가 실운영에서 재현됐다(PR #108). save()/merge()의
	 *        자동생성 UPDATE가 이 컬럼을 아예 못 건드리게 막으면, 같은 종류의 실수가 또 나와도
	 *        실제로 컬럼을 지울 방법이 없다.
	 *   COST: 없다. 정상 접수 건은 202 응답의 ID를 채운 뒤 <b>최초 INSERT 한 번</b>으로 저장하므로
	 *        (INSERT는 이 애너테이션의 영향을 받지 않는다) 기록해야 할 UPDATE 자체가 없다. 폴링
	 *        상태 전이는 엔티티 merge가 아니라 이 컬럼을 SET 절에 넣지 않는 전용 쿼리를 쓴다.
	 *   EXIT: 되돌리려면 이 애너테이션만 지운다. 다만 그 순간 merge가 만드는 UPDATE에 이 컬럼이
	 *        다시 섞이므로, 되돌릴 이유가 생겼다면 그것부터 의심한다.
	 */
	@Column(name = "external_job_id", updatable = false)
	private UUID externalJobId;

	/** 15종. 분석 실행 5종 + 저장소 접근 5종(S-03) + ZIP 검증 5종(S-15). */
	@Enumerated(EnumType.STRING)
	@Column(name = "failure_code", length = 100)
	private AnalysisFailureCode failureCode;

	/**
	 * 이 실행에 쓰라고 지정한 모델. {@code ai_model.model_id}.
	 *
	 * <p>AI가 자기 기본 모델을 고르게 두면 응답 {@code aiUsage[].modelCode}가 우리 카탈로그에 없는
	 * 값일 수 있고, {@code ai_usage.model_code}는 FK라 그 순간 사용량 적재가 통째로 실패한다.
	 * 그래서 <b>요청 시점에 우리가 고르고 그 선택을 여기 남긴다</b> — 나중에 "이 실행은 어느 모델로
	 * 돌았나"를 응답이 아니라 우리 원장으로 답할 수 있어야 한다.
	 */
	@Column(name = "requested_model_id")
	private UUID requestedModelId;

	/** 요청한 계획 문제 수. {@code assessment_problem.problem_no}가 1~3이라 3을 넘길 수 없다. */
	@Column(name = "question_budget")
	private Short questionBudget;

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
	 * 분석 요청을 접수 대기 상태로 만든다.
	 *
	 * <p>정상 경로에서는 아직 영속화하지 않는다. AI가 202로 준 작업 ID를 {@link #acceptExternalJob}
	 * 으로 먼저 붙이고, ID를 포함한 한 번의 INSERT로 저장한다. 요청 자체가 실패한 경우에만 외부 ID가
	 * 없는 FAILED 행을 저장해 실패 이력을 남긴다.
	 *
	 * <p>{@code uq_analysis_job_active(batch_key, job_type) WHERE status IN ('QUEUED','RUNNING')}가
	 * 같은 제출의 동시 실행을 DB에서 막는다.
	 */
	public static AnalysisJob queued(UUID orgId, UUID assessmentRoundId, UUID teamId, UUID submissionId,
			String batchKey, String jobType, int executionNo, String traceId) {
		return new AnalysisJob(orgId, assessmentRoundId, teamId, submissionId,
				batchKey, jobType, executionNo, traceId);
	}

	/**
	 * AI 서버를 부르기 직전에 <b>무엇을 요청했는지</b>를 남긴다.
	 *
	 * <p>{@link #queued}의 인자로 받지 않고 따로 둔 이유: 이 둘은 job의 정체가 아니라 요청 파라미터다.
	 * 팩터리에 계속 인자를 붙이면 어느 것이 상태 전이에 필요한 값이고 어느 것이 기록용인지 흐려진다.
	 */
	public void recordRequest(UUID requestedModelId, Integer questionBudget) {
		this.requestedModelId = requestedModelId;
		// ck_analysis_job_question_budget: NULL 이거나 0보다 커야 한다.
		this.questionBudget = questionBudget == null ? null : questionBudget.shortValue();
	}

	/** 202 응답의 작업 ID를 최초 INSERT 전에 한 번만 반영한다. */
	public void acceptExternalJob(UUID externalJobId) {
		if (externalJobId == null) {
			throw new IllegalArgumentException("externalJobId는 null일 수 없다.");
		}
		if (this.externalJobId != null && !this.externalJobId.equals(externalJobId)) {
			throw new IllegalStateException("externalJobId는 최초 기록 후 변경할 수 없다.");
		}
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
