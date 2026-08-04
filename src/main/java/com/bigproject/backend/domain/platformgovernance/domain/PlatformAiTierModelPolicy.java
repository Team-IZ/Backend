package com.bigproject.backend.domain.platformgovernance.domain;

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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * platform_ai_tier_model_policy 테이블 매핑 엔티티. v06 신규.
 *
 * <p>질문 생성·요약 기능의 <b>티어 ↔ 실제 모델 매핑</b>을 보존하는 버전형 플랫폼 정책이다.
 *
 * <p>목업 SA-03의 핵심 설계: "<b>3티어 추상화</b>라 모델이 단종돼도 기관은 아무것도 하지 않는다."
 * 기관은 {@code organization_policy.question_generation_tier_code} 등으로 <b>티어 이름만</b> 고르고,
 * 그 티어가 어떤 모델을 쓰는지는 이 테이블이 정한다. 모델이 단종되면 플랫폼이 이 매핑만 바꾸면 된다.
 *
 * <p>키는 (기능, 티어)이며 그 조합마다 버전이 따로 올라간다 — DB UNIQUE (feature_code, tier_code, policy_version).
 */
@Getter
@Entity
@Table(name = "platform_ai_tier_model_policy")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformAiTierModelPolicy {

	@Id
	@UuidGenerator
	@Column(name = "tier_policy_id", updatable = false, nullable = false)
	private UUID tierPolicyId;

	// DB CHECK: feature_code IN ('QUESTION_GENERATION','SUMMARY_DRAFT')
	// 채점(ANSWER_GRADING)은 티어 대상이 아니라 PlatformGradingModelPolicy가 고정한다.
	@Enumerated(EnumType.STRING)
	@Column(name = "feature_code", nullable = false, updatable = false, length = 100)
	private FeatureCode featureCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "tier_code", nullable = false, updatable = false, length = 30)
	private AiTier tierCode;

	/** 해당 (기능, 티어)가 실제로 호출할 논리 모델. */
	@Column(name = "model_id", nullable = false, updatable = false)
	private UUID modelId;

	@Column(name = "policy_version", nullable = false, updatable = false)
	private Integer policyVersion;

	// DB CHECK: status IN ('ACTIVE','SUPERSEDED')
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private PlatformGradingModelPolicy.Status status;

	@Column(name = "effective_from", nullable = false, updatable = false)
	private Instant effectiveFrom;

	@Column(name = "effective_to")
	private Instant effectiveTo;

	@Column(name = "changed_by", nullable = false, updatable = false)
	private UUID changedBy;

	@Column(name = "change_reason")
	private String changeReason;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private PlatformAiTierModelPolicy(
			FeatureCode featureCode,
			AiTier tierCode,
			UUID modelId,
			int policyVersion,
			UUID changedBy,
			String changeReason
	) {
		this.featureCode = featureCode;
		this.tierCode = tierCode;
		this.modelId = modelId;
		this.policyVersion = policyVersion;
		this.status = PlatformGradingModelPolicy.Status.ACTIVE;
		this.effectiveFrom = Instant.now();
		this.changedBy = changedBy;
		this.changeReason = changeReason;
	}

	/**
	 * (기능, 티어) 조합의 다음 정책 버전을 발급한다.
	 * 이전 활성 버전은 호출부에서 {@link #supersede()}로 먼저 닫아야 한다.
	 */
	public static PlatformAiTierModelPolicy createNextVersion(
			FeatureCode featureCode,
			AiTier tierCode,
			UUID modelId,
			int previousVersion,
			UUID changedBy,
			String changeReason
	) {
		return new PlatformAiTierModelPolicy(
				featureCode, tierCode, modelId, previousVersion + 1, changedBy, changeReason
		);
	}

	public void supersede() {
		this.status = PlatformGradingModelPolicy.Status.SUPERSEDED;
		this.effectiveTo = Instant.now();
	}

	/** 티어 선택이 적용되는 AI 기능. */
	public enum FeatureCode {
		QUESTION_GENERATION, SUMMARY_DRAFT
	}
}
