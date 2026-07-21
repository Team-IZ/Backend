package com.bigproject.backend.domain.operations.application;

import com.bigproject.backend.domain.operations.presentation.dto.OperationSettingResponse;
import com.bigproject.backend.domain.operations.presentation.dto.OrganizationUsageResponse;
import com.bigproject.backend.domain.operations.presentation.dto.UpdateOperationSettingRequest;

import java.time.YearMonth;
import java.util.UUID;

/**
 * 운영(Operations) 도메인의 비즈니스 로직 인터페이스.
 * {@link com.bigproject.backend.domain.operations.presentation.OperationsController}의 각 엔드포인트와 1:1로 대응한다.
 */
public interface OperationsService {

	/** 기관의 특정 월(period) 저장량·활동·AI 비용 사용량 조회. */
	OrganizationUsageResponse findUsage(UUID organizationId, YearMonth period);

	/** 기관의 현재(활성) 운영 설정(정책) 조회. */
	OperationSettingResponse findSettings(UUID organizationId);

	/**
	 * 기관 운영 설정 변경. organization_policy는 append-only 버전 이력이므로
	 * "수정"이 아니라 기존 활성 버전을 SUPERSEDED로 전환하고 새 버전을 발급하는 방식으로 동작한다.
	 *
	 * @param requesterId 감사 컬럼(organization_policy.configured_by, organization.updated_by)에 기록될 요청자 UUID.
	 */
	OperationSettingResponse updateSettings(UUID organizationId, UpdateOperationSettingRequest request, UUID requesterId);
}
