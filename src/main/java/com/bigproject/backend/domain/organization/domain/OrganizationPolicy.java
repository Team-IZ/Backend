package com.bigproject.backend.domain.organization.domain;

import com.bigproject.backend.domain.platformgovernance.domain.AiTier;
import com.bigproject.backend.domain.disclosure.domain.DisclosureScope;
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

	// v06 DB CHECK: currency_code = 'USD'. 플랫폼 공통 통화이며 기관별 변경을 허용하지 않는다.
	@Column(name = "currency_code", nullable = false, updatable = false, length = 3)
	private String currencyCode;

	/** 월간 토큰 상한. NULL은 무제한이다. */
	@Column(name = "monthly_token_limit", updatable = false)
	private Long monthlyTokenLimit;

	/** 저장량 상한(바이트). NULL은 무제한이다. */
	@Column(name = "storage_limit_bytes", updatable = false)
	private Long storageLimitBytes;

	@Column(name = "retention_days", nullable = false, updatable = false)
	private Integer retentionDays;

	@Enumerated(EnumType.STRING)
	@Column(name = "default_disclosure_scope", nullable = false, updatable = false, length = 100)
	private DisclosureScope defaultDisclosureScope;

	/*
	 * 기관이 고르는 AI 모델 티어. 실제 모델 ID는 플랫폼 정책(platform_ai_tier_model_policy)이 정하고,
	 * 기관은 티어 이름만 선택한다(목업 OP-06 §7: "모델별 단가는 SA-03 — 플랫폼이 정하고 기관은 티어 이름만 본다").
	 *
	 * v07에서 티어 컬럼 2개(question_generation_tier_code·summary_tier_code)가 code_session_tier_code
	 * 하나로 통합됐다. 통합이지 이름 변경만은 아니다 — 요약 계열은 티어 선택 대상에서 빠졌다.
	 *  · platform_ai_tier_model_policy.feature_code CHECK가 'CODE_SESSION' 단일값이 됐다.
	 *  · ai_usage CHECK가 INTERVIEW_BRIEF_GENERATION·REPORT_GENERATION의 tier_code를 NULL로 강제한다.
	 * 즉 요약 티어는 저장할 자리가 사라졌으므로 필드도 함께 제거한다.
	 * DDL 주석: "질문 생성 기능에서 기관이 선택한 플랫폼 모델 티어다."
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "code_session_tier_code", nullable = false, updatable = false, length = 30)
	private AiTier codeSessionTierCode;

	/*
	 * v06 신규 기능 토글 5종. 목업 SA-02 ④ 설정 탭의 스위치들이며, 이전에는 컬럼이 없어
	 * OperationsSchemaPending에서 null로 대체하고 저장 자체를 무시하고 있었다.
	 */
	@Column(name = "allow_manager_invite", nullable = false, updatable = false)
	private Boolean allowManagerInvite;

	@Column(name = "allow_data_export", nullable = false, updatable = false)
	private Boolean allowDataExport;

	@Column(name = "allow_zip_submission", nullable = false, updatable = false)
	private Boolean allowZipSubmission;

	@Column(name = "allow_github_integration", nullable = false, updatable = false)
	private Boolean allowGithubIntegration;

	@Column(name = "enable_big_project_contribution_analysis", nullable = false, updatable = false)
	private Boolean enableBigProjectContributionAnalysis;

	@Column(name = "effective_from", nullable = false, updatable = false)
	private Instant effectiveFrom;

	@Column(name = "effective_to")
	private Instant effectiveTo;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 100)
	private Status status;

	// v06에서 configured_by → created_by로 이름이 바뀌고 updated_by/updated_at이 추가됐다.
	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** 이 버전을 종료·대체 처리한 사용자. 정책값 자체를 덮어쓰는 용도가 아니다. */
	@Column(name = "updated_by")
	private UUID updatedBy;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** 플랫폼 공통 과금 통화. DB CHECK(currency_code = 'USD')와 동일하며 기관별로 바꿀 수 없다. */
	public static final String PLATFORM_CURRENCY_CODE = "USD";

	private OrganizationPolicy(UUID orgId, int policyVersion, Settings settings, UUID createdBy) {
		this.orgId = orgId;
		this.policyVersion = policyVersion;
		this.currencyCode = PLATFORM_CURRENCY_CODE;
		this.effectiveFrom = Instant.now();
		this.status = Status.ACTIVE;
		this.createdBy = createdBy;
		apply(settings);
	}

	private void apply(Settings settings) {
		this.monthlyAiBudget = settings.monthlyAiBudget();
		this.monthlyTokenLimit = settings.monthlyTokenLimit();
		this.storageLimitBytes = settings.storageLimitBytes();
		this.retentionDays = settings.retentionDays();
		this.defaultDisclosureScope = settings.defaultDisclosureScope();
		this.codeSessionTierCode = settings.codeSessionTierCode();
		this.allowManagerInvite = settings.allowManagerInvite();
		this.allowDataExport = settings.allowDataExport();
		this.allowZipSubmission = settings.allowZipSubmission();
		this.allowGithubIntegration = settings.allowGithubIntegration();
		this.enableBigProjectContributionAnalysis = settings.enableBigProjectContributionAnalysis();
	}

	/** 기관 생성 시 발급되는 최초(버전 1) 정책. */
	public static OrganizationPolicy createInitial(UUID orgId, Settings settings, UUID createdBy) {
		return new OrganizationPolicy(orgId, 1, settings, createdBy);
	}

	/**
	 * 기존 활성 정책의 다음 버전을 발급한다(operations 도메인의 운영 설정 변경에서 사용).
	 * 통화는 플랫폼 공통 고정이라 변경 대상이 아니다.
	 */
	public static OrganizationPolicy createNextVersion(OrganizationPolicy previous, Settings settings, UUID createdBy) {
		return new OrganizationPolicy(previous.orgId, previous.policyVersion + 1, settings, createdBy);
	}

	/** 이 정책 버전의 현재 값들을 Settings로 꺼낸다. 부분 수정 요청을 병합할 때의 기준값으로 쓴다. */
	public Settings toSettings() {
		return new Settings(
				monthlyAiBudget,
				monthlyTokenLimit,
				storageLimitBytes,
				retentionDays,
				defaultDisclosureScope,
				codeSessionTierCode,
				allowManagerInvite,
				allowDataExport,
				allowZipSubmission,
				allowGithubIntegration,
				enableBigProjectContributionAnalysis
		);
	}

	/** 새 버전이 발급될 때 이 버전을 과거 이력으로 전환한다. */
	public void supersede(UUID updatedBy) {
		this.status = Status.SUPERSEDED;
		this.effectiveTo = Instant.now();
		this.updatedBy = updatedBy;
	}

	/**
	 * 정책 버전이 담는 "변경 가능한 값"의 묶음. 버전형 테이블이라 부분 수정이 없고 항상 전체를 실어 새 버전을 만든다.
	 * 파라미터가 11개라 메서드 인자로 늘어놓지 않고 한 덩어리로 받는다.
	 */
	public record Settings(
			BigDecimal monthlyAiBudget,
			Long monthlyTokenLimit,
			Long storageLimitBytes,
			Integer retentionDays,
			DisclosureScope defaultDisclosureScope,
			AiTier codeSessionTierCode,
			Boolean allowManagerInvite,
			Boolean allowDataExport,
			Boolean allowZipSubmission,
			Boolean allowGithubIntegration,
			Boolean enableBigProjectContributionAnalysis
	) {
	}

	// DB CHECK: status IN ('ACTIVE','SUPERSEDED','EXPIRED'). 별도 공용 enum 파일 없이 정책 엔티티에 종속시켜 정의한다.
	public enum Status {
		ACTIVE, SUPERSEDED, EXPIRED
	}
}
