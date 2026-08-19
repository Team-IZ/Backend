package com.bigproject.backend.domain.organization.application;

import com.bigproject.backend.domain.platformgovernance.domain.AiTier;
import com.bigproject.backend.domain.organization.domain.DisclosureScope;
import com.bigproject.backend.domain.usagemetering.infrastructure.AiUsageRepository;
import com.bigproject.backend.domain.usagemetering.infrastructure.OrgAiCostTotal;
import com.bigproject.backend.domain.organization.domain.Organization;
import com.bigproject.backend.domain.organization.domain.OrganizationErrorCode;
import com.bigproject.backend.domain.organization.domain.OrganizationException;
import com.bigproject.backend.domain.organization.domain.OrganizationPolicy;
import com.bigproject.backend.domain.organization.domain.OrganizationStatsRepository;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationPolicyRepository;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationRepository;
import com.bigproject.backend.domain.organization.presentation.dto.CreateOrganizationRequest;
import com.bigproject.backend.domain.organization.presentation.dto.DeleteOrganizationRequest;
import com.bigproject.backend.domain.organization.presentation.dto.DeleteOrganizationResponse;
import com.bigproject.backend.domain.organization.presentation.dto.PurgeOrganizationResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationCohortListResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationListResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationNameAvailabilityResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationResponse;
import com.bigproject.backend.domain.organization.presentation.dto.PlatformSummaryResponse;
import com.bigproject.backend.domain.organization.presentation.dto.UpdateOrganizationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본은 조회 트랜잭션. 쓰기가 필요한 메서드에만 @Transactional을 개별로 얹는다.
public class OrganizationServiceImpl implements OrganizationService {

	// 기관 생성 시 발급하는 최초 정책의 기본값. 실제 기획 값이 확정되면 설정(application.yaml) 등으로 분리하는 것이 바람직하다.
	private static final BigDecimal DEFAULT_MONTHLY_AI_BUDGET = BigDecimal.ZERO;
	/**
	 * v2 IA 목업(SA-01/SA-02, OP-06 ⑤)은 비용을 전부 `$`로 표기하고,
	 * v06 DDL도 CHECK(currency_code = 'USD')로 플랫폼 공통 통화를 USD로 고정했다.
	 */
	private static final String DEFAULT_CURRENCY_CODE = OrganizationPolicy.PLATFORM_CURRENCY_CODE;
	private static final DisclosureScope DEFAULT_DISCLOSURE_SCOPE = DisclosureScope.SUMMARY;
	private static final int RATE_SCALE = 4;

	/*
	 * 기관 생성 시 최초 정책에 넣는 기본값. DDL의 컬럼 DEFAULT와 같은 값으로 맞춰 둔다
	 * (엔티티가 값을 명시해 INSERT하므로 DB DEFAULT가 적용되지 않는다 — 여기서 어긋나면 화면 기본값이 달라진다).
	 */
	private static final AiTier DEFAULT_AI_TIER = AiTier.BALANCED;
	private static final boolean DEFAULT_ALLOW_MANAGER_INVITE = true;
	private static final boolean DEFAULT_ALLOW_DATA_EXPORT = true;
	private static final boolean DEFAULT_ALLOW_ZIP_SUBMISSION = true;
	private static final boolean DEFAULT_ALLOW_GITHUB_INTEGRATION = true;
	private static final boolean DEFAULT_ENABLE_BIG_PROJECT_CONTRIBUTION_ANALYSIS = true;

	private final OrganizationRepository organizationRepository;
	private final OrganizationPolicyRepository organizationPolicyRepository;
	private final OrganizationStatsRepository organizationStatsRepository;
	private final AiUsageRepository aiUsageRepository;

	@Override
	public OrganizationListResponse findOrganizations(
			String query,
			OrganizationStatus status,
			OrganizationSort sort,
			int page,
			int size
	) {
		String normalizedQuery = (query == null || query.isBlank()) ? null : normalize(query);
		String likePattern = normalizedQuery == null ? null : "%" + normalizedQuery + "%";
		Page<Organization> result = organizationRepository.search(
				likePattern, status, PageRequest.of(page, size, OrganizationSort.orDefault(sort).toSort())
		);

		List<Organization> organizations = result.getContent();
		Map<UUID, OrganizationPolicy> activePolicyByOrgId = activePolicyByOrgId(organizations);

		// 목록에 담긴 기관 ID를 한 번에 모아서 배치 조회한다(기관 수만큼 매번 따로 조회하는 N+1을 피하기 위함).
		List<UUID> orgIds = organizations.stream().map(Organization::getOrgId).toList();
		OrgAggregates aggregates = loadAggregates(orgIds);

		List<OrganizationResponse> content = organizations.stream()
				.map(organization -> toResponse(organization, activePolicyByOrgId.get(organization.getOrgId()), aggregates))
				.toList();

		return new OrganizationListResponse(
				content,
				result.getNumber(),
				result.getSize(),
				result.getTotalElements(),
				result.getTotalPages()
		);
	}

	@Override
	public PlatformSummaryResponse findPlatformSummary() {
		YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
		List<UUID> orgIds = organizationRepository.findAliveOrganizationIds();

		OrganizationStatsRepository.PlatformOrganizationCounts counts = organizationStatsRepository.countOrganizationsByStatus();

		BigDecimal currentCost = sumAiCost(orgIds, currentMonth);
		BigDecimal previousCost = sumAiCost(orgIds, currentMonth.minusMonths(1));
		BigDecimal totalBudget = organizationPolicyRepository
				.findByOrgIdInAndStatus(orgIds, OrganizationPolicy.Status.ACTIVE).stream()
				.map(OrganizationPolicy::getMonthlyAiBudget)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		long currentStorage = sumStorageBytes(orgIds, currentMonth);
		long previousStorage = sumStorageBytes(orgIds, currentMonth.minusMonths(1));
		long averageStorage = orgIds.isEmpty() ? 0L : currentStorage / orgIds.size();

		return new PlatformSummaryResponse(
				currentMonth,
				new PlatformSummaryResponse.Organizations(counts.total(), counts.active(), counts.suspended()),
				organizationStatsRepository.countAllActiveTrainees(),
				organizationStatsRepository.countAllActiveSessions(),
				new PlatformSummaryResponse.AiCost(
						currentCost,
						totalBudget,
						usageRate(currentCost, totalBudget),
						changeRate(currentCost, previousCost),
						DEFAULT_CURRENCY_CODE
				),
				new PlatformSummaryResponse.Storage(
						currentStorage,
						changeRate(BigDecimal.valueOf(currentStorage), BigDecimal.valueOf(previousStorage)),
						averageStorage
				)
		);
	}

	@Override
	public OrganizationNameAvailabilityResponse checkNameAvailability(String name) {
		String normalizedName = normalize(name);
		// v06: normalized_name이 전체 UNIQUE라 soft-delete된 기관의 이름도 선점 상태다(물리 파기 전에는 재사용 불가).
		return organizationRepository.existsByNormalizedName(normalizedName)
				? OrganizationNameAvailabilityResponse.taken(name, normalizedName)
				: OrganizationNameAvailabilityResponse.available(name, normalizedName);
	}

	@Override
	@Transactional
	public OrganizationResponse createOrganization(CreateOrganizationRequest request, UUID requesterId, UUID idempotencyKey) {
		String fingerprint = requestFingerprint(request);

		/*
		 * 생성 멱등성(목업 SA-01 "생성 실패는 아무것도 남기지 않는다").
		 * 같은 키 + 같은 내용이면 최초 생성 결과를 그대로 돌려주고, 같은 키 + 다른 내용이면 거절한다.
		 * 네트워크 재시도나 더블 클릭이 기관을 두 개 만들지 않도록 하는 장치다.
		 */
		if (idempotencyKey != null) {
			Optional<Organization> replayed = organizationRepository.findByCreateIdempotencyKey(idempotencyKey);
			if (replayed.isPresent()) {
				Organization existing = replayed.get();
				if (!fingerprint.equals(existing.getCreateRequestFingerprint())) {
					throw new OrganizationException(OrganizationErrorCode.ORG_IDEMPOTENCY_CONFLICT);
				}
				return toResponse(
						existing,
						findActivePolicy(existing.getOrgId()),
						loadAggregates(List.of(existing.getOrgId()))
				);
			}
		}

		String normalizedName = normalize(request.name());
		if (organizationRepository.existsByNormalizedName(normalizedName)) {
			throw new OrganizationException(OrganizationErrorCode.ORG_NAME_TAKEN);
		}

		Organization organization = Organization.create(request.name(), normalizedName, requesterId);
		// v06에서 컬럼이 생겨 슬러그·표시 코드·이메일 도메인이 실제로 저장된다(이전에는 요청값을 버리고 있었다).
		organization.applyProfile(request.slug(), request.displayCode(), request.emailDomain());
		organization.applyCreateIdempotency(idempotencyKey, fingerprint);
		// save()만 호출하면 실제 INSERT가 트랜잭션 커밋 시점까지 미뤄질 수 있어, @CreationTimestamp로 채워지는
		// createdAt이 이 메서드 안에서는 아직 null이다. saveAndFlush로 즉시 INSERT를 실행해 createdAt을 확정한 뒤 응답을 만든다.
		organizationRepository.saveAndFlush(organization);

		OrganizationPolicy policy = OrganizationPolicy.createInitial(
				organization.getOrgId(),
				initialSettings(request.dataRetentionDays()),
				requesterId
		);
		organizationPolicyRepository.save(policy);

		// 방금 생성한 기관이라 기수/오퍼레이터/교육생/AI 비용이 존재할 수 없으므로 조회 없이 비워둔다.
		// 목업대로 오퍼레이터가 0명이므로 operatorUnassigned=true("활성 · 오퍼레이터 미배정")로 응답한다.
		return toResponse(organization, policy, OrgAggregates.empty());
	}

	@Override
	public OrganizationResponse findOrganization(UUID organizationId) {
		Organization organization = getOrganizationOrThrow(organizationId);
		// 활성 정책이 없어도 조회는 성공해야 한다. 목록 조회(findOrganizations)가 이미 정책 없는 기관을 허용하고 있어서,
		// 여기서만 예외를 던지면 같은 기관이 목록에는 보이는데 상세에서 500이 나는 불일치가 생긴다.
		// 정책 없는 기관은 예산·보존기간·공개범위가 비어 있는 상태로 응답한다(toResponse가 null 정책을 처리한다).
		return toResponse(organization, findActivePolicy(organizationId), loadAggregates(List.of(organizationId)));
	}

	@Override
	public OrganizationCohortListResponse findOrganizationCohorts(UUID organizationId) {
		assertOrganizationExists(organizationId);

		List<OrganizationCohortListResponse.Cohort> content = organizationStatsRepository
				.findCohortSummaries(organizationId).stream()
				.map(cohort -> new OrganizationCohortListResponse.Cohort(
						cohort.cohortId(),
						cohort.name(),
						cohort.status(),
						cohort.classCount(),
						cohort.traineeCount(),
						cohort.startDate(),
						cohort.endDate()
				))
				.toList();

		return new OrganizationCohortListResponse(organizationId, content);
	}

	@Override
	@Transactional
	public OrganizationResponse updateOrganization(UUID organizationId, UpdateOrganizationRequest request, UUID requesterId) {
		Organization organization = getOrganizationOrThrow(organizationId);

		// soft-delete된 기관을 changeStatus(ACTIVE/SUSPENDED)로 되돌리면 DB CHECK(ck_organization_status_timeline)를 위반한다:
		// ACTIVE/SUSPENDED는 deletion_requested_at·deleted_at이 NULL이어야 하는데 삭제된 기관은 이미 값이 채워져 있다.
		if (organization.getStatus() == OrganizationStatus.DELETED) {
			throw new OrganizationException(
					OrganizationErrorCode.ORG_ALREADY_DELETED, "삭제된 기관은 상태를 변경할 수 없습니다."
			);
		}

		// organization_policy와 동일하게 ACTIVE/SUSPENDED만 직접 지정 가능한 값이다.
		if (request.status() != OrganizationStatus.ACTIVE && request.status() != OrganizationStatus.SUSPENDED) {
			throw new OrganizationException(OrganizationErrorCode.ORG_STATUS_NOT_MUTABLE);
		}

		if (request.name() != null && !request.name().isBlank()) {
			String normalizedName = normalize(request.name());
			// v06: 전체 UNIQUE라 자기 자신만 제외하고 검사한다(삭제된 기관 이름도 선점 상태).
			if (organizationRepository.existsByNormalizedNameAndOrgIdNot(normalizedName, organizationId)) {
				throw new OrganizationException(OrganizationErrorCode.ORG_NAME_TAKEN);
			}
			organization.rename(request.name(), normalizedName);
		}

		organization.changeStatus(request.status());
		organization.touchUpdatedBy(requesterId);

		// 이 메서드는 정책을 읽지도 쓰지도 않는다(이름·상태만 바꾼다). 정책은 응답을 채우는 데만 쓰이므로
		// 활성 정책이 없어도 변경 자체는 성공시킨다.
		return toResponse(organization, findActivePolicy(organizationId), loadAggregates(List.of(organizationId)));
	}

	@Override
	@Transactional
	public DeleteOrganizationResponse deleteOrganization(
			UUID organizationId,
			DeleteOrganizationRequest request,
			UUID requesterId,
			UUID idempotencyKey
	) {
		Organization organization = getOrganizationOrThrow(organizationId);

		// 삭제 멱등성: 같은 키로 다시 들어오면 최초 삭제 결과를 그대로 돌려준다(중복 삭제로 보존기간이 밀리지 않게).
		String fingerprint = deletionFingerprint(organizationId, request);
		if (idempotencyKey != null) {
			Optional<Organization> replayed = organizationRepository.findByDeletionIdempotencyKey(idempotencyKey);
			if (replayed.isPresent()) {
				Organization existing = replayed.get();
				if (!fingerprint.equals(existing.getDeletionRequestFingerprint())) {
					throw new OrganizationException(OrganizationErrorCode.ORG_IDEMPOTENCY_CONFLICT);
				}
				return new DeleteOrganizationResponse(
						existing.getOrgId(), existing.getDeletedAt(), existing.getRetentionUntil()
				);
			}
		}

		// 이미 삭제된 기관에 softDelete를 다시 적용하면 retentionUntil이 현재 시각 기준으로 다시 늘어나 버린다.
		if (organization.getStatus() == OrganizationStatus.DELETED) {
			throw new OrganizationException(OrganizationErrorCode.ORG_ALREADY_DELETED);
		}

		// 목업 case 7: 확인 모달에서 기관명을 직접 입력해야 진행된다.
		// 소속 전원이 못 들어오게 되는 액션이라 버튼 한 번으로 끝나면 안 된다.
		// 비교는 저장된 표시명 기준으로 하되, 앞뒤 공백만 관대하게 처리한다.
		if (!organization.getName().equals(request.confirmName().trim())) {
			throw new OrganizationException(OrganizationErrorCode.ORG_DELETE_CONFIRM_MISMATCH);
		}

		int retentionDays = getActivePolicyOrThrow(organizationId).getRetentionDays();

		// v06 CHECK(ck_organization_deletion_required)가 삭제 요청자를 필수로 요구한다 — 요청자를 함께 넘긴다.
		organization.softDelete(retentionDays, requesterId, idempotencyKey, fingerprint);

		return new DeleteOrganizationResponse(organization.getOrgId(), organization.getDeletedAt(), organization.getRetentionUntil());
	}

	@Override
	@Transactional
	public OrganizationResponse restoreOrganization(UUID organizationId, UUID requesterId) {
		Organization organization = getOrganizationOrThrow(organizationId);

		if (organization.getStatus() != OrganizationStatus.DELETED) {
			throw new OrganizationException(OrganizationErrorCode.ORG_NOT_DELETED);
		}

		// 보존기간이 이미 지났으면 파기 대상이라 복구를 허용하지 않는다
		// (목업: "보존기간이 지난 뒤 파기됩니다. 그전까지는 복구할 수 있습니다").
		if (organization.isPurgeAllowed(Instant.now())) {
			throw new OrganizationException(
					OrganizationErrorCode.ORG_ALREADY_DELETED,
					"보존기간이 지나 파기 대상이므로 복구할 수 없습니다."
			);
		}

		/*
		 * v06에서 normalized_name이 전체 UNIQUE가 되면서, 삭제된 기관의 이름도 파기 전까지 선점 상태로 남는다.
		 * 즉 삭제된 동안 같은 이름의 기관이 새로 생길 수 없으므로 복구 시 이름 충돌 검사는 더 이상 필요하지 않다
		 * (이전 부분 유니크 인덱스에서는 삭제된 이름을 재사용할 수 있어 복구 전에 막아야 했다).
		 */
		organization.restore(requesterId);

		return toResponse(organization, findActivePolicy(organizationId), loadAggregates(List.of(organizationId)));
	}

	@Override
	@Transactional
	public PurgeOrganizationResponse purgeOrganization(UUID organizationId) {
		Organization organization = getOrganizationOrThrow(organizationId);

		if (organization.getStatus() != OrganizationStatus.DELETED) {
			throw new OrganizationException(
					OrganizationErrorCode.ORG_NOT_DELETED, "삭제되지 않은 기관은 파기할 수 없습니다."
			);
		}

		// 목업 case 8: 보존기간이 남아 있으면 파기할 수 없다고 막는다.
		if (!organization.isPurgeAllowed(Instant.now())) {
			throw new OrganizationException(
					OrganizationErrorCode.RETENTION_NOT_MET,
					"보존기간이 남아 파기할 수 없습니다. 파기 가능 시각: " + organization.getRetentionUntil()
			);
		}

		// 파기 파이프라인 상태를 IN_PROGRESS로 올려 배치가 집어갈 대상임을 남긴다(v06 organization.purge_status).
		organization.markPurgeInProgress();

		// TODO(purge-batch): 보존기간 검증과 상태 전이까지만 구현돼 있다. 실제 파기는 cohort/app_user/ai_usage 등
		//  테넌트 데이터 전체를 지우는 작업이라 한 트랜잭션에서 처리할 수 없고(FK RESTRICT), 별도 배치가 담당해야 한다.
		//  배치가 붙기 전까지 purged=false로 접수만 응답한다.
		return new PurgeOrganizationResponse(
				organization.getOrgId(),
				organization.getDeletedAt(),
				organization.getRetentionUntil(),
				Instant.now(),
				false,
				"보존기간이 지나 파기할 수 있는 상태입니다. 실제 데이터 파기 배치는 아직 연결되지 않았습니다."
		);
	}

	private void assertOrganizationExists(UUID organizationId) {
		if (!organizationRepository.existsById(organizationId)) {
			throw new OrganizationException(
					OrganizationErrorCode.ORG_NOT_FOUND, "기관을 찾을 수 없습니다: " + organizationId
			);
		}
	}

	private Organization getOrganizationOrThrow(UUID organizationId) {
		return organizationRepository.findById(organizationId)
				.orElseThrow(() -> new OrganizationException(
						OrganizationErrorCode.ORG_NOT_FOUND, "기관을 찾을 수 없습니다: " + organizationId
				));
	}

	/** 활성 정책을 찾되, 없으면 null을 반환한다. 정책이 응답을 채우는 데만 쓰이는 조회 경로에서 사용한다. */
	private OrganizationPolicy findActivePolicy(UUID organizationId) {
		return organizationPolicyRepository
				.findByOrgIdAndStatus(organizationId, OrganizationPolicy.Status.ACTIVE)
				.orElse(null);
	}

	/**
	 * 활성 정책이 반드시 필요한 경로(보존기간 계산 등)에서 사용한다.
	 * 정책이 없는 것은 서버 결함이 아니라 데이터 상태이므로 500이 아니라 409로 알린다.
	 */
	private OrganizationPolicy getActivePolicyOrThrow(UUID organizationId) {
		return organizationPolicyRepository.findByOrgIdAndStatus(organizationId, OrganizationPolicy.Status.ACTIVE)
				.orElseThrow(() -> new OrganizationException(
						OrganizationErrorCode.ORG_POLICY_NOT_FOUND,
						"기관에 활성 운영 정책이 없어 이 작업을 수행할 수 없습니다. 운영 설정을 먼저 등록해 주세요: " + organizationId
				));
	}

	private Map<UUID, OrganizationPolicy> activePolicyByOrgId(List<Organization> organizations) {
		List<UUID> orgIds = organizations.stream().map(Organization::getOrgId).toList();
		return organizationPolicyRepository.findByOrgIdInAndStatus(orgIds, OrganizationPolicy.Status.ACTIVE).stream()
				.collect(Collectors.toMap(OrganizationPolicy::getOrgId, policy -> policy));
	}

	/** 목록/단건에 공통으로 쓰이는 기관별 집계를 한 번에 배치 조회한다. */
	private OrgAggregates loadAggregates(List<UUID> orgIds) {
		if (orgIds.isEmpty()) {
			return OrgAggregates.empty();
		}
		return new OrgAggregates(
				organizationStatsRepository.countCohortsByOrgId(orgIds),
				organizationStatsRepository.findOperatorsByOrgId(orgIds),
				organizationStatsRepository.countActiveTraineesByOrgId(orgIds),
				organizationStatsRepository.countActiveSessionsByOrgId(orgIds),
				currentMonthAiCostByOrgId(orgIds)
		);
	}

	// 이름 검색/중복확인에 사용하는 정규화 규칙(트림 + 소문자). organization.normalized_name 컬럼과 동일한 규칙을 적용한다.
	private String normalize(String name) {
		return name.trim().toLowerCase(Locale.ROOT);
	}

	/** 기관 생성 시 발급하는 최초 정책 값. DDL의 컬럼 DEFAULT와 같은 값으로 맞춰 둔다. */
	private OrganizationPolicy.Settings initialSettings(int dataRetentionDays) {
		return new OrganizationPolicy.Settings(
				DEFAULT_MONTHLY_AI_BUDGET,
				null,   // monthlyTokenLimit: 무제한
				null,   // storageLimitBytes: 무제한
				dataRetentionDays,
				DEFAULT_DISCLOSURE_SCOPE,
				DEFAULT_AI_TIER,
				DEFAULT_ALLOW_MANAGER_INVITE,
				DEFAULT_ALLOW_DATA_EXPORT,
				DEFAULT_ALLOW_ZIP_SUBMISSION,
				DEFAULT_ALLOW_GITHUB_INTEGRATION,
				DEFAULT_ENABLE_BIG_PROJECT_CONTRIBUTION_ANALYSIS
		);
	}

	/**
	 * 생성 요청의 지문. 같은 멱등성 키로 들어온 요청이 <b>같은 내용</b>인지 판정하는 데만 쓴다.
	 * 정규화한 값들을 이어 붙여 SHA-256으로 줄인다(DB CHECK가 소문자 16진수 64자를 요구한다).
	 */
	private String requestFingerprint(CreateOrganizationRequest request) {
		return sha256Hex(String.join("",
				normalize(request.name()),
				emptyIfNull(request.slug()),
				emptyIfNull(request.displayCode()),
				emptyIfNull(request.emailDomain()),
				String.valueOf(request.dataRetentionDays())
		));
	}

	/** 삭제 요청의 지문. 대상 기관과 확인 입력값을 함께 담는다. */
	private String deletionFingerprint(UUID organizationId, DeleteOrganizationRequest request) {
		return sha256Hex(organizationId + "" + emptyIfNull(request.confirmName()).trim());
	}

	private String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			// SHA-256은 모든 JVM이 제공해야 하는 알고리즘이라 실제로는 도달하지 않는다.
			throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
		}
	}

	private String emptyIfNull(String value) {
		return value == null ? "" : value;
	}

	// 이번 달(UTC 기준) 1일 00:00 ~ 다음 달 1일 00:00 직전까지의 AI 비용 합계를 기관별로 조회한다.
	// operations.findUsage()가 특정 월(period)을 파라미터로 받는 것과 달리, 여기서는 항상 "이번 달"만 본다.
	private Map<UUID, BigDecimal> currentMonthAiCostByOrgId(Collection<UUID> orgIds) {
		if (orgIds.isEmpty()) {
			return Map.of();
		}
		YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
		return aiUsageRepository.sumPricedCostByOrgId(orgIds, startOf(currentMonth), startOf(currentMonth.plusMonths(1))).stream()
				.collect(Collectors.toMap(OrgAiCostTotal::orgId, OrgAiCostTotal::totalCost));
	}

	private BigDecimal sumAiCost(Collection<UUID> orgIds, YearMonth period) {
		if (orgIds.isEmpty()) {
			return BigDecimal.ZERO;
		}
		return aiUsageRepository.sumPricedCostByOrgId(orgIds, startOf(period), startOf(period.plusMonths(1))).stream()
				.map(OrgAiCostTotal::totalCost)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private long sumStorageBytes(Collection<UUID> orgIds, YearMonth period) {
		if (orgIds.isEmpty()) {
			return 0L;
		}
		return organizationStatsRepository
				.sumLatestStorageBytesByOrgId(orgIds, startOf(period), startOf(period.plusMonths(1)))
				.values().stream()
				.mapToLong(Long::longValue)
				.sum();
	}

	private Instant startOf(YearMonth period) {
		return period.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
	}

	/** 예산 소진율(0~1). 예산이 0 이하면 나눌 수 없어 null을 반환한다. */
	private BigDecimal usageRate(BigDecimal cost, BigDecimal budget) {
		if (budget == null || budget.compareTo(BigDecimal.ZERO) <= 0) {
			return null;
		}
		return cost.divide(budget, RATE_SCALE, RoundingMode.HALF_UP);
	}

	/** 전월 대비 증감률. 전월 값이 0이면 증감률이 정의되지 않으므로 null을 반환한다(프론트에서는 `—`로 표기). */
	private BigDecimal changeRate(BigDecimal current, BigDecimal previous) {
		if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
			return null;
		}
		return current.subtract(previous).divide(previous, RATE_SCALE, RoundingMode.HALF_UP);
	}

	private OrganizationResponse toResponse(Organization organization, OrganizationPolicy policy, OrgAggregates aggregates) {
		UUID orgId = organization.getOrgId();

		List<OrganizationStatsRepository.OrganizationOperator> operators =
				aggregates.operators().getOrDefault(orgId, List.of());
		OrganizationStatsRepository.CohortCounts cohorts =
				aggregates.cohorts().getOrDefault(orgId, OrganizationStatsRepository.CohortCounts.empty());
		BigDecimal aiCost = aggregates.aiCosts().getOrDefault(orgId, BigDecimal.ZERO);
		BigDecimal budget = policy == null ? BigDecimal.ZERO : policy.getMonthlyAiBudget();

		return new OrganizationResponse(
				orgId,
				// v06에서 컬럼이 생겨 실제 저장 값으로 응답한다(이전에는 OrganizationSchemaPending으로 null 고정).
				organization.getSlug(),
				organization.getDisplayCode(),
				organization.getName(),
				organization.getEmailDomain(),
				organization.getStatus(),
				// 목업 SA-01/SA-02의 `오퍼레이터 미배정` 배지. 저장 상태가 아니라 오퍼레이터 0명에서 파생시킨다.
				operators.isEmpty(),
				// 목업의 `예산 초과` 배지. 예산이 0이면(미설정) 초과 판정을 하지 않는다.
				budget.compareTo(BigDecimal.ZERO) > 0 && aiCost.compareTo(budget) > 0,
				operators.stream()
						.map(operator -> new OrganizationResponse.Operator(operator.memberId(), operator.name(), operator.email()))
						.toList(),
				new OrganizationResponse.CohortCounts(cohorts.total(), cohorts.running(), cohorts.closed()),
				aggregates.trainees().getOrDefault(orgId, 0),
				// v07에서 assessment_session이 생겨 실제 집계로 대체됐다(이전에는 OrganizationSchemaPending으로 0 고정).
				aggregates.activeSessions().getOrDefault(orgId, 0),
				aiCost,
				budget,
				usageRate(aiCost, budget),
				policy == null ? null : policy.getCurrencyCode(),
				policy == null ? 0 : policy.getRetentionDays(),
				policy == null ? null : policy.getDefaultDisclosureScope(),
				organization.getCreatedAt(),
				organization.getDeletedAt()
		);
	}

	/** 기관별 집계 묶음. 목록은 배치 조회 결과를, 단건/생성은 1건짜리 또는 빈 맵을 담는다. */
	private record OrgAggregates(
			Map<UUID, OrganizationStatsRepository.CohortCounts> cohorts,
			Map<UUID, List<OrganizationStatsRepository.OrganizationOperator>> operators,
			Map<UUID, Integer> trainees,
			Map<UUID, Integer> activeSessions,
			Map<UUID, BigDecimal> aiCosts
	) {
		static OrgAggregates empty() {
			return new OrgAggregates(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
		}
	}
}
