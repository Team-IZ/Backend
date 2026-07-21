package com.bigproject.backend.domain.operations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * storage_usage_snapshot 테이블 매핑 엔티티. 기관·저장 범위별 클라우드 저장량을 주기적으로 수집한 시점 스냅샷(append-only 로그).
 * 수집 배치(별도 스케줄러/잡)가 INSERT를 담당하고, 이 도메인에서는 월별 저장량 조회(findUsage)를 위한 READ 전용으로 사용한다.
 */
@Getter
@Entity
@Table(name = "storage_usage_snapshot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StorageUsageSnapshot {

	@Id
	@UuidGenerator
	@Column(name = "storage_snapshot_id", updatable = false, nullable = false)
	private UUID storageSnapshotId;

	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	@Column(name = "as_of_at", nullable = false, updatable = false)
	private Instant asOfAt;

	// DB CHECK: storage_category IN ('CODE_ARTIFACT','SESSION_TRANSCRIPT','SCORE_EVIDENCE','REPORT_EXPORT','CURRICULUM_PDF','DATABASE')
	@Enumerated(EnumType.STRING)
	@Column(name = "storage_category", nullable = false, updatable = false, length = 100)
	private StorageCategory storageCategory;

	// storage_scope는 DDL에 고정 값 목록(CHECK)이 없어 문자열 그대로 보관한다.
	@Column(name = "storage_scope", nullable = false, updatable = false, length = 100)
	private String storageScope;

	// 집계 실패(aggregationStatus=FAILED) 시에는 null이다(DB CHECK로 강제됨).
	@Column(name = "object_count", updatable = false)
	private Integer objectCount;

	@Column(name = "byte_count", updatable = false)
	private Long byteCount;

	// DB CHECK: source_type IN ('DB_METADATA','DB_SYSTEM_CATALOG','CLOUD_API','CLOUD_INVENTORY','CLOUD_METRIC')
	@Enumerated(EnumType.STRING)
	@Column(name = "source_type", nullable = false, updatable = false, length = 100)
	private SourceType sourceType;

	@Column(name = "measurement_method", nullable = false, updatable = false, length = 100)
	private String measurementMethod;

	@Column(name = "source_ref", nullable = false, updatable = false)
	private String sourceRef;

	@Column(name = "collection_started_at", nullable = false, updatable = false)
	private Instant collectionStartedAt;

	@Column(name = "collected_at", nullable = false, updatable = false)
	private Instant collectedAt;

	@Column(name = "calculation_version", nullable = false, updatable = false)
	private Integer calculationVersion;

	// DB CHECK: aggregation_status IN ('SUCCEEDED','FAILED')
	@Enumerated(EnumType.STRING)
	@Column(name = "aggregation_status", nullable = false, updatable = false, length = 100)
	private AggregationStatus aggregationStatus;

	@Column(name = "failure_reason", updatable = false)
	private String failureReason;

	@Column(name = "source_watermark", updatable = false)
	private String sourceWatermark;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public enum StorageCategory {
		CODE_ARTIFACT, SESSION_TRANSCRIPT, SCORE_EVIDENCE, REPORT_EXPORT, CURRICULUM_PDF, DATABASE
	}

	public enum SourceType {
		DB_METADATA, DB_SYSTEM_CATALOG, CLOUD_API, CLOUD_INVENTORY, CLOUD_METRIC
	}

	public enum AggregationStatus {
		SUCCEEDED, FAILED
	}
}
