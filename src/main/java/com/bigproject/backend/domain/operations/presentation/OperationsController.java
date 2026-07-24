package com.bigproject.backend.domain.operations.presentation;

import com.bigproject.backend.domain.operations.application.OperationsService;
import com.bigproject.backend.domain.operations.presentation.dto.OperationSettingResponse;
import com.bigproject.backend.domain.operations.presentation.dto.OrganizationUsageResponse;
import com.bigproject.backend.domain.operations.presentation.dto.UpdateOperationSettingRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.UUID;

@Tag(name = "Operations", description = "기관 사용량·AI 비용·운영 설정 API")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RestController
@RequestMapping("/organizations/{organizationId}/operations")
@RequiredArgsConstructor
public class OperationsController {

	private final OperationsService operationsService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			summary = "기관 월별 저장량·활동·AI 비용 조회",
			description = """
					지정한 기관의 특정 월(period) 사용량을 저장량·활동량·AI 비용 세 영역으로 나눠 조회한다.

					**요청**
					- organizationId (경로)
					- period (필수, yyyy-MM 형식, 예: 2026-07)

					**응답**
					- storage: 코드 제출물·세션 로그·채점 근거·리포트별 저장 바이트
					- activity: 활성 교육생·완료 세션·채점 건수·생성 리포트 수 — 현재는 관련 도메인 미구현으로 항상 0
					- aiCost: 총 비용·월 예산·예산 초과 여부·모델별 호출/토큰/비용 내역
					"""
	)
	@GetMapping("/usage")
	public ResponseEntity<OrganizationUsageResponse> findUsage(
			@PathVariable UUID organizationId,
			@RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth period
	) {
		return ResponseEntity.ok(operationsService.findUsage(organizationId, period));
	}

	@Operation(
			summary = "기관 운영 설정 조회",
			description = """
					지정한 기관의 현재 활성(ACTIVE) 운영 정책을 조회한다.

					**요청**
					- organizationId (경로)

					**응답**
					- organizationStatus: 운영 상태
					- monthlyAiBudget: 월 AI 예산
					- dataRetentionDays: 데이터 보존기간
					- defaultDisclosureScope: 기본 공개범위 (SUMMARY/PRIVATE/FULL)
					- 기관에 활성 정책이 없으면(정상 생성 경로를 거치지 않은 데이터 등) 500 오류가 발생한다.
					"""
	)
	@GetMapping("/settings")
	public ResponseEntity<OperationSettingResponse> findSettings(@PathVariable UUID organizationId) {
		return ResponseEntity.ok(operationsService.findSettings(organizationId));
	}

	@Operation(
			summary = "기관 운영 설정 변경",
			description = """
					지정한 기관의 운영 설정을 변경한다. organization_policy는 append-only 이력 테이블이라 \
					기존 설정을 수정하는 게 아니라 기존 활성 버전을 SUPERSEDED로 닫고 새 버전을 발급하는 방식으로 동작한다.

					**요청** (부분 수정이 아니라 아래 값을 모두 포함해서 보내야 한다)
					- organizationId (경로)
					- organizationStatus (필수): ACTIVE 또는 SUSPENDED만 직접 지정 가능
					- monthlyAiBudget (필수)
					- dataRetentionDays (필수, 30~3650일)
					- defaultDisclosureScope (필수)

					**응답**
					- 새로 발급된 버전 기준의 운영 설정
					"""
	)
	@PutMapping("/settings")
	public ResponseEntity<OperationSettingResponse> updateSettings(
			@PathVariable UUID organizationId,
			@Valid @RequestBody UpdateOperationSettingRequest request
	) {
		OperationSettingResponse response = operationsService.updateSettings(
				organizationId, request, currentUserResolver.resolveCurrentMemberId()
		);
		return ResponseEntity.ok(response);
	}
}
