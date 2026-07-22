package com.bigproject.backend.domain.organization.application;

import com.bigproject.backend.domain.operations.domain.DisclosureScope;
import com.bigproject.backend.domain.organization.domain.Organization;
import com.bigproject.backend.domain.organization.domain.OrganizationPolicy;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import com.bigproject.backend.domain.organization.domain.repository.OrganizationPolicyRepository;
import com.bigproject.backend.domain.organization.domain.repository.OrganizationRepository;
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

	@Override
	public OrganizationListResponse findOrganizations(String query, OrganizationStatus status, int page, int size) {
		String normalizedQuery = (query == null || query.isBlank()) ? null : normalize(query);
		Page<Organization> result = organizationRepository.search(normalizedQuery, status, PageRequest.of(page, size));

		List<Organization> organizations = result.getContent();
		Map<UUID, Integer> retentionDaysByOrgId = retentionDaysByOrgId(organizations);

		List<OrganizationResponse> content = organizations.stream()
				.map(organization -> toResponse(organization, retentionDaysByOrgId.getOrDefault(organization.getOrgId(), 0)))
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
		organizationRepository.save(organization);

		OrganizationPolicy policy = OrganizationPolicy.createInitial(
				organization.getOrgId(),
				DEFAULT_MONTHLY_AI_BUDGET,
				DEFAULT_CURRENCY_CODE,
				request.dataRetentionDays(),
				DEFAULT_DISCLOSURE_SCOPE,
				requesterId
		);
		organizationPolicyRepository.save(policy);

		return toResponse(organization, policy.getRetentionDays());
	}

	@Override
	public OrganizationResponse findOrganization(UUID organizationId) {
		Organization organization = getOrganizationOrThrow(organizationId);
		int retentionDays = getActivePolicyOrThrow(organizationId).getRetentionDays();
		return toResponse(organization, retentionDays);
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

		int retentionDays = getActivePolicyOrThrow(organizationId).getRetentionDays();
		return toResponse(organization, retentionDays);
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

	private Map<UUID, Integer> retentionDaysByOrgId(List<Organization> organizations) {
		List<UUID> orgIds = organizations.stream().map(Organization::getOrgId).toList();
		return organizationPolicyRepository.findByOrgIdInAndStatus(orgIds, OrganizationPolicy.Status.ACTIVE).stream()
				.collect(Collectors.toMap(OrganizationPolicy::getOrgId, OrganizationPolicy::getRetentionDays));
	}

	// 이름 검색/중복확인에 사용하는 정규화 규칙(트림 + 소문자). organization.normalized_name 컬럼과 동일한 규칙을 적용한다.
	private String normalize(String name) {
		return name.trim().toLowerCase(Locale.ROOT);
	}

	// cohortCount, managerCount, traineeCount, currentMonthAiCost는 각각 cohort/member/operations(ai_usage) 도메인의
	// 엔티티가 있어야 정확히 계산할 수 있다. 이번 작업 범위(organization, operations)에는 cohort/member 엔티티가 없으므로
	// 우선 0/ZERO로 채우고, 해당 도메인 구현 후 실제 집계 로직으로 교체가 필요하다.
	private OrganizationResponse toResponse(Organization organization, int retentionDays) {
		return new OrganizationResponse(
				organization.getOrgId(),
				organization.getName(),
				organization.getStatus(),
				0,
				0,
				0,
				BigDecimal.ZERO,
				retentionDays,
				organization.getCreatedAt(),
				organization.getDeletedAt()
		);
	}
}
