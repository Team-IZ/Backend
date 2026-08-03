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
 * platform_grading_model_policy 테이블 매핑 엔티티. v06 신규.
 *
 * <p>답변 채점에 쓸 논리 모델을 <b>플랫폼이 고정</b>하는 버전형 정책이다.
 * 목업 SA-03: "채점 모델은 플랫폼이 고정한다. 기관이 바꿀 수 없다 — 바뀌면 점수를 비교할 수 없다."
 *
 * <p>버전형이라 UPDATE가 아니라 <b>이전 버전을 SUPERSEDED로 닫고 새 버전을 INSERT</b>한다.
 * 채점 모델이 바뀌면 전 기관 재캘리브레이션이 필요하므로
 * {@link PlatformGradingCalibrationVersion}을 함께 만들어야 한다.
 */
@Getter
@Entity
@Table(name = "platform_grading_model_policy")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformGradingModelPolicy {

	@Id
	@UuidGenerator
	@Column(name = "grading_policy_id", updatable = false, nullable = false)
	private UUID gradingPolicyId;

	/** 플랫폼 전역에서 증가하는 정책 버전. 전체 UNIQUE다(기관별이 아니라 플랫폼 단위 정책이므로). */
	@Column(name = "policy_version", nullable = false, updatable = false)
	private Integer policyVersion;

	/** 기본 채점 논리 모델. ai_model.model_id를 가리킨다. */
	@Column(name = "model_id", nullable = false, updatable = false)
	private UUID modelId;

	// DB CHECK: status IN ('ACTIVE','SUPERSEDED')
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private Status status;

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

	private PlatformGradingModelPolicy(int policyVersion, UUID modelId, UUID changedBy, String changeReason) {
		this.policyVersion = policyVersion;
		this.modelId = modelId;
		this.status = Status.ACTIVE;
		this.effectiveFrom = Instant.now();
		this.changedBy = changedBy;
		this.changeReason = changeReason;
	}

	/** 다음 정책 버전을 발급한다. 이전 활성 버전은 호출부에서 {@link #supersede()}로 먼저 닫아야 한다. */
	public static PlatformGradingModelPolicy createNextVersion(
			int previousVersion,
			UUID modelId,
			UUID changedBy,
			String changeReason
	) {
		return new PlatformGradingModelPolicy(previousVersion + 1, modelId, changedBy, changeReason);
	}

	/** 새 버전이 발급될 때 이 버전을 과거 이력으로 전환한다. */
	public void supersede() {
		this.status = Status.SUPERSEDED;
		this.effectiveTo = Instant.now();
	}

	public enum Status {
		ACTIVE, SUPERSEDED
	}
}
