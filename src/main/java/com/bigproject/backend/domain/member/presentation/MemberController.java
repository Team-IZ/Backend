package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.application.MemberQueryService;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.MemberSortField;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.domain.SortDirection;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerResponse;
import com.bigproject.backend.domain.member.presentation.dto.MemberListResponse;
import com.bigproject.backend.domain.member.presentation.dto.MemberSummaryResponse;
import com.bigproject.backend.domain.member.presentation.dto.UpdateManagerAssignmentsRequest;
import com.bigproject.backend.domain.member.presentation.dto.UpdateMemberStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@Tag(name = "Member", description = "매니저와 교육생 계정·초대·배정 API")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping("/members")
@lombok.RequiredArgsConstructor
public class MemberController {
	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final MemberInvitationService memberInvitationService;
	private final MemberQueryService memberQueryService;

	@Operation(
			summary = "기관 매니저 목록 조회",
			description = "슈퍼어드민은 organizationId가 필수이며, 오퍼레이터는 자기 기관만 조회합니다. "
					+ "role을 생략하면 오퍼레이터·담당 매니저를 모두 반환하며 권한·상태는 한글 표시명, 최근 로그인은 날짜로 제공합니다."
	)
	@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATOR')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "기관 범위 매니저 목록 조회 성공"),
			@ApiResponse(responseCode = "400", description = "필터·정렬·페이지 값 또는 슈퍼어드민 기관 ID가 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "역할·계정·기관 상태 또는 기관 접근 범위가 허용되지 않음"),
			@ApiResponse(responseCode = "404", description = "조회할 기관을 찾을 수 없음")
	})
	@GetMapping
	public ResponseEntity<MemberListResponse> findMembers(
			@Parameter(description = "조회할 기관 ID이며 슈퍼어드민은 필수입니다.", example = "123e4567-e89b-12d3-a456-426614174000")
			@RequestParam(required = false) UUID organizationId,
			@Parameter(description = "조회할 매니저 역할이며 생략 시 오퍼레이터·일반 매니저를 모두 조회합니다.", example = "MANAGER")
			@RequestParam(required = false) Role role,
			@Parameter(description = "조회할 계정 상태이며 생략 시 모든 상태를 조회합니다.", example = "ACTIVE")
			@RequestParam(required = false) AccountStatus status,
			@Parameter(description = "이름 또는 이메일에 적용할 대소문자 무시 검색어입니다.", example = "manager@example.com")
			@RequestParam(required = false) @Size(max = 200) String query,
			@Parameter(description = "0부터 시작하는 페이지 번호입니다.", example = "0")
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@Parameter(description = "페이지당 항목 수이며 1~100까지 허용합니다.", example = "20")
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			@Parameter(description = "이름 또는 최근 로그인 시각 기준 정렬 필드입니다.", example = "NAME")
			@RequestParam(defaultValue = "NAME") MemberSortField sortBy,
			@Parameter(description = "정렬 방향입니다.", example = "ASC")
			@RequestParam(defaultValue = "ASC") SortDirection direction,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(memberQueryService.findManagers(
				organizationId,
				role,
				status,
				query,
				page,
				size,
				sortBy,
				direction,
				authentication.getName()
		));
	}

	@Operation(summary = "회원 상세 조회", description = "회원 ID로 계정·소속·배정 상세를 조회하는 미구현 API입니다.")
	@ApiResponses({
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "501", description = "회원 상세 조회 기능이 아직 구현되지 않음")
	})
	@GetMapping("/{memberId}")
	public ResponseEntity<MemberSummaryResponse> findMember(
			@Parameter(description = "상세 조회할 회원 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID memberId
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(
			summary = "기관 운영자 초대",
			description = "슈퍼어드민은 지정 기관의 오퍼레이터만, 오퍼레이터는 자기 기관의 매니저만 초대합니다. "
					+ "요청에는 역할을 받지 않고 서버가 호출자 역할로 대상 역할을 결정합니다. "
					+ "오퍼레이터 초대는 email만 사용하며 cohortId와 targetClassId를 허용하지 않습니다. "
					+ "매니저 초대는 cohortId가 필수이고 targetClassId는 선택이며, 생략하면 가입 후 미배정 상태가 됩니다."
	)
	@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATOR')")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "초대 원장·현재 토큰 생성 및 메일 발송 성공; 계정 활성화 완료를 의미하지 않음"),
			@ApiResponse(responseCode = "400", description = "이메일 형식 또는 호출자 역할에 따른 기수·반 입력 조건이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "호출자가 SUPER_ADMIN·OPERATOR가 아니거나 오퍼레이터가 다른 기관을 지정함"),
			@ApiResponse(responseCode = "404", description = "초대 대상 기관을 찾을 수 없음"),
			@ApiResponse(responseCode = "409", description = "이미 등록된 계정 또는 동일 대상의 미완료 초대가 존재함"),
			@ApiResponse(responseCode = "500", description = "이메일 중복이 아닌 DB 제약 위반 등으로 초대 정보를 저장하지 못함"),
			@ApiResponse(responseCode = "502", description = "초대 메일 발송 실패로 초대 트랜잭션을 완료하지 못함")
	})
	@PostMapping("/organizations/{organizationId}/manager-invitations")
	public ResponseEntity<InviteManagerResponse> inviteManager(
			@Parameter(description = "초대 대상 기관 ID. SUPER_ADMIN은 지정 기관, OPERATOR는 자기 소속 기관만 허용됩니다.", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID organizationId,
			@Valid @RequestBody InviteManagerRequest request,
			@Parameter(description = "초대 생성·토큰 발급 추적용 요청 ID이며 생략 시 서버가 생성합니다.", example = "invite-request-001")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		InviteManagerResponse response = memberInvitationService.inviteManager(
				organizationId,
				request,
				authentication.getName(),
				requestId
		);
		return ResponseEntity.created(URI.create("/api/v0/members/" + response.memberId())).body(response);
	}

	@Operation(summary = "회원 계정 상태 변경", description = "회원 계정 상태와 변경 사유를 기록하는 미구현 API입니다.")
	@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATOR')")
	@ApiResponses({
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "계정 상태 변경 권한이 없음"),
			@ApiResponse(responseCode = "501", description = "회원 계정 상태 변경 기능이 아직 구현되지 않음")
	})
	@PatchMapping("/{memberId}/status")
	public ResponseEntity<MemberSummaryResponse> updateStatus(
			@Parameter(description = "상태를 변경할 회원 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID memberId,
			@Valid @RequestBody UpdateMemberStatusRequest request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "담당 매니저의 기수·반 배정 변경", description = "일반 매니저의 담당 기수·반과 변경 사유를 기록하는 미구현 API입니다.")
	@PreAuthorize("hasRole('OPERATOR')")
	@ApiResponses({
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한이 없음"),
			@ApiResponse(responseCode = "501", description = "매니저 배정 변경 기능이 아직 구현되지 않음")
	})
	@PatchMapping("/{memberId}/manager-assignments")
	public ResponseEntity<MemberSummaryResponse> updateManagerAssignments(
			@Parameter(description = "담당 범위를 변경할 일반 매니저 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID memberId,
			@Valid @RequestBody UpdateManagerAssignmentsRequest request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}
}
