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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * ai_model 테이블 매핑 엔티티. AI 실행에 사용하는 모델(제공자·모델 코드·컨텍스트 한도 등)의 중앙 마스터.
 * 이 작업 범위(사용량 조회)에서는 {@link AiUsage}가 참조하는 READ 전용 정보로만 사용되며,
 * 모델 등록/변경 API 자체는 organization/operations 도메인 범위 밖이다.
 */
@Getter
@Entity
@Table(name = "ai_model")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiModel {

	@Id
	@UuidGenerator
	@Column(name = "model_id", updatable = false, nullable = false)
	private UUID modelId;

	@Column(name = "model_code", nullable = false, updatable = false, length = 100)
	private String modelCode;

	@Column(name = "provider", nullable = false, updatable = false, length = 100)
	private String provider;

	@Column(name = "provider_model_code", nullable = false, updatable = false, length = 100)
	private String providerModelCode;

	@Column(name = "display_name", nullable = false, length = 200)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 100)
	private Status status;

	// 모델의 기능/제약 정보(JSON). 별도 파싱 없이 원문 JSON 문자열로만 보관한다.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "capability_payload", nullable = false, columnDefinition = "jsonb")
	private String capabilityPayload;

	@Column(name = "context_window", nullable = false)
	private Integer contextWindow;

	@Column(name = "max_output_tokens", nullable = false)
	private Long maxOutputTokens;

	@Column(name = "data_processing_region", nullable = false)
	private String dataProcessingRegion;

	@Column(name = "effective_from", nullable = false)
	private Instant effectiveFrom;

	@Column(name = "effective_to")
	private Instant effectiveTo;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	// DB CHECK: status IN ('ACTIVE', 'INACTIVE')
	public enum Status {
		ACTIVE, INACTIVE
	}
}
