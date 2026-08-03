package com.bigproject.backend.domain.platformgovernance.application;

import com.bigproject.backend.domain.platformgovernance.presentation.dto.PlatformModelSettingResponse;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.SuperAdminListResponse;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.UpdateGradingModelRequest;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.UpdateModelPricingRequest;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.UpdateTierModelRequest;
import com.bigproject.backend.domain.organization.domain.OperatorAccountStatus;

import java.util.UUID;

/**
 * 플랫폼 전역 설정. 목업 SA-03 `플랫폼 설정 — 탭 2`에 대응한다.
 *
 * <p>기관 1곳의 운영 설정({@link OperationsService})과 구분된다 — 이쪽은 <b>전 기관에 걸리는</b>
 * 모델·단가·계정을 다루며 슈퍼어드민만 접근한다.
 */
public interface PlatformOperationsService {

	/** 모델·단가 탭 전체 조회: 채점 정책 + 티어 매핑 + 모델별 단가 + 캘리브레이션 진행 현황. */
	PlatformModelSettingResponse findModelSettings();

	/**
	 * 채점 모델 변경. <b>되돌릴 수 없다.</b>
	 * 새 정책 버전을 발급하고 캘리브레이션 버전을 만들고 전 기관 재캘리브레이션 대기 행을 생성한다.
	 */
	PlatformModelSettingResponse updateGradingModel(UpdateGradingModelRequest request, UUID requesterId);

	/** 티어 ↔ 모델 매핑 변경. 재캘리브레이션은 발생하지 않는다. */
	PlatformModelSettingResponse updateTierModel(UpdateTierModelRequest request, UUID requesterId);

	/** 모델 단가 수정. 입력·출력 단가를 모두 비우면 `단가 미설정`으로 되돌린다. */
	PlatformModelSettingResponse updateModelPricing(UUID modelId, UpdateModelPricingRequest request, UUID requesterId);

	/** 슈퍼어드민 계정 목록. */
	SuperAdminListResponse findSuperAdmins();

	/** 슈퍼어드민 정지·재활성. 마지막 활성 1인은 정지할 수 없다. */
	SuperAdminListResponse updateSuperAdminStatus(UUID memberId, OperatorAccountStatus status);
}
