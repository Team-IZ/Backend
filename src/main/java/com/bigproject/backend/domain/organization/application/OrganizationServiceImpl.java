package com.bigproject.backend.domain.organization.application;

import com.bigproject.backend.domain.operations.domain.DisclosureScope;
import com.bigproject.backend.domain.operations.infrastructure.AiUsageRepository;
import com.bigproject.backend.domain.operations.infrastructure.OrgAiCostTotal;
import com.bigproject.backend.domain.organization.domain.Organization;
import com.bigproject.backend.domain.organization.domain.OrganizationPolicy;
import com.bigproject.backend.domain.organization.domain.OrganizationStatsRepository;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationPolicyRepository;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationRepository;
import com.bigproject.backend.domain.organization.presentation.dto.CreateOrganizationRequest;
import com.bigproject.backend.domain.organization.presentation.dto.DeleteOrganizationResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationListResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationResponse;
import com.bigproject.backend.domain.organization.presentation.dto.UpdateOrganizationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본은 조회 트랜잭션. 쓰기가 필요한 메서드에만 @Transactional을 개별로 얹는다.
public class OrganizationServiceImpl implements OrganizationService {

	// 기관 생성 시 발급하는 최초 정책의 기본값. 실제 기획 값이 확정되면 설정(application.yaml) 등으로 분리하는 것이 바람직하다.
	private static final BigDecimal DEFAULT_MONTHLY_AI_BUDGET = BigDecimal.ZERO;
	private static final String DEFAULT_CURRENCY_CODE = "KRW";
	private static final DisclosureScope DEFAULT_DISCLOSURE_SCOPE = DisclosureScope.SUMMARY;

	private final OrganizationRepository organizationRepository;
	private final OrganizationPolicyRepository organizationPolicyRepository;
	private final OrganizationStatsRepository organizationStatsRepository;
	private final AiUsageRepository aiUsageRepository;

	@Override
	public OrganizationListResponse findOrganizations(String query, OrganizationStatus status, int page, int size) {
		String normalizedQuery = (query == null || query.isBlank()) ? null : normalize(query);
		String likePattern = normalizedQuery == null ? null : "%" + normalizedQuery + "%";
		Page<Organization> result = organizationRepository.search(likePattern, status, PageRequest.of(page, size));

		List<Organization> organizations = result.getContent();
		Map<UUID, OrganizationPolicy> activePolicyByOrgId = activePolicyByOrgId(organizations);

		// 목록의 기관 ID를 한 번에 모아서 배치 조회한다(기관 수만큼 매번 따로 조회하는 N+1을 피하기 위함).
		List<UUID> orgIds = organizations.stream().map(Organization::getOrgId).toList();
		Map<UUID, Integer> cohortCountByOrgId = organizationStatsRepository.countActiveCohortsByOrgId(orgIds);
		Map<UUID, Integer> managerCountByOrgId = organizationStatsRepository.countActiveManagersByOrgId(orgIds);
		Map<UUID, Integer> traineeCountByOrgId = organizationStatsRepository.countActiveTraineesByOrgId(orgIds);
		Map<UUID, BigDecimal> aiCostByOrgId = currentMonthAiCostByOrgId(orgIds);

		List<OrganizationResponse> content = organizations.stream()
				.map(organization -> {
					OrganizationPolicy policy = activePolicyByOrgId.get(organization.getOrgId());
					return toResponse(
							organization,
							policy == null ? 0 : policy.getRetentionDays(),
							policy == null ? null : policy.getDefaultDisclosureScope(),
							cohortCountByOrgId.getOrDefault(organization.getOrgId(), 0),
							managerCountByOrgId.getOrDefault(organization.getOrgId(), 0),
							traineeCountByOrgId.getOrDefault(organization.getOrgId(), 0),
							aiCostByOrgId.getOrDefault(organization.getOrgId(), BigDecimal.ZERO)
					);
				})
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
	@Transactional
	public OrganizationResponse createOrganization(CreateOrganizationRequest request, UUID requesterId) {
		String normalizedName = normalize(request.name());
		if (organizationRepository.existsByNormalizedNameAndDeletedAtIsNull(normalizedName)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 기관명입니다.");
		}

		Organization organization = Organization.create(request.name(), normalizedName, requesterId);
		// save()만 호출하면 실제 INSERT가 트랜잭션 커밋 시점까지 미뤄질 수 있어, @CreationTimestamp로 채워지는
		// createdAt이 이 메서드 안에서는 아직 null이다(우리 PK는 UUID를 애플리케이션에서 미리 만들어서 Hibernate가
		// ID 확보를 위해 flush를 서두를 필요가 없기 때문). saveAndFlush로 즉시 INSERT를 실행해 createdAt을 확정한 뒤
		// 응답을 만든다.
		organizationRepository.saveAndFlush(organization);

		OrganizationPolicy policy = OrganizationPolicy.createInitial(
				organization.getOrgId(),
				DEFAULT_MONTHLY_AI_BUDGET,
				DEFAULT_CURRENCY_CODE,
				request.dataRetentionDays(),
				DEFAULT_DISCLOSURE_SCOPE,
				requesterId
		);
		organizationPolicyRepository.save(policy);

		// 방금 생성한 기관이라 기수/매니저/교육생/AI 비용이 존재할 수 없으므로 조회 없이 0으로 채운다.
		return toResponse(organization, policy.getRetentionDays(), policy.getDefaultDisclosureScope(), 0, 0, 0, BigDecimal.ZERO);
	}

	@Override
	public OrganizationResponse findOrganization(UUID organizationId) {
		Organization organization = getOrganizationOrThrow(organizationId);
		OrganizationPolicy policy = getActivePolicyOrThrow(organizationId);

		List<UUID> singleOrgId = List.of(organizationId);
		int cohortCount = organizationStatsRepository.countActiveCohortsByOrgId(singleOrgId).getOrDefault(organizationId, 0);
		int managerCount = organizationStatsRepository.countActiveManagersByOrgId(singleOrgId).getOrDefault(organizationId, 0);
		int traineeCount = organizationStatsRepository.countActiveTraineesByOrgId(singleOrgId).getOrDefault(organizationId, 0);
		BigDecimal aiCost = currentMonthAiCostByOrgId(singleOrgId).getOrDefault(organizationId, BigDecimal.ZERO);

		return toResponse(organization, policy.getRetentionDays(), policy.getDefaultDisclosureScope(), cohortCount, managerCount, traineeCount, aiCost);
	}

	@Override
	@Transactional
	public OrganizationResponse updateOrganization(UUID organizationId, UpdateOrganizationRequest request, UUID requesterId) {
		Organization organization = getOrganizationOrThrow(organizationId);

		// soft-delete된 기관을 changeStatus(ACTIVE/SUSPENDED)로 되돌리면 DB CHECK(ck_organization_status_2)를 위반한다:
		// ACTIVE/SUSPENDED는 deletion_requested_at·deleted_at이 NULL이어야 하는데 삭제된 기관은 이미 값이 채워져 있다.
		if (organization.getStatus() == OrganizationStatus.DELETED) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "삭제된 기관은 상태를 변경할 수 없습니다.");
		}

		// organization_policy와 동일하게 ACTIVE/SUSPENDED만 직접 지정 가능한 값이다.
		// (UpdateOperationSettingRequest에 걸린 것과 동일한 규칙을 이 서비스 계층에서도 적용한다.)
		if (request.status() != OrganizationStatus.ACTIVE && request.status() != OrganizationStatus.SUSPENDED) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기관 운영 상태는 활성 또는 정지만 직접 설정할 수 있습니다.");
		}

		if (request.name() != null && !request.name().isBlank()) {
			String normalizedName = normalize(request.name());
			boolean nameChanged = !normalizedName.equals(organization.getNormalizedName());
			if (nameChanged && organizationRepository.existsByNormalizedNameAndDeletedAtIsNull(normalizedName)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 기관명입니다.");
			}
			organization.rename(request.name(), normalizedName);
		}

		organization.changeStatus(request.status());
		organization.touchUpdatedBy(requesterId);

		OrganizationPolicy policy = getActivePolicyOrThrow(organizationId);

		List<UUID> singleOrgId = List.of(organizationId);
		int cohortCount = organizationStatsRepository.countActiveCohortsByOrgId(singleOrgId).getOrDefault(organizationId, 0);
		int managerCount = organizationStatsRepository.countActiveManagersByOrgId(singleOrgId).getOrDefault(organizationId, 0);
		int traineeCount = organizationStatsRepository.countActiveTraineesByOrgId(singleOrgId).getOrDefault(organizationId, 0);
		BigDecimal aiCost = currentMonthAiCostByOrgId(singleOrgId).getOrDefault(organizationId, BigDecimal.ZERO);

		return toResponse(organization, policy.getRetentionDays(), policy.getDefaultDisclosureScope(), cohortCount, managerCount, traineeCount, aiCost);
	}

	@Override
	@Transactional
	public DeleteOrganizationResponse deleteOrganization(UUID organizationId) {
		Organization organization = getOrganizationOrThrow(organizationId);

		// 이미 삭제된 기관에 softDelete를 다시 적용하면 retentionUntil이 현재 시각 기준으로 다시 늘어나 버린다.
		if (organization.getStatus() == OrganizationStatus.DELETED) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 삭제된 기관입니다.");
		}

		int retentionDays = getActivePolicyOrThrow(organizationId).getRetentionDays();

		organization.softDelete(retentionDays, null);

		return new DeleteOrganizationResponse(organization.getOrgId(), organization.getDeletedAt(), organization.getRetentionUntil());
	}

	private Organization getOrganizationOrThrow(UUID organizationId) {
		return organizationRepository.findById(organizationId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기관을 찾을 수 없습니다: " + organizationId));
	}

	private OrganizationPolicy getActivePolicyOrThrow(UUID organizationId) {
		return organizationPolicyRepository.findByOrgIdAndStatus(organizationId, OrganizationPolicy.Status.ACTIVE)
				.orElseThrow(() -> new IllegalStateException("기관에 활성 운영 정책이 없습니다: " + organizationId));
	}

	private Map<UUID, OrganizationPolicy> activePolicyByOrgId(List<Organization> organizations) {
		List<UUID> orgIds = organizations.stream().map(Organization::getOrgId).toList();
		return organizationPolicyRepository.findByOrgIdInAndStatus(orgIds, OrganizationPolicy.Status.ACTIVE).stream()
				.collect(Collectors.toMap(OrganizationPolicy::getOrgId, policy -> policy));
	}

	// 이름 검색/중복확인에 사용하는 정규화 규칙(트림 + 소문자). organization.normalized_name 컬럼과 동일한 규칙을 적용한다.
	private String normalize(String name) {
		return name.trim().toLowerCase(Locale.ROOT);
	}

	// 이번 달(UTC 기준) 1일 00:00 ~ 다음 달 1일 00:00 직전까지의 AI 비용 합계를 기관별로 조회한다.
	// operations.findUsage()가 특정 월(period)을 파라미터로 받는 것과 달리, 여기서는 항상 "이번 달"만 본다.
	private Map<UUID, BigDecimal> currentMonthAiCostByOrgId(Collection<UUID> orgIds) {
		if (orgIds.isEmpty()) {
			return Map.of();
		}
		YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
		Instant from = currentMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant to = currentMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		return aiUsageRepository.sumCostByOrgId(orgIds, from, to).stream()
				.collect(Collectors.toMap(OrgAiCostTotal::orgId, OrgAiCostTotal::totalCost));
	}

	private OrganizationResponse toResponse(
			Organization organization,
			int retentionDays,
			DisclosureScope defaultDisclosureScope,
			int cohortCount,
			int managerCount,
			int traineeCount,
			BigDecimal currentMonthAiCost
	) {
		return new OrganizationResponse(
				organization.getOrgId(),
				organization.getName(),
				organization.getStatus(),
				cohortCount,
				managerCount,
				traineeCount,
				currentMonthAiCost,
				retentionDays,
				defaultDisclosureScope,
				organization.getCreatedAt(),
				organization.getDeletedAt()
		);
	}
}
