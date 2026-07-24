package com.bigproject.backend.domain.organization.application;

import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import com.bigproject.backend.domain.organization.presentation.dto.CreateOrganizationRequest;
import com.bigproject.backend.domain.organization.presentation.dto.DeleteOrganizationResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationListResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationResponse;
import com.bigproject.backend.domain.organization.presentation.dto.UpdateOrganizationRequest;

import java.util.UUID;

/**
 * 기관(Organization) 도메인의 비즈니스 로직 인터페이스.
 * {@link com.bigproject.backend.domain.organization.presentation.OrganizationController}의 각 엔드포인트와 1:1로 대응한다.
 */
public interface OrganizationService {

	/** 기관 목록 조회: 이름 검색(query) + 상태 필터(status) + 페이지네이션(page, size). */
	OrganizationListResponse findOrganizations(String query, OrganizationStatus status, int page, int size);

	/**
	 * 기관 생성 + 최초 운영 정책(organization_policy 버전 1) 초기화.
	 *
	 * @param requesterId 감사 컬럼(organization.created_by, organization_policy.configured_by)에 기록될 요청자 UUID.
	 *                    Controller에 인증 주체를 꺼내는 로직이 아직 연결되지 않아 서비스 계층 파라미터로 우선 분리해두었다.
	 */
	OrganizationResponse createOrganization(CreateOrganizationRequest request, UUID requesterId);

	/** 기관 단건 조회. 존재하지 않으면 404(NOT_FOUND)를 던진다. */
	OrganizationResponse findOrganization(UUID organizationId);

	/** 기관 이름/운영 상태 변경. 상태는 ACTIVE 또는 SUSPENDED만 직접 지정할 수 있다. */
	OrganizationResponse updateOrganization(UUID organizationId, UpdateOrganizationRequest request, UUID requesterId);

	/** 기관 soft-delete. 활성 정책의 retentionDays만큼 뒤를 파기 가능 시각(purgeAvailableAt)으로 계산한다. */
	DeleteOrganizationResponse deleteOrganization(UUID organizationId);
}
