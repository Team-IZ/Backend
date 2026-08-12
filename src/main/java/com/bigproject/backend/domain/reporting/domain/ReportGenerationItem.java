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
 * report_generation_item 테이블 매핑 엔티티. <b>문제 1건에 대한 AI 호출 1회분</b>이다.
 *
 * <p>AI {@code POST /reports}는 문제마다 따로 부르고 각각 jobId·상태·결과를 돌려주므로,
 * 상위 {@link ReportGenerationRun}보다 낮은 grain이 필요하다(테이블 코멘트).
 *
 * <h2>행을 먼저 만들고 AI를 부른다</h2>
 *
 * <p>{@code request_payload}·{@code request_payload_hash}가 NOT NULL이라 이 순서가 강제된다.
 * {@code external_job_id}는 nullable이므로 202를 받은 뒤 {@link #acceptExternalJob}으로 채운다 —
 * 요청 자체가 거절돼 job이 없는 실패도 행으로 남아야 하기 때문이다.
 *
 * <h2>payload를 String으로 받는 이유</h2>
 *
 * <p>{@link ReportSnapshot#getSummaryPayload()}와 같다. AI의 {@code result}는
 * {@code reportMarkdown}·{@code narrative}·{@code problem}·{@code curriculumRefs}·{@code retest}·
 * {@code versions}를 담은 열린 구조라, DTO로 좁혔다가 다시 직렬화하면 <b>모르는 필드가 소리 없이
 * 사라진다.</b> 여기는 최종 확정 전의 임시 버퍼이므로 원문 보존이 더 중요하다.
 */
@Getter
@Entity
@Table(name = "report_generation_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportGenerationItem {

	/** {@code ck_report_generation_item_request_payload_hash}가 요구하는 형식. */
	private static final String HEX_64 = "^[0-9a-f]{64}$";

	@Id
	@UuidGenerator
	@Column(name = "generation_item_id", updatable = false, nullable = false)
	private UUID generationItemId;

	@Column(name = "generation_run_id", nullable = false, updatable = false)
	private UUID generationRunId;

	@Column(name = "problem_id", nullable = false, updatable = false)
	private UUID problemId;

	@Column(name = "session_id", nullable = false, updatable = false)
	private UUID sessionId;

	/**
	 * AI 요청의 {@code scoreRunId}를 보존한다(컬럼 코멘트).
	 *
	 * <p>🔴 <b>비워 두면 안 된다.</b> AI 멱등키가 {@code {problemId}:{scoreRunId}}라, 이 값이 없으면
	 * 키가 문제당 상수가 된다. 그러면 재생성 때 AI가 <b>처음 jobId를 그대로</b> 돌려주고,
	 * {@code uq_report_generation_item_external_job_id}가 두 번째 행을 23505로 거부한다.
	 * 실행마다 달라지는 값({@code generation_run_id})을 넣는다.
	 */
	@Column(name = "score_run_ref", updatable = false, columnDefinition = "text")
	private String scoreRunRef;

	/**
	 * AI가 202로 준 작업 ID. 전역 UNIQUE({@code uq_report_generation_item_external_job_id}).
	 *
	 * <p>컬럼이 UUID인데 AI 스키마의 {@code jobId}는 {@code format: uuid}가 없는 그냥 string이다.
	 * 실측 응답은 UUID였고({@code f9413c77-943e-...}) 코드 분석 경로도 같은 전제로 동작 중이지만,
	 * 형식이 어긋난 값이 오면 파싱 단계에서 걸러야 한다 — 여기까지 오면 저장이 깨진다.
	 */
	@Column(name = "external_job_id")
	private UUID externalJobId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private ReportGenerationItemStatus status;

	@Column(name = "failure_reason", columnDefinition = "text")
	private String failureReason;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "request_payload", nullable = false, updatable = false)
	private String requestPayload;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "response_payload")
	private String responsePayload;

	// 아래 둘은 DDL이 CHAR(64)라 JDBC 타입을 명시한다. 지정하지 않으면 Hibernate가 varchar로 보고
	// validate에서 타입 불일치가 난다(Organization.createRequestFingerprint와 같은 처리다).
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "request_payload_hash", nullable = false, updatable = false, length = 64)
	private String requestPayloadHash;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "response_payload_hash", length = 64)
	private String responsePayloadHash;

	/** 역직렬화 분기의 근거. DB CHECK: >= 1 */
	@Column(name = "payload_schema_version", nullable = false, updatable = false)
	private Integer payloadSchemaVersion;

	/**
	 * AI가 점수는 냈지만 서술 생성에 실패했는가.
	 *
	 * <p>이게 true면 상위 run과 스냅샷은 {@code PARTIAL}이다(테이블 코멘트). item 자체는
	 * {@code SUCCEEDED}로 둔다 — 결과가 왔고 토큰도 태웠으므로 실패가 아니다.
	 */
	@Column(name = "narrative_failed", nullable = false)
	private Boolean narrativeFailed;

	/** 세션 안에서 몇 번째 문제인가. DB CHECK: NULL이거나 1~3. */
	@Column(name = "problem_no", updatable = false)
	private Short problemNo;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "created_at", nullable = false, updatable = false, insertable = false)
	private Instant createdAt;

	private ReportGenerationItem(UUID generationRunId, UUID problemId, UUID sessionId, Integer problemNo,
			String requestPayload, String requestPayloadHash, int payloadSchemaVersion, String scoreRunRef) {
		this.generationRunId = generationRunId;
		this.problemId = problemId;
		this.sessionId = sessionId;
		this.problemNo = problemNo == null ? null : problemNo.shortValue();
		this.requestPayload = requestPayload;
		this.requestPayloadHash = requestPayloadHash;
		this.payloadSchemaVersion = payloadSchemaVersion;
		this.scoreRunRef = scoreRunRef;
		this.status = ReportGenerationItemStatus.QUEUED;
		this.narrativeFailed = false;
	}

	/** AI를 부르기 전에 저장한다. 위 javadoc의 순서 제약 참고. */
	public static ReportGenerationItem queued(UUID generationRunId, UUID problemId, UUID sessionId,
			Integer problemNo, String requestPayload, String requestPayloadHash,
			int payloadSchemaVersion, String scoreRunRef) {
		if (requestPayloadHash == null || !requestPayloadHash.matches(HEX_64)) {
			throw new IllegalArgumentException(
					"request_payload_hash는 소문자 hex 64자여야 한다: " + requestPayloadHash);
		}
		return new ReportGenerationItem(generationRunId, problemId, sessionId, problemNo,
				requestPayload, requestPayloadHash, payloadSchemaVersion, scoreRunRef);
	}

	/** 202를 받았다. jobId를 붙이고 진행 중으로 올린다. */
	public void acceptExternalJob(UUID externalJobId, Instant startedAt) {
		this.externalJobId = externalJobId;
		this.status = ReportGenerationItemStatus.RUNNING;
		this.startedAt = startedAt;
	}

	/**
	 * SUCCEEDED로 끝낸다. {@code ck_report_generation_item_status_3}이 이 상태에서
	 * {@code completed_at}을 NOT NULL로 요구한다.
	 */
	public void markSucceeded(String responsePayload, String responsePayloadHash,
			boolean narrativeFailed, Instant completedAt) {
		this.status = ReportGenerationItemStatus.SUCCEEDED;
		this.responsePayload = responsePayload;
		this.responsePayloadHash = responsePayloadHash;
		this.narrativeFailed = narrativeFailed;
		this.completedAt = clampCompletedAt(completedAt);
		this.failureReason = null;
	}

	/**
	 * FAILED로 끝낸다. {@code ck_report_generation_item_status_2}가 사유를 요구한다.
	 *
	 * <p>응답이 있었다면 함께 남긴다 — 실패 사유가 응답 본문에만 있는 경우가 있고,
	 * 그걸 버리면 나중에 왜 실패했는지 재구성할 수 없다.
	 */
	public void markFailed(String failureReason, String responsePayload, String responsePayloadHash,
			Instant completedAt) {
		if (failureReason == null || failureReason.isBlank()) {
			throw new IllegalArgumentException(
					"FAILED는 failure_reason이 필수다(ck_report_generation_item_status_2).");
		}
		this.status = ReportGenerationItemStatus.FAILED;
		this.failureReason = failureReason;
		this.responsePayload = responsePayload;
		this.responsePayloadHash = responsePayloadHash;
		this.completedAt = clampCompletedAt(completedAt);
	}

	/** AI가 job을 잊었다. 같은 run 안에서 다시 보내기 위해 요청 전 상태로 되돌린다. */
	public void resetForRedispatch() {
		this.externalJobId = null;
		this.status = ReportGenerationItemStatus.QUEUED;
		this.startedAt = null;
	}

	public boolean isActive() {
		return status.isActive();
	}

	public boolean isTerminal() {
		return status.isTerminal();
	}

	/**
	 * {@code ck_report_generation_item_completed_at}이 {@code completed_at >= started_at}을 요구한다.
	 *
	 * <p>AI가 준 시각을 그대로 쓰면 이 조건이 깨질 수 있다 — 우리 {@code started_at}은 202를 받은
	 * 시각이고 AI의 {@code completedAt}은 AI 서버 시계라, 두 시계가 어긋나면 역전이 생긴다.
	 * 저장 자체가 막히는 것보다 시작 시각으로 맞춰 두는 편이 낫다.
	 */
	private Instant clampCompletedAt(Instant completedAt) {
		Instant resolved = completedAt == null ? Instant.now() : completedAt;
		return startedAt != null && resolved.isBefore(startedAt) ? startedAt : resolved;
	}
}
