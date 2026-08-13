package com.bigproject.backend.domain.reporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * report_generation_run 테이블 매핑 엔티티. <b>리포트 1건을 만드는 실행 1회분</b>이다.
 *
 * <p>아래에 {@link ReportGenerationItem}이 문제 수(최대 3)만큼 달린다. 등급이 나뉜 이유는 AI
 * {@code POST /reports}가 <b>문제마다 1회</b> 호출되고 각 호출이 별도 jobId·상태·결과를 돌려주기
 * 때문이다 — run 하나에 상태를 뭉치면 "3문제 중 2개만 성공"을 표현할 수 없다.
 *
 * <p>⚠ <b>감사 컬럼이 없다.</b> v07 DDL의 report_generation_run에는 created_at·updated_at이 없다
 * ({@link Report}·{@link ReportSnapshot}과 같다). 다른 도메인 엔티티를 복사해 오면 validate에서 깨진다.
 *
 * <h2>상태별 시각·사유 조합이 CHECK로 강제된다</h2>
 *
 * <p>{@code ck_report_generation_run_status_2}가 넷을 한꺼번에 본다.
 * <pre>
 * QUEUED             : started_at NULL   · completed_at NULL   · failure_reason NULL
 * RUNNING / RETRYING : started_at NOT NULL · completed_at NULL
 * COMPLETED / PARTIAL: started_at NOT NULL · completed_at NOT NULL · failure_reason NULL
 * FAILED             : completed_at NOT NULL · failure_reason NOT NULL
 * </pre>
 * 그래서 필드를 개별로 열지 않고 전이 메서드만 노출한다 — 밖에서 하나씩 채우면 조합이 어긋난 채
 * 저장을 시도하게 되고, 원인이 스택트레이스에 묻힌다({@code AnalysisJob}과 같은 판단이다).
 */
@Getter
@Entity
@Table(name = "report_generation_run")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportGenerationRun {

	/** {@code ck_report_generation_run_request_fingerprint}가 요구하는 형식. */
	private static final String HEX_64 = "^[0-9a-f]{64}$";

	@Id
	@UuidGenerator
	@Column(name = "generation_run_id", updatable = false, nullable = false)
	private UUID generationRunId;

	@Column(name = "report_id", nullable = false, updatable = false)
	private UUID reportId;

	@Enumerated(EnumType.STRING)
	@Column(name = "trigger_type", nullable = false, updatable = false, length = 100)
	private ReportGenerationTriggerType triggerType;

	/**
	 * 전역 UNIQUE({@code uq_report_generation_run_idempotency_key}).
	 *
	 * <p>이 UNIQUE가 <b>다중 인스턴스에서 같은 리포트를 두 번 생성하는 것을 막는 유일한 장치</b>다.
	 * 스케줄러 락(ShedLock 등)이 없어서, 두 인스턴스가 같은 대상을 동시에 집으면 뒤늦은 쪽의
	 * INSERT가 23505로 거부되고 그 세션은 다음 배치가 다시 본다.
	 */
	@Column(name = "idempotency_key", nullable = false, updatable = false, columnDefinition = "text")
	private String idempotencyKey;

	/** 집계 로직 버전. 로직이 바뀌면 올려서 과거 실행과 구분한다(DB CHECK: > 0). */
	@Column(name = "calculation_version", nullable = false, updatable = false)
	private Integer calculationVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 100)
	private ReportGenerationRunStatus status;

	@Column(name = "failure_reason", columnDefinition = "text")
	private String failureReason;

	/**
	 * 재시도 회차. 재시도는 기존 행을 되돌리지 않고 이 값을 +1 한 <b>새 행</b>으로 남긴다
	 * (analysis_job과 같은 규칙). DB CHECK: > 0.
	 */
	@Column(name = "execution_no", nullable = false, updatable = false)
	private Integer executionNo;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	/**
	 * 이 실행이 무엇을 요청했는지의 지문. 소문자 hex 64자여야 한다
	 * ({@code ck_report_generation_run_request_fingerprint}).
	 *
	 * <p>{@link #idempotencyKey}와 역할이 다르다. 멱등키는 "같은 의도인가"를, 지문은 "같은 입력인가"를
	 * 답한다 — 같은 회차를 다시 돌렸는데 그 사이 채점이 바뀌었다면 키는 달라도 지문으로 차이를 보인다.
	 */
	// DDL이 CHAR(64)라 JDBC 타입을 명시한다. 지정하지 않으면 Hibernate가 varchar로 보고 validate에서
	// 타입 불일치가 난다(Organization.createRequestFingerprint와 같은 처리다).
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
	private String requestFingerprint;

	private ReportGenerationRun(UUID reportId, ReportGenerationTriggerType triggerType, String idempotencyKey,
			int calculationVersion, int executionNo, String requestFingerprint) {
		this.reportId = reportId;
		this.triggerType = triggerType;
		this.idempotencyKey = idempotencyKey;
		this.calculationVersion = calculationVersion;
		this.executionNo = executionNo;
		this.requestFingerprint = requestFingerprint;
		this.status = ReportGenerationRunStatus.QUEUED;
	}

	/**
	 * 실행을 접수 대기 상태로 만든다. AI를 부르기 <b>전에</b> 저장한다.
	 *
	 * <p>호출 후에 만들면 202를 받고도 행이 없는 순간이 생기고, 그 사이 프로세스가 죽으면 AI 쪽에는
	 * 실행이 있는데 우리 원장에는 없다. 먼저 QUEUED로 남기면 최악이라도 고아 run이 남을 뿐이다.
	 */
	public static ReportGenerationRun queued(UUID reportId, ReportGenerationTriggerType triggerType,
			String idempotencyKey, int calculationVersion, int executionNo, String requestFingerprint) {
		if (requestFingerprint == null || !requestFingerprint.matches(HEX_64)) {
			// INSERT까지 가면 CHECK 위반이 되는데, 그 시점에는 어느 값이 문제인지 드러나지 않는다.
			throw new IllegalArgumentException(
					"request_fingerprint는 소문자 hex 64자여야 한다: " + requestFingerprint);
		}
		return new ReportGenerationRun(reportId, triggerType, idempotencyKey,
				calculationVersion, executionNo, requestFingerprint);
	}

	/** QUEUED → RUNNING. CHECK가 이 상태에서 completedAt·failureReason을 NULL로 요구한다. */
	public void markRunning(Instant startedAt) {
		this.status = ReportGenerationRunStatus.RUNNING;
		this.startedAt = startedAt;
		this.completedAt = null;
		this.failureReason = null;
	}

	/**
	 * COMPLETED 또는 PARTIAL로 끝낸다.
	 *
	 * <p>PARTIAL은 item 중 하나라도 실패했거나 {@code narrative_failed=true}가 섞인 경우다
	 * (report_generation_item 테이블 코멘트). FAILED로 뭉뚱그리면 나머지 문제의 결과가 멀쩡한데도
	 * 리포트 전체를 못 쓰는 것으로 보인다.
	 */
	public void markCompleted(ReportGenerationRunStatus terminal, Instant completedAt) {
		if (terminal != ReportGenerationRunStatus.COMPLETED && terminal != ReportGenerationRunStatus.PARTIAL) {
			throw new IllegalArgumentException("성공 종료 상태가 아니다: " + terminal);
		}
		if (this.startedAt == null) {
			// CHECK가 두 상태 모두 started_at을 NOT NULL로 요구한다. QUEUED에서 곧바로 끝나는
			// 경로는 없지만, 있더라도 완료 시각으로 채워 기록 자체를 잃지 않는다.
			this.startedAt = completedAt;
		}
		this.status = terminal;
		this.completedAt = completedAt;
		this.failureReason = null;
	}

	/**
	 * FAILED로 끝낸다. CHECK가 {@code completed_at}과 {@code failure_reason}을 함께 요구하므로
	 * 사유가 비면 여기서 먼저 막는다.
	 */
	public void markFailed(String failureReason, Instant completedAt) {
		if (failureReason == null || failureReason.isBlank()) {
			throw new IllegalArgumentException(
					"FAILED는 failure_reason이 필수다(ck_report_generation_run_status_2).");
		}
		this.status = ReportGenerationRunStatus.FAILED;
		this.completedAt = completedAt;
		this.failureReason = failureReason;
	}

	public boolean isTerminal() {
		return status.isTerminal();
	}
}
