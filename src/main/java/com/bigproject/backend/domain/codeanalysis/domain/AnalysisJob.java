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
 * <p>현재는 <b>읽기 전용</b>이다. 행을 만드는 주체는 마감 후 분석 배치이며 아직 구현되지 않았다.
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

	/**
	 * 11종이다. 앞 6종은 분석 실행 실패, 뒤 5종은 저장소 접근 실패다(S-03).
	 * 저장소 접근 주체가 AI 서버로 확정되어 저장소 사유를 별도 필드로 받지 않고 이 컬럼에 통합했다.
	 */
	@Column(name = "failure_code", length = 100)
	private String failureCode;

	@Column(name = "created_at", nullable = false, updatable = false, insertable = false)
	private Instant createdAt;
}
