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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * organization_grading_calibration 테이블 매핑 엔티티. v06 신규.
 *
 * <p>{@link PlatformGradingCalibrationVersion} 하나에 대해 <b>기관별 재캘리브레이션 진행 상태</b>를 추적한다.
 * 채점 모델을 바꾸면 전 기관에 대해 이 행이 PENDING으로 생성되고, 배치가 기관별로 처리한다.
 *
 * <p>목업 SA-03의 확인 모달은 "전 기관 재캘리브레이션이 필요하다"를 알려야 하는데,
 * 진행률(몇 개 기관이 끝났는지)은 이 테이블을 집계해서 만든다.
 */
@Getter
@Entity
@Table(name = "organization_grading_calibration")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationGradingCalibration {

	@Id
	@UuidGenerator
	@Column(name = "organization_calibration_id", updatable = false, nullable = false)
	private UUID organizationCalibrationId;

	@Column(name = "calibration_version_id", nullable = false, updatable = false)
	private UUID calibrationVersionId;

	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	// DB CHECK: status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private Status status;

	@Column(name = "started_at")
	private Instant startedAt;

	/** DB CHECK: SUCCEEDED이면 필수. */
	@Column(name = "completed_at")
	private Instant completedAt;

	/** DB CHECK: FAILED이면 failure_code와 failed_at이 필수. */
	@Column(name = "failure_code", length = 100)
	private String failureCode;

	@Column(name = "failure_reason")
	private String failureReason;

	@Column(name = "failed_at")
	private Instant failedAt;

	@Column(name = "retry_count", nullable = false)
	private Integer retryCount;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private OrganizationGradingCalibration(UUID calibrationVersionId, UUID orgId) {
		this.calibrationVersionId = calibrationVersionId;
		this.orgId = orgId;
		this.status = Status.PENDING;
		this.retryCount = 0;
	}

	/** 채점 모델 변경 시 대상 기관마다 만들어지는 대기 행. */
	public static OrganizationGradingCalibration createPending(UUID calibrationVersionId, UUID orgId) {
		return new OrganizationGradingCalibration(calibrationVersionId, orgId);
	}

	public void markRunning() {
		this.status = Status.RUNNING;
		this.startedAt = Instant.now();
		clearFailure();
	}

	public void markSucceeded() {
		this.status = Status.SUCCEEDED;
		this.completedAt = Instant.now();
		clearFailure();
	}

	public void markFailed(String failureCode, String failureReason) {
		this.status = Status.FAILED;
		this.failureCode = failureCode;
		this.failureReason = failureReason;
		this.failedAt = Instant.now();
	}

	/** 실패한 기관을 다시 시도할 때 호출한다. */
	public void retry() {
		this.retryCount = this.retryCount + 1;
		markRunning();
	}

	private void clearFailure() {
		this.failureCode = null;
		this.failureReason = null;
		this.failedAt = null;
	}

	public enum Status {
		PENDING, RUNNING, SUCCEEDED, FAILED
	}
}
