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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * organization_policy 테이블 매핑 엔티티.
 * 기관별 예산·데이터 보존기간·기본 공개범위 정책을 "버전" 단위로 이력 관리한다(UPDATE가 아니라 새 버전 INSERT + 이전 버전 SUPERSEDE 처리).
 *
 * ⚠️ 같은 테이블을 organization 도메인({@code com.bigproject.backend.domain.organization.domain.OrganizationPolicy})에서도
 *    별도 엔티티로 매핑한다(폴더 구조 상 두 도메인 모두 필요하다고 명시됨). 책임을 다음과 같이 나눴다.
 *      - organization 도메인: 기관 "생성" 시 최초(버전 1) 정책만 생성한다.
 *      - operations 도메인: 운영 설정 "조회/변경"(새 버전 발급)을 담당한다(이 클래스, createNextVersion()/supersede()).
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

	// organization.org_id FK. 원시 UUID로만 보관하고(연관관계 매핑 없음) 조회 시 organization 도메인 리포지토리를 함께 사용한다.
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

	// 새 버전이 발급되며 이 버전이 superseded 될 때 채워진다.
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

	/** 기존 활성 정책 다음 버전을 발급한다. currencyCode는 변경 대상이 아니므로 이전 값을 그대로 이어받는다. */
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
