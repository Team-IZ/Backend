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
 * ⚠️ 같은 테이블을 operations 도메인({@code com.bigproject.backend.domain.operations.domain.OrganizationPolicy})에서도
 *    별도 엔티티로 매핑한다(폴더 구조 상 두 도메인 모두 필요하다고 명시됨). 책임을 다음과 같이 나눴다.
 *      - organization 도메인: 기관 "생성" 시 최초(버전 1) 정책만 생성한다(이 클래스, createInitial()).
 *      - operations 도메인: 운영 설정 "조회/변경"(새 버전 발급)을 담당한다.
 *    두 엔티티는 서로 다른 Java 클래스이므로, 같은 org_id 행을 두 트랜잭션에서 동시에 다루지 않도록 주의가 필요하다.
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

	// DB CHECK: status IN ('ACTIVE','SUPERSEDED','EXPIRED'). 별도 공용 enum 파일 없이 정책 엔티티에 종속시켜 정의한다.
	public enum Status {
		ACTIVE, SUPERSEDED, EXPIRED
	}
}
