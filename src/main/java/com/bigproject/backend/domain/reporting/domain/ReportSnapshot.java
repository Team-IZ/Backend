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
 * report_snapshot 테이블 매핑 엔티티. <b>리포트 본문이 여기 있다.</b>
 *
 * <p>한 {@link Report}에 스냅샷이 여러 개 붙고 그중 {@code isActive=true}인 하나가
 * 현재 본문이다. 재생성하면 새 스냅샷이 생기고 이전 것은 비활성으로 내려간다 —
 * 발행 당시 학생이 본 내용이 그대로 남는다.
 *
 * <h2>summary_payload를 String으로 받는 이유</h2>
 *
 * <p>JSONB를 객체로 매핑하지 않고 원문 문자열로 둔다. 페이로드 모양은 화면 계약
 * (Frontend {@code types.ts})을 따라가는데, 그 계약이 바뀔 때마다 엔티티를 고치면
 * <b>과거 스냅샷이 역직렬화되지 않는다.</b> {@code payload_schema_version}으로 버전을 보고
 * 서비스 계층에서 Jackson으로 읽는 편이 이력 보존과 맞는다.
 *
 * <p>⚠ report_snapshot에도 감사 컬럼(created_at·row_version)이 없다. v07 DDL 그대로다.
 */
@Getter
@Entity
@Table(name = "report_snapshot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportSnapshot {

	@Id
	@UuidGenerator
	@Column(name = "snapshot_id", updatable = false, nullable = false)
	private UUID snapshotId;

	@Column(name = "org_id", nullable = false)
	private UUID orgId;

	@Column(name = "report_id", nullable = false)
	private UUID reportId;

	/** 1부터. 재생성할 때마다 올라간다(DB CHECK: > 0). */
	@Column(name = "snapshot_version", nullable = false)
	private Integer snapshotVersion;

	/** 이 스냅샷이 어느 시점의 데이터를 얼린 것인가. 발행 시각(publishedAt)과 다를 수 있다. */
	@Column(name = "as_of_at", nullable = false)
	private Instant asOfAt;

	/** 집계 로직 버전. 로직이 바뀌면 올려서 과거 스냅샷과 구분한다(DB CHECK: > 0). */
	@Column(name = "calculation_version", nullable = false)
	private Integer calculationVersion;

	/** 리포트 본문 JSON 원문. 위 javadoc 참고. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "summary_payload", nullable = false)
	private String summaryPayload;

	@Enumerated(EnumType.STRING)
	@Column(name = "completion_status", nullable = false, length = 100)
	private ReportCompletionStatus completionStatus;

	/** 집계 모수. DB CHECK: >= 0 */
	@Column(name = "sample_count", nullable = false)
	private Integer sampleCount;

	/** 모수 중 결과를 못 읽은 건수. DB CHECK: 0 <= missing <= sample */
	@Column(name = "missing_count", nullable = false)
	private Integer missingCount;

	@Column(name = "payload_hash", nullable = false, length = 128)
	private String payloadHash;

	/** 현재 본문인가. 리포트당 하나만 true여야 한다. */
	@Column(name = "is_active", nullable = false)
	private Boolean isActive;

	/** 이 스냅샷을 만든 생성 실행. DB UNIQUE라 실행 1회당 스냅샷 1개다. */
	@Column(name = "generation_run_id", nullable = false)
	private UUID generationRunId;

	/** summary_payload의 스키마 버전. 역직렬화 분기의 근거다(DB CHECK: > 0). */
	@Column(name = "payload_schema_version", nullable = false)
	private Integer payloadSchemaVersion;

	private ReportSnapshot(UUID orgId, UUID reportId, int snapshotVersion, Instant asOfAt,
			int calculationVersion, String summaryPayload, ReportCompletionStatus completionStatus,
			int sampleCount, int missingCount, String payloadHash, UUID generationRunId,
			int payloadSchemaVersion) {
		this.orgId = orgId;
		this.reportId = reportId;
		this.snapshotVersion = snapshotVersion;
		this.asOfAt = asOfAt;
		this.calculationVersion = calculationVersion;
		this.summaryPayload = summaryPayload;
		this.completionStatus = completionStatus;
		this.sampleCount = sampleCount;
		this.missingCount = missingCount;
		this.payloadHash = payloadHash;
		this.generationRunId = generationRunId;
		this.payloadSchemaVersion = payloadSchemaVersion;
		this.isActive = true;
	}

	/**
	 * 새 본문을 활성 스냅샷으로 만든다.
	 *
	 * <p><b>부르기 전에 이전 활성 스냅샷을 {@link #deactivate()} 해야 한다.</b> "리포트당 활성 하나"는
	 * 이 클래스가 강제할 수 없다 — 다른 행을 봐야 알 수 있는 조건이라 서비스 계층의 책임이다.
	 *
	 * <p>{@code generationRunId}는 DB에서 UNIQUE다({@code uq_report_snapshot_generation_run_id}).
	 * 실행 1회당 스냅샷 1건이라는 뜻이므로, 같은 run으로 두 번 부르면 23505가 난다.
	 *
	 * <p>{@code missingCount <= sampleCount}를 CHECK가 요구한다. 여기서 먼저 막지 않으면
	 * INSERT 시점에 어느 값이 어긋났는지 드러나지 않는다.
	 */
	public static ReportSnapshot create(UUID orgId, UUID reportId, int snapshotVersion, Instant asOfAt,
			int calculationVersion, String summaryPayload, ReportCompletionStatus completionStatus,
			int sampleCount, int missingCount, String payloadHash, UUID generationRunId,
			int payloadSchemaVersion) {
		if (missingCount < 0 || sampleCount < 0 || missingCount > sampleCount) {
			throw new IllegalArgumentException(
					"missing_count는 0 이상 sample_count 이하여야 한다: sample=%d, missing=%d"
							.formatted(sampleCount, missingCount));
		}
		return new ReportSnapshot(orgId, reportId, snapshotVersion, asOfAt, calculationVersion,
				summaryPayload, completionStatus, sampleCount, missingCount, payloadHash,
				generationRunId, payloadSchemaVersion);
	}

	/** 비활성으로 내린다. 새 스냅샷을 활성화하기 전에 부른다. */
	public void deactivate() {
		this.isActive = false;
	}
}
