package com.bigproject.backend.domain.usagemetering.application;

import com.bigproject.backend.domain.usagemetering.presentation.dto.OperationSettingResponse;
import com.bigproject.backend.domain.usagemetering.presentation.dto.OrganizationUsageResponse;
import com.bigproject.backend.domain.usagemetering.presentation.dto.UpdateOperationSettingRequest;

import java.time.YearMonth;
import java.util.UUID;

/**
 * 운영(Operations) 도메인의 비즈니스 로직 인터페이스.
 * {@link com.bigproject.backend.domain.usagemetering.presentation.OperationsController}의 각 엔드포인트와 1:1로 대응한다.
 */
public interface OperationsService {

	/** 기관의 특정 월(period) 저장량·활동·AI 비용 사용량 조회. */
	/**
	 * 기관의 특정 월(period) 저장량·활동·AI 비용 사용량 조회.
	 *
	 * @param cohortId 선택. 목업 OP-06 ⑤ 비용 탭은 상단 기수 스위처가 범위를 정하므로 기수별·반별 내역을 좁힌다.
	 *                 null이면 기관 전체를 본다(SA-02 ③ 슈퍼어드민 화면).
	 */
	OrganizationUsageResponse findUsage(UUID organizationId, YearMonth period, UUID cohortId);

	/** 기관의 현재(활성) 운영 설정(정책) 조회. */
	OperationSettingResponse findSettings(UUID organizationId);

	/**
	 * 기관 운영 설정 변경. organization_policy는 append-only 버전 이력이므로
	 * "수정"이 아니라 기존 활성 버전을 SUPERSEDED로 전환하고 새 버전을 발급하는 방식으로 동작한다.
	 *
	 * @param requesterId 감사 컬럼(organization_policy.created_by, organization.updated_by)에 기록될 요청자 UUID.
	 */
	OperationSettingResponse updateSettings(UUID organizationId, UpdateOperationSettingRequest request, UUID requesterId);
}
