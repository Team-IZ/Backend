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
 * platform_grading_calibration_version 테이블 매핑 엔티티. v06 신규.
 *
 * <p>채점 모델 정책이 바뀔 때마다 만들어지는 <b>플랫폼 캘리브레이션 버전</b>이다.
 *
 * <p>목업 SA-03: "채점 모델을 바꾸면 <b>전 기관 재캘리브레이션</b>이 필요하고,
 * 버전이 다른 결과끼리는 화면에서 비교를 막는다." — 그 "버전"이 이 엔터티다.
 * 기관별 진행 상태는 {@link OrganizationGradingCalibration}이 따로 관리한다.
 */
@Getter
@Entity
@Table(name = "platform_grading_calibration_version")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformGradingCalibrationVersion {

	@Id
	@UuidGenerator
	@Column(name = "calibration_version_id", updatable = false, nullable = false)
	private UUID calibrationVersionId;

	/** 이 캘리브레이션을 유발한 채점 모델 정책. */
	@Column(name = "grading_policy_id", nullable = false, updatable = false)
	private UUID gradingPolicyId;

	/** 화면·결과 비교에 쓰는 불변 버전 코드. 전체 UNIQUE. 형식: ^[A-Z][A-Z0-9_]{0,99}$ */
	@Column(name = "version_code", nullable = false, updatable = false, length = 100)
	private String versionCode;

	// DB CHECK: status IN ('PENDING','RUNNING','ACTIVE','FAILED','SUPERSEDED')
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private Status status;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	/** DB CHECK: FAILED이면 failure_code와 failed_at이 필수다. */
	@Column(name = "failure_code", length = 100)
	private String failureCode;

	@Column(name = "failure_reason")
	private String failureReason;

	@Column(name = "failed_at")
	private Instant failedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private PlatformGradingCalibrationVersion(UUID gradingPolicyId, String versionCode, UUID createdBy) {
		this.gradingPolicyId = gradingPolicyId;
		this.versionCode = versionCode;
		this.status = Status.PENDING;
		this.createdBy = createdBy;
	}

	/** 채점 모델 변경과 함께 새 캘리브레이션 버전을 만든다. 시작 전이므로 PENDING이다. */
	public static PlatformGradingCalibrationVersion create(UUID gradingPolicyId, String versionCode, UUID createdBy) {
		return new PlatformGradingCalibrationVersion(gradingPolicyId, versionCode, createdBy);
	}

	public void markRunning() {
		this.status = Status.RUNNING;
		this.startedAt = Instant.now();
		clearFailure();
	}

	/** 전 기관 재캘리브레이션이 끝나 이 버전이 결과 비교의 기준이 된다. */
	public void markActive() {
		this.status = Status.ACTIVE;
		this.completedAt = Instant.now();
		clearFailure();
	}

	public void markFailed(String failureCode, String failureReason) {
		this.status = Status.FAILED;
		this.failureCode = failureCode;
		this.failureReason = failureReason;
		this.failedAt = Instant.now();
	}

	/** 더 새로운 캘리브레이션 버전이 활성화되면 이 버전을 과거로 넘긴다. */
	public void supersede() {
		this.status = Status.SUPERSEDED;
	}

	private void clearFailure() {
		this.failureCode = null;
		this.failureReason = null;
		this.failedAt = null;
	}

	public enum Status {
		PENDING, RUNNING, ACTIVE, FAILED, SUPERSEDED
	}
}
