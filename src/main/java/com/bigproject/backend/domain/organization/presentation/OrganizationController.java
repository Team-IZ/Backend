package com.bigproject.backend.domain.organization.presentation;

import com.bigproject.backend.domain.organization.application.OrganizationService;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import com.bigproject.backend.domain.organization.presentation.dto.CreateOrganizationRequest;
import com.bigproject.backend.domain.organization.presentation.dto.DeleteOrganizationResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationListResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationResponse;
import com.bigproject.backend.domain.organization.presentation.dto.UpdateOrganizationRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Organization", description = "기관 프로비저닝과 상태 관리 API")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Validated
@RestController
@RequestMapping("/organizations")
@RequiredArgsConstructor
public class OrganizationController {

	private final OrganizationService organizationService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			summary = "기관 목록 조회",
			description = """
					이름 검색과 상태 필터로 기관 목록을 페이지네이션 조회한다.

					**요청**
					- query (선택): 기관명 부분검색
					- status (선택): 운영 상태 필터
					- page (기본 0): 0부터 시작하는 페이지 번호
					- size (기본 20, 최대 100): 페이지당 개수

					**응답**
					- 기관별 이름·상태·보존기간·기본 공개범위·생성/삭제 시각을 담은 목록
					- cohortCount(삭제되지 않은 전체 기수 수)·managerCount(활성 매니저 수)·traineeCount(활성 교육생 수)는 \
					cohort/app_user 테이블을 직접 집계한 값
					- currentMonthAiCost는 이번 달(UTC 기준) ai_usage 합계 — 아직 사용 이력이 없으면 0
					- page/size/totalElements/totalPages 페이지 메타데이터
					"""
	)
	@GetMapping
	public ResponseEntity<OrganizationListResponse> findOrganizations(
			@RequestParam(required = false) String query,
			@RequestParam(required = false) OrganizationStatus status,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ResponseEntity.ok(organizationService.findOrganizations(query, status, page, size));
	}

	@Operation(
			summary = "기관 생성 및 기본 운영 정책 초기화",
			description = """
					새 기관(테넌트)을 생성하고, 동시에 기본값(월 예산 0, 통화 KRW, 공개범위 SUMMARY)으로 \
					최초 운영 정책(버전 1)을 함께 발급한다.

					**요청**
					- name (필수): 기관명
					- dataRetentionDays (필수, 30~3650일): 데이터 보존기간

					**응답**
					- 생성된 기관 정보(organizationId, dataRetentionDays, defaultDisclosureScope 등 포함) — \
					이후 다른 API 호출 시 이 organizationId를 사용한다.
					- 이미 사용 중인 기관명이면 409를 반환한다.
					"""
	)
	@PostMapping
	public ResponseEntity<OrganizationResponse> createOrganization(
			@Valid @RequestBody CreateOrganizationRequest request
	) {
		OrganizationResponse response =
				organizationService.createOrganization(request, currentUserResolver.resolveCurrentMemberId());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@Operation(
			summary = "기관 상세 조회",
			description = """
					organizationId로 기관 상세 정보를 조회한다.

					**요청**
					- organizationId (경로)

					**응답**
					- 목록 조회와 동일한 형태의 단건 정보(dataRetentionDays, defaultDisclosureScope 포함)
					- 존재하지 않는 organizationId면 404를 반환한다.
					"""
	)
	@GetMapping("/{organizationId}")
	public ResponseEntity<OrganizationResponse> findOrganization(@PathVariable UUID organizationId) {
		return ResponseEntity.ok(organizationService.findOrganization(organizationId));
	}

	@Operation(
			summary = "기관 이름 또는 운영 상태 변경",
			description = """
					기관의 이름과 운영 상태를 변경한다.

					**요청**
					- organizationId (경로)
					- name (선택): null/빈 값이면 이름을 변경하지 않는다. Swagger 예시값 "string"을 그대로 \
					두면 실제로 이름이 "string"으로 바뀌니 주의.
					- status (필수): ACTIVE 또는 SUSPENDED만 직접 지정 가능

					**응답**
					- 변경된 기관 정보
					- 이미 삭제(DELETED)된 기관이면 409, status에 그 외 값을 넣으면 400을 반환한다.
					"""
	)
	@PatchMapping("/{organizationId}")
	public ResponseEntity<OrganizationResponse> updateOrganization(
			@PathVariable UUID organizationId,
			@Valid @RequestBody UpdateOrganizationRequest request
	) {
		OrganizationResponse response = organizationService.updateOrganization(
				organizationId, request, currentUserResolver.resolveCurrentMemberId()
		);
		return ResponseEntity.ok(response);
	}

	@Operation(
			summary = "기관 soft-delete",
			description = """
					기관을 즉시 물리 삭제하지 않고 soft-delete 처리한다.

					**요청**
					- organizationId (경로)

					**응답**
					- deletedAt: 삭제 처리 시각
					- purgeAvailableAt: 활성 정책의 보존기간만큼 뒤, 실제 파기 가능 시각
					- 이미 삭제된 기관을 다시 호출하면 409를 반환한다.
					"""
	)
	@DeleteMapping("/{organizationId}")
	public ResponseEntity<DeleteOrganizationResponse> deleteOrganization(@PathVariable UUID organizationId) {
		return ResponseEntity.ok(organizationService.deleteOrganization(organizationId));
	}
}
