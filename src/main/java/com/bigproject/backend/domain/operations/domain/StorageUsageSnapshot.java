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
 * storage_usage_snapshot 테이블 매핑 엔티티. 기관·저장 범위별 저장량을 주기적으로 수집한 시점 스냅샷(append-only 로그).
 * 수집 배치(별도 스케줄러/잡)가 INSERT를 담당하고, 이 도메인에서는 월별 저장량 조회(findUsage)를 위한 READ 전용으로 사용한다.
 *
 * <p><b>v06에서 구조가 통째로 바뀌었다.</b>
 * <ul>
 *   <li>PK가 {@code storage_snapshot_id} → {@code snapshot_id}</li>
 *   <li>{@code as_of_at} → {@code captured_at}, {@code byte_count} → {@code used_bytes}(NOT NULL),
 *       {@code object_count} → {@code file_count}(NOT NULL)</li>
 *   <li>{@code aggregation_status}·{@code failure_reason}·{@code collection_started_at}·{@code collected_at} 삭제 —
 *       <b>실패한 측정은 아예 행을 남기지 않는 모델</b>로 바뀌었다. 따라서 이 테이블의 모든 행은 성공한 측정이다.</li>
 *   <li>{@code measurement_batch_id} 신설 — 화면 총계에 함께 쓰이는 카테고리 행들을 하나의 측정 세트로 묶는다.
 *       "가장 최근 스냅샷"을 고를 때 카테고리별로 따로 고르지 않고 이 배치 단위로 골라야 총계가 어긋나지 않는다.</li>
 *   <li>{@code storage_category}에 {@code ORG_TOTAL}이 추가됐다 —
 *       <b>세부 카테고리와 함께 SUM하면 이중 계산된다.</b> 합산 시 반드시 한쪽만 써야 한다.</li>
 * </ul>
 */
@Getter
@Entity
@Table(name = "storage_usage_snapshot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StorageUsageSnapshot {

	@Id
	@UuidGenerator
	@Column(name = "snapshot_id", updatable = false, nullable = false)
	private UUID snapshotId;

	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	/** 같은 측정 세트(화면 총계에 함께 쓰이는 카테고리 행 묶음)를 식별한다. */
	@Column(name = "measurement_batch_id", nullable = false, updatable = false)
	private UUID measurementBatchId;

	@Column(name = "used_bytes", nullable = false, updatable = false)
	private Long usedBytes;

	/** 파일·객체 수. 원천이 개수를 제공하지 않으면 0이며, 미지원 여부는 measurementMethod로 구분한다. */
	@Column(name = "file_count", nullable = false, updatable = false)
	private Integer fileCount;

	@Column(name = "captured_at", nullable = false, updatable = false)
	private Instant capturedAt;

	// DB CHECK: storage_category IN ('ORG_TOTAL','CODE_ARTIFACT','SESSION_TRANSCRIPT','SCORE_EVIDENCE','REPORT_EXPORT','CURRICULUM_PDF','DATABASE')
	@Enumerated(EnumType.STRING)
	@Column(name = "storage_category", nullable = false, updatable = false, length = 100)
	private StorageCategory storageCategory;

	// storage_scope는 DDL에 고정 값 목록(CHECK)이 없어 문자열 그대로 보관한다.
	@Column(name = "storage_scope", nullable = false, updatable = false, length = 100)
	private String storageScope;

	// DB CHECK: source_type IN ('DB_METADATA','DB_SYSTEM_CATALOG','CLOUD_API','CLOUD_INVENTORY','CLOUD_METRIC')
	@Enumerated(EnumType.STRING)
	@Column(name = "source_type", nullable = false, updatable = false, length = 100)
	private SourceType sourceType;

	@Column(name = "measurement_method", nullable = false, updatable = false, length = 100)
	private String measurementMethod;

	// v06에서 NULL 허용으로 완화됐다(비민감 참조값이라 없을 수 있다).
	@Column(name = "source_ref", updatable = false)
	private String sourceRef;

	@Column(name = "calculation_version", nullable = false, updatable = false)
	private Integer calculationVersion;

	@Column(name = "source_watermark", updatable = false)
	private String sourceWatermark;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/**
	 * 기관 전체 합계를 나타내는 카테고리. 세부 카테고리와 같은 배치에 공존하므로 합산할 때 섞으면 안 된다.
	 */
	public enum StorageCategory {
		ORG_TOTAL, CODE_ARTIFACT, SESSION_TRANSCRIPT, SCORE_EVIDENCE, REPORT_EXPORT, CURRICULUM_PDF, DATABASE
	}

	public enum SourceType {
		DB_METADATA, DB_SYSTEM_CATALOG, CLOUD_API, CLOUD_INVENTORY, CLOUD_METRIC
	}
}
