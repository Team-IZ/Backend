package com.bigproject.backend.domain.organization.domain;

import com.bigproject.backend.domain.operations.domain.DisclosureScope;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * organization_policy 테이블 매핑 엔티티.
 * 기관별 예산·데이터 보존기간·기본 공개범위 정책을 "버전" 단위로 이력 관리한다(UPDATE가 아니라 새 버전 INSERT + 이전 버전 SUPERSEDE 처리).
 *
 * organization 도메인의 "기관 생성 시 최초(버전 1) 정책 생성"과 operations 도메인의 "운영 설정 조회/변경(새 버전 발급)"이
 * 모두 이 클래스를 함께 사용한다. 원래는 두 도메인에 동일 테이블을 매핑하는 엔티티를 각각 두려 했으나,
 * Spring Data JPA가 리포지토리 빈 이름을 패키지와 무관하게 "인터페이스 simple name"으로 등록하는 바람에
 * 두 OrganizationPolicyRepository가 빈 이름 충돌을 일으켜(BeanDefinitionOverrideException) 이 클래스 하나로 통합했다.
 *
 * 팀 폴더 구조 표준(operations/domain/OperationSetting.java 별도 보유)과는 다르지만, 같은 테이블을 매핑하는
 * 엔티티를 두 개로 쪼개면 컬럼 변경 시 한쪽만 고치고 누락되는 등 매핑 드리프트 위험이 더 크다고 판단해
 * 이 구조를 유지하기로 확인함.
 */
@Getter
@Entity
@Table(name = "organization_policy")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationPolicy {

	@Id
	@UuidGenerator
	@Column(name = "policy_id", updatable = false, nullable = false)
	private UUID policyId;

	// organization.org_id FK. 같은 도메인이지만 버전 이력 테이블 특성상 연관관계 대신 원시 UUID로 보관한다.
	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	@Column(name = "policy_version", nullable = false, updatable = false)
	private Integer policyVersion;

	@Column(name = "monthly_ai_budget", nullable = false, updatable = false, precision = 18, scale = 6)
	private BigDecimal monthlyAiBudget;

	@Column(name = "currency_code", nullable = false, updatable = false, length = 3)
	private String currencyCode;

	@Column(name = "retention_days", nullable = false, updatable = false)
	private Integer retentionDays;

	@Enumerated(EnumType.STRING)
	@Column(name = "default_disclosure_scope", nullable = false, updatable = false, length = 100)
	private DisclosureScope defaultDisclosureScope;

	@Column(name = "effective_from", nullable = false, updatable = false)
	private Instant effectiveFrom;

	@Column(name = "effective_to")
	private Instant effectiveTo;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 100)
	private Status status;

	@Column(name = "configured_by", nullable = false, updatable = false)
	private UUID configuredBy;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	private OrganizationPolicy(
			UUID orgId,
			BigDecimal monthlyAiBudget,
			String currencyCode,
			Integer retentionDays,
			DisclosureScope defaultDisclosureScope,
			UUID configuredBy
	) {
		this.orgId = orgId;
		this.policyVersion = 1;
		this.monthlyAiBudget = monthlyAiBudget;
		this.currencyCode = currencyCode;
		this.retentionDays = retentionDays;
		this.defaultDisclosureScope = defaultDisclosureScope;
		this.effectiveFrom = Instant.now();
		this.status = Status.ACTIVE;
		this.configuredBy = configuredBy;
	}

	/** 기관 생성 시 발급되는 최초(버전 1) 정책. */
	public static OrganizationPolicy createInitial(
			UUID orgId,
			BigDecimal monthlyAiBudget,
			String currencyCode,
			int retentionDays,
			DisclosureScope defaultDisclosureScope,
			UUID configuredBy
	) {
		return new OrganizationPolicy(orgId, monthlyAiBudget, currencyCode, retentionDays, defaultDisclosureScope, configuredBy);
	}

	/** 기존 활성 정책 다음 버전을 발급한다(operations 도메인의 운영 설정 변경에서 사용). currencyCode는 변경 대상이 아니므로 이전 값을 그대로 이어받는다. */
	public static OrganizationPolicy createNextVersion(
			OrganizationPolicy previous,
			BigDecimal monthlyAiBudget,
			int retentionDays,
			DisclosureScope defaultDisclosureScope,
			UUID configuredBy
	) {
		OrganizationPolicy next = new OrganizationPolicy();
		next.orgId = previous.orgId;
		next.policyVersion = previous.policyVersion + 1;
		next.monthlyAiBudget = monthlyAiBudget;
		next.currencyCode = previous.currencyCode;
		next.retentionDays = retentionDays;
		next.defaultDisclosureScope = defaultDisclosureScope;
		next.effectiveFrom = Instant.now();
		next.status = Status.ACTIVE;
		next.configuredBy = configuredBy;
		return next;
	}

	/** 새 버전이 발급될 때 이 버전을 과거 이력으로 전환한다. */
	public void supersede() {
		this.status = Status.SUPERSEDED;
		this.effectiveTo = Instant.now();
	}

	// DB CHECK: status IN ('ACTIVE','SUPERSEDED','EXPIRED'). 별도 공용 enum 파일 없이 정책 엔티티에 종속시켜 정의한다.
	public enum Status {
		ACTIVE, SUPERSEDED, EXPIRED
	}
}
