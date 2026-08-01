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
 * status 값은 DB CHECK 제약('ACTIVE','SUSPENDED','DELETION_PENDING','DELETED')과 {@link OrganizationStatus}를
 * 일치시켜 두었다. v2 IA 목업의 `예산 초과`·`오퍼레이터 미배정` 배지는 저장 상태가 아니라 응답의 파생 플래그다
 * ({@code OrganizationResponse.budgetExceeded} / {@code operatorUnassigned}).
 *
 * ⚠️ 삭제 흐름은 DELETION_PENDING 중간 상태를 거치지 않고 곧바로 DELETED로 전이한다({@link #softDelete} 참고).
 *    보존기간 경과 후 파기를 별도 단계로 나눌 거면 이 전이를 함께 손봐야 한다.
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

	/**
	 * 기관명 검색/중복확인용 정규화 값(NFKC·트림·연속공백 축소·case folding).
	 *
	 * <p>⚠ v06에서 <b>부분 유니크(deleted_at IS NULL) → 전체 UNIQUE</b>로 바뀌었다.
	 * 정의서: "물리 파기 전에는 상태와 관계없이 재사용하지 않는다" — 즉 soft-delete된 기관의 이름도 선점 상태로 남는다.
	 */
	@Column(name = "normalized_name", nullable = false, length = 200)
	private String normalizedName;

	/** URL·외부 연동용 짧은 식별값. DB CHECK: ^[a-z0-9]+(-[a-z0-9]+)*$ */
	@Column(name = "slug", length = 64)
	private String slug;

	/** 화면 표시용 코드. 권한·테넌트 판정에는 쓰지 않는다. */
	@Column(name = "display_code", length = 32)
	private String displayCode;

	/** 초대 입력 검증용 도메인. NULL이면 도메인 제한을 적용하지 않으며, 권한·테넌트의 단독 근거로 쓰지 않는다. */
	@Column(name = "email_domain", length = 255)
	private String emailDomain;

	/*
	 * 생성 멱등성. 같은 키·같은 지문이면 최초 생성 결과를 그대로 돌려주고, 같은 키·다른 지문이면 거부한다.
	 * 목업 SA-01: "생성 실패는 아무것도 남기지 않는다" — 재시도가 반쯤 만들어진 기관을 만들지 않도록 하는 장치다.
	 * DB CHECK로 두 컬럼은 동시 NULL 또는 동시 NOT NULL이어야 한다.
	 */
	@Column(name = "create_idempotency_key", updatable = false)
	private UUID createIdempotencyKey;

	@Column(name = "create_request_fingerprint", updatable = false, length = 64)
	private String createRequestFingerprint;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 100)
	private OrganizationStatus status;

	/** 삭제 멱등성. 생성 쪽과 동일한 규칙(키·지문 쌍). */
	@Column(name = "deletion_idempotency_key")
	private UUID deletionIdempotencyKey;

	@Column(name = "deletion_request_fingerprint", length = 64)
	private String deletionRequestFingerprint;

	// 삭제 유예 종료(파기 가능) 시각. deletionRequestedAt이 채워졌을 때만 값을 가진다(DB CHECK와 동일한 규칙).
	@Column(name = "retention_until")
	private Instant retentionUntil;

	@Column(name = "suspended_at")
	private Instant suspendedAt;

	@Column(name = "suspended_reason")
	private String suspendedReason;

	@Column(name = "deletion_requested_at")
	private Instant deletionRequestedAt;

	/**
	 * 삭제를 요청한 슈퍼어드민.
	 * ⚠ v06 CHECK(ck_organization_deletion_required)가 DELETION_PENDING·DELETED 상태에서
	 * retention_until과 함께 <b>이 값을 필수로 요구</b>한다. 비우고 삭제하면 제약 위반으로 실패한다.
	 */
	@Column(name = "deletion_requested_by")
	private UUID deletionRequestedBy;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	/*
	 * v06 신규 — 물리 파기 파이프라인 상태. 삭제 요청이 성공하면 SCHEDULED로 전이하고,
	 * 실제 파기는 테넌트 데이터 전체를 지우는 별도 배치가 IN_PROGRESS → COMPLETED/FAILED로 진행한다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "purge_status", nullable = false, length = 30)
	private PurgeStatus purgeStatus;

	@Column(name = "purge_started_at")
	private Instant purgeStartedAt;

	@Column(name = "purge_failed_at")
	private Instant purgeFailedAt;

	@Column(name = "purge_failure_code", length = 100)
	private String purgeFailureCode;

	/** 보존기간 내 삭제 요청을 복구한 최근 시각·주체. DB CHECK로 두 값은 동시 NULL 또는 동시 NOT NULL이다. */
	@Column(name = "restored_at")
	private Instant restoredAt;

	@Column(name = "restored_by")
	private UUID restoredBy;

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
		this.purgeStatus = PurgeStatus.NONE;
		this.createdBy = createdBy;
	}

	/** 기관 신규 생성. 생성 직후 상태는 항상 ACTIVE이고 파기 상태는 NONE이다. */
	public static Organization create(String name, String normalizedName, UUID createdBy) {
		return new Organization(name, normalizedName, createdBy);
	}

	/** 생성 모달에서 함께 받는 선택 식별 정보(슬러그·표시 코드·이메일 도메인)를 채운다. */
	public void applyProfile(String slug, String displayCode, String emailDomain) {
		this.slug = slug;
		this.displayCode = displayCode;
		this.emailDomain = emailDomain;
	}

	/**
	 * 생성 멱등성 키와 요청 지문을 기록한다. DB CHECK가 두 값의 동시 NULL/동시 NOT NULL을 강제하므로
	 * 한쪽만 들어오면 둘 다 버린다(이관·시드 행은 NULL을 허용한다).
	 */
	public void applyCreateIdempotency(UUID idempotencyKey, String requestFingerprint) {
		if (idempotencyKey == null || requestFingerprint == null) {
			return;
		}
		this.createIdempotencyKey = idempotencyKey;
		this.createRequestFingerprint = requestFingerprint;
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
	 * 그 외 값(DELETION_PENDING, DELETED)은 삭제 흐름이 설정하는 상태이므로 호출 전 서비스 계층에서 검증한다.
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

	/**
	 * soft-delete: 삭제 요청/완료 시각과, 정책의 보존기간만큼 뒤의 파기 가능 시각(retentionUntil)을 함께 기록한다.
	 *
	 * <p>v06 CHECK(ck_organization_deletion_required)가 DELETED 상태에서 retention_until과
	 * <b>deletion_requested_by를 모두 요구</b>하므로 요청자를 반드시 함께 남긴다. 요청자는 이 액션을 수행한
	 * 슈퍼어드민 본인이다. 삭제 요청이 성공하면 파기 상태를 SCHEDULED로 전이한다(정의서 purge_status 규칙).
	 */
	public void softDelete(int retentionDays, UUID requestedBy, UUID idempotencyKey, String requestFingerprint) {
		Instant now = Instant.now();
		this.status = OrganizationStatus.DELETED;
		this.deletionRequestedAt = now;
		this.deletionRequestedBy = requestedBy;
		this.deletedAt = now;
		this.retentionUntil = now.plus(retentionDays, ChronoUnit.DAYS);
		this.purgeStatus = PurgeStatus.SCHEDULED;
		if (idempotencyKey != null && requestFingerprint != null) {
			this.deletionIdempotencyKey = idempotencyKey;
			this.deletionRequestFingerprint = requestFingerprint;
		}
		this.updatedBy = requestedBy;
	}

	/**
	 * soft-delete 복구. 목업 SA-02 case 7: "보존기간이 지난 뒤 파기됩니다. <b>그전까지는 복구할 수 있습니다.</b>"
	 *
	 * <p>DB CHECK(ck_organization_status_2)가 ACTIVE 상태에서 deletion_requested_at·deleted_at이 모두
	 * NULL이기를 요구하므로 삭제 흔적 세 컬럼을 함께 비운다. 정지가 아니라 활성으로 되돌린다 —
	 * 삭제 전 상태를 따로 보관하는 컬럼이 없고, 복구의 의도는 "다시 쓰겠다"이기 때문이다.
	 */
	public void restore(UUID updatedBy) {
		Instant now = Instant.now();
		this.status = OrganizationStatus.ACTIVE;
		this.deletionRequestedAt = null;
		this.deletionRequestedBy = null;
		this.deletedAt = null;
		this.retentionUntil = null;
		this.suspendedAt = null;
		this.suspendedReason = null;
		// 파기 예약을 함께 취소한다. 복구했는데 SCHEDULED로 남으면 배치가 살아 있는 기관을 지운다.
		this.purgeStatus = PurgeStatus.NONE;
		this.purgeStartedAt = null;
		this.purgeFailedAt = null;
		this.purgeFailureCode = null;
		this.deletionIdempotencyKey = null;
		this.deletionRequestFingerprint = null;
		this.restoredAt = now;
		this.restoredBy = updatedBy;
		this.updatedBy = updatedBy;
	}

	/**
	 * 보존기간이 지나 파기(purge)할 수 있는 상태인지. 목업 SA-02 case 8의 RETENTION_NOT_MET 판정에 쓴다.
	 * 삭제되지 않았거나 보존기간이 남아 있으면 false.
	 */
	public boolean isPurgeAllowed(Instant now) {
		return status == OrganizationStatus.DELETED
				&& retentionUntil != null
				&& !now.isBefore(retentionUntil);
	}

	/** 파기 배치가 실행을 시작했음을 기록한다. */
	public void markPurgeInProgress() {
		this.purgeStatus = PurgeStatus.IN_PROGRESS;
		this.purgeStartedAt = Instant.now();
		this.purgeFailedAt = null;
		this.purgeFailureCode = null;
	}

	/** 파기가 실패했음을 기록한다. DB CHECK가 FAILED일 때 실패 시각·코드를 함께 요구한다. */
	public void markPurgeFailed(String failureCode) {
		this.purgeStatus = PurgeStatus.FAILED;
		this.purgeFailedAt = Instant.now();
		this.purgeFailureCode = failureCode;
	}

	/** 물리 파기 완료. */
	public void markPurgeCompleted() {
		this.purgeStatus = PurgeStatus.COMPLETED;
		this.purgeFailedAt = null;
		this.purgeFailureCode = null;
	}

	/** DB CHECK: purge_status IN ('NONE','SCHEDULED','IN_PROGRESS','FAILED','COMPLETED') */
	public enum PurgeStatus {
		NONE, SCHEDULED, IN_PROGRESS, FAILED, COMPLETED
	}
}
