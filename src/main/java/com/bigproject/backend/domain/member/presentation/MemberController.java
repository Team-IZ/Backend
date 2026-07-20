package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerResponse;
import com.bigproject.backend.domain.member.presentation.dto.MemberListResponse;
import com.bigproject.backend.domain.member.presentation.dto.MemberSummaryResponse;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesResponse;
import com.bigproject.backend.domain.member.presentation.dto.UpdateManagerAssignmentsRequest;
import com.bigproject.backend.domain.member.presentation.dto.UpdateMemberStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Member", description = "매니저와 교육생 계정·초대·배정 API")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping("/members")
public class MemberController {

	@Operation(summary = "기관/기수/반 범위 회원 목록 조회")
	@GetMapping
	public ResponseEntity<MemberListResponse> findMembers(
			@RequestParam(required = false) Long organizationId,
			@RequestParam(required = false) Long cohortId,
			@RequestParam(required = false) Long classroomId,
			@RequestParam(required = false) Role role,
			@RequestParam(required = false) AccountStatus status,
			@RequestParam(required = false) String query,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "회원 상세 조회")
	@GetMapping("/{memberId}")
	public ResponseEntity<MemberSummaryResponse> findMember(@PathVariable Long memberId) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "기관 매니저 초대")
	@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LEAD_MANAGER')")
	@PostMapping("/organizations/{organizationId}/manager-invitations")
	public ResponseEntity<InviteManagerResponse> inviteManager(
			@PathVariable Long organizationId,
			@Valid @RequestBody InviteManagerRequest request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "기수 교육생 명단 등록 및 초대")
	@PreAuthorize("hasRole('LEAD_MANAGER')")
	@PostMapping("/cohorts/{cohortId}/trainees")
	public ResponseEntity<RegisterTraineesResponse> registerTrainees(
			@PathVariable Long cohortId,
			@Valid @RequestBody RegisterTraineesRequest request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "회원 계정 상태 변경")
	@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LEAD_MANAGER')")
	@PatchMapping("/{memberId}/status")
	public ResponseEntity<MemberSummaryResponse> updateStatus(
			@PathVariable Long memberId,
			@Valid @RequestBody UpdateMemberStatusRequest request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "담당 매니저의 기수·반 배정 변경")
	@PreAuthorize("hasRole('LEAD_MANAGER')")
	@PatchMapping("/{memberId}/manager-assignments")
	public ResponseEntity<MemberSummaryResponse> updateManagerAssignments(
			@PathVariable Long memberId,
			@Valid @RequestBody UpdateManagerAssignmentsRequest request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}
}
