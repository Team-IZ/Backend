package com.bigproject.backend.domain.organization.application;

import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
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

import java.util.UUID;

/**
 * 기관(Organization) 도메인의 비즈니스 로직 인터페이스.
 * {@link com.bigproject.backend.domain.organization.presentation.OrganizationController}의 각 엔드포인트와 1:1로 대응한다.
 */
public interface OrganizationService {

	/**
	 * 기관 목록 조회: 이름 검색(query) + 상태 필터(status) + 정렬(sort) + 페이지네이션(page, size). 목업 SA-01.
	 * 목업 툴바가 `검색 · 상태 ▾ · 정렬 ▾` 세 개다.
	 */
	OrganizationListResponse findOrganizations(
			String query,
			OrganizationStatus status,
			OrganizationSort sort,
			int page,
			int size
	);

	/** 플랫폼 전체 집계(기관 수·교육생 수·AI 비용·저장량). 목업 SA-01 상단 지표 카드. */
	PlatformSummaryResponse findPlatformSummary();

	/** 기관명 중복 확인. 목업 SA-01 생성 모달의 실시간 확인용. */
	OrganizationNameAvailabilityResponse checkNameAvailability(String name);

	/**
	 * 기관 생성 + 최초 운영 정책(organization_policy 버전 1) 초기화.
	 *
	 * @param requesterId 감사 컬럼(organization.created_by, organization_policy.created_by)에 기록될 요청자 UUID.
	 * @param idempotencyKey {@code Idempotency-Key} 헤더 값. null이면 멱등 처리를 하지 않는다.
	 *                       같은 키 + 같은 내용이면 최초 생성 결과를 그대로 돌려주고,
	 *                       같은 키 + 다른 내용이면 {@code ORG_IDEMPOTENCY_CONFLICT}로 거절한다.
	 */
	OrganizationResponse createOrganization(CreateOrganizationRequest request, UUID requesterId, UUID idempotencyKey);

	/** 기관 단건 조회. 존재하지 않으면 404(NOT_FOUND)를 던진다. 목업 SA-02 ① 개요. */
	OrganizationResponse findOrganization(UUID organizationId);

	/** 기관의 기수 목록(읽기전용). 목업 SA-02 ① 개요 하단 표. */
	OrganizationCohortListResponse findOrganizationCohorts(UUID organizationId);

	/** 기관 이름/운영 상태 변경. 상태는 ACTIVE 또는 SUSPENDED만 직접 지정할 수 있다. */
	OrganizationResponse updateOrganization(UUID organizationId, UpdateOrganizationRequest request, UUID requesterId);

	/**
	 * 기관 soft-delete. 활성 정책의 retentionDays만큼 뒤를 파기 가능 시각(purgeAvailableAt)으로 계산한다.
	 * 목업 case 7: 확인 모달에서 <b>기관명을 직접 입력</b>해야 진행된다.
	 */
	DeleteOrganizationResponse deleteOrganization(
			UUID organizationId,
			DeleteOrganizationRequest request,
			UUID requesterId,
			UUID idempotencyKey
	);

	/** soft-delete된 기관 복구. 보존기간이 지나기 전까지만 가능하다. */
	OrganizationResponse restoreOrganization(UUID organizationId, UUID requesterId);

	/**
	 * 기관 파기 요청. 보존기간이 남아 있으면 RETENTION_NOT_MET으로 거절한다(목업 case 8).
	 * 즉시 물리 삭제 경로는 두지 않는다 — 실수로 지우면 되돌릴 방법이 없다.
	 */
	PurgeOrganizationResponse purgeOrganization(UUID organizationId);
}
