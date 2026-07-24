package com.bigproject.backend.domain.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * organization 테이블 매핑 엔티티.
 * 서비스의 최상위 기관(테넌트) 마스터로, 기관명/운영 상태/삭제(보존) 유예 이력을 관리한다.
 *
 * ⚠️ status 컬럼의 DB CHECK 제약은 ('ACTIVE','SUSPENDED','DELETION_PENDING','DELETED')이지만,
 *    기존에 정의된 {@link OrganizationStatus} enum에는 DELETION_PENDING이 없고 대신
 *    BUDGET_EXCEEDED, PENDING_LEAD_MANAGER 값이 존재한다. enum 파일은 기존 산출물이라 값을 그대로 유지했으므로,
 *    실제 삭제 흐름은 DELETION_PENDING 중간 상태 없이 곧바로 DELETED로 전이하도록 구현했다(softDelete 참고).
 *    추후 DDL과 enum 값 정합성을 함께 맞추는 작업이 필요하다.
 */
@Getter
@Entity
@Table(name = "organization")
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 기본 생성자. 무분별한 생성을 막기 위해 protected로 제한하고 create()로만 생성한다.
public class Organization {

	@Id
	@UuidGenerator // 애플리케이션에서 UUID(v4)를 생성해 INSERT한다. DB의 gen_random_uuid() 기본값과 동일한 역할.
	@Column(name = "org_id", updatable = false, nullable = false)
	private UUID orgId;

	@Column(name = "name", nullable = false, length = 200)
	private String name;

	// 기관명 검색/중복확인용 정규화 값(트림 + 소문자). 부분 유니크 인덱스 uq_organization_normalized_name_active와 대응된다.
	@Column(name = "normalized_name", nullable = false, length = 200)
	private String normalizedName;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 100)
	private OrganizationStatus status;

	// 삭제 유예 종료(파기 가능) 시각. deletionRequestedAt이 채워졌을 때만 값을 가진다(DB CHECK와 동일한 규칙).
	@Column(name = "retention_until")
	private Instant retentionUntil;

	@Column(name = "suspended_at")
	private Instant suspendedAt;

	@Column(name = "suspended_reason")
	private String suspendedReason;

	@Column(name = "deletion_requested_at")
	private Instant deletionRequestedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	// app_user.user_id를 가리키는 FK 값이지만, app_user 엔티티가 이 작업 범위에 없어 UUID 원시값만 보관한다.
	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_by")
	private UUID updatedBy;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	// 낙관적 락 버전. DB 컬럼명이 이미 row_version이라 JPA @Version과 자연스럽게 대응된다.
	@Version
	@Column(name = "row_version", nullable = false)
	private Integer rowVersion;

	private Organization(String name, String normalizedName, UUID createdBy) {
		this.name = name;
		this.normalizedName = normalizedName;
		this.status = OrganizationStatus.ACTIVE;
		this.createdBy = createdBy;
	}

	/** 기관 신규 생성. 생성 직후 상태는 항상 ACTIVE. */
	public static Organization create(String name, String normalizedName, UUID createdBy) {
		return new Organization(name, normalizedName, createdBy);
	}

	/** 이름 변경. name이 null이면 변경하지 않는다(부분 수정). */
	public void rename(String name, String normalizedName) {
		if (name == null || name.isBlank()) {
			return;
		}
		this.name = name;
		this.normalizedName = normalizedName;
	}

	/**
	 * 운영 상태 변경. ACTIVE/SUSPENDED만 직접 지정할 수 있는 값이며,
	 * 그 외 값(BUDGET_EXCEEDED, PENDING_LEAD_MANAGER, DELETED)은 시스템이 파생시키는 상태이므로
	 * 호출 전 서비스 계층에서 검증한다.
	 */
	public void changeStatus(OrganizationStatus status) {
		this.status = status;
		if (status == OrganizationStatus.SUSPENDED) {
			this.suspendedAt = Instant.now();
		} else {
			this.suspendedAt = null;
			this.suspendedReason = null;
		}
	}

	public void touchUpdatedBy(UUID updatedBy) {
		this.updatedBy = updatedBy;
	}

	/** soft-delete: 삭제 요청/완료 시각과, 정책의 보존기간만큼 뒤의 파기 가능 시각(retentionUntil)을 함께 기록한다. */
	public void softDelete(int retentionDays, UUID updatedBy) {
		Instant now = Instant.now();
		this.status = OrganizationStatus.DELETED;
		this.deletionRequestedAt = now;
		this.deletedAt = now;
		this.retentionUntil = now.plus(retentionDays, ChronoUnit.DAYS);
		this.updatedBy = updatedBy;
	}
}
