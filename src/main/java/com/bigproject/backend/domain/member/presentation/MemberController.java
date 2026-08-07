package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.member.application.ManagerRosterService;
import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.ManagerRosterRepository;
import com.bigproject.backend.domain.member.domain.ManagerRosterSort;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerResponse;
import com.bigproject.backend.domain.member.presentation.dto.ManagerRosterResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

@Tag(name = "Member", description = "사용자 계정, 역할, 초대, 활성화, 계정 상태, 약관 동의 API (매니저·교육생)")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping(value = "/members", produces = MediaType.APPLICATION_JSON_VALUE)
@lombok.RequiredArgsConstructor
public class MemberController {
	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final MemberInvitationService memberInvitationService;
	private final ManagerRosterService managerRosterService;

	@Operation(
			operationId = "inviteManager",
			summary = "매니저 초대 | ✅ 사용 가능",
			description = """
					오퍼레이터가 자기 기관의 **매니저**를 초대합니다(OP-06). \
					이 경로가 만드는 계정은 항상 MANAGER이며, 다른 역할은 만들 수 없습니다.

					**초대 권한 체계**: 슈퍼어드민 → 오퍼레이터 초대는 \
					`POST /organizations/{organizationId}/operators/invitations`(SA-02 ②)가 전담하고, \
					오퍼레이터 → 매니저 초대는 이 API가 전담합니다.

					**요청**
					- organizationId (경로): 초대 대상 기관. 호출자의 소속 기관과 다르면 403
					- email (필수, 최대 320자): 초대받을 이메일
					- cohortId (필수): 담당할 기수 ID. 그 기관의 기수가 아니면 400
					- X-Request-Id (헤더, 선택): 초대 추적용 식별자. 생략하면 서버가 만든다

					**반 배정은 초대 시점에 하지 않습니다** — 요청에 반 필드가 없으며, 담당 반은 가입 이후
					`PATCH /cohorts/{cohortId}/classrooms/{classroomId}/managers`로 따로 정합니다.

					**응답 (201)**
					- memberId: 생성된 계정 자리 ID
					- email / role(항상 MANAGER) / status(항상 INVITED) / invitedAt

					**메일이 나갔다고 계정이 활성화된 것은 아닙니다.** 초대받은 매니저가
					`POST /auth/manager-signup`에서 이름·비밀번호를 정해야 활성 계정이 됩니다.

					⚠️ 메일 발송에 실패하면(502) 계정 자리도 **함께 롤백**되어 아무것도 남지 않습니다.
					"""
	)
	@PreAuthorize("hasRole('OPERATOR')")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "초대 원장·현재 토큰 생성 및 메일 발송 성공; 계정 활성화 완료를 의미하지 않음"),
			@ApiResponse(responseCode = "400", description = "이메일 형식이 올바르지 않거나 담당 기수가 없거나 기관에 속하지 않은 기수임"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "호출자가 오퍼레이터가 아니거나 다른 기관을 지정함"),
			@ApiResponse(responseCode = "404", description = "초대 대상 기관을 찾을 수 없음"),
			@ApiResponse(responseCode = "409", description = "이미 등록된 계정 또는 동일 대상의 미완료 초대가 존재함"),
			@ApiResponse(responseCode = "500", description = "이메일 중복이 아닌 DB 제약 위반 등으로 초대 정보를 저장하지 못함"),
			@ApiResponse(responseCode = "502", description = "초대 메일 발송 실패로 초대 트랜잭션을 완료하지 못함")
	})
	@PostMapping("/organizations/{organizationId}/manager-invitations")
	public ResponseEntity<InviteManagerResponse> inviteManager(
			@Parameter(description = "초대 대상 기관 ID. 호출자인 오퍼레이터의 소속 기관만 허용됩니다.", example = "123e4567-e89b-12d3-a456-426614174000")
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
				Role.MANAGER,
				authentication.getName(),
				requestId
		);
		return ResponseEntity.created(URI.create("/api/v0/members/" + response.memberId())).body(response);
	}

	@Operation(
			operationId = "findMembers",
			summary = "회원 목록 조회 | ✅ 사용 가능",
			description = """
					운영 관리 `매니저` 탭의 매니저 목록 표를 채운다. `role=MANAGER`만 지원한다 —
					다른 역할 목록은 각자의 화면 전용 API(SA-02 오퍼레이터 목록 등)를 쓴다.
					계정 상태로 필터링하고 이름·이메일로 검색하며 페이지네이션한다. 조회 범위인 기관은
					액세스 토큰에서 가져온다.

					**요청**
					- role (쿼리, **필수**): 현재 MANAGER만 지원. 다른 값은 400
					- status (쿼리, 선택): INVITED(초대 대기) / ACTIVE(활성) / INACTIVE(정지). 생략하면 전체.
					  LOCKED는 이 화면에서 쓰지 않는 값이라 지정하면 400
					- query (쿼리, 선택): 이름·이메일 부분검색
					- sort (쿼리, 선택, 기본 NAME): NAME(이름순) / ASSIGNED_TRAINEE_COUNT(담당 인원순)
					- page (쿼리, 선택, 기본 0) / size (쿼리, 선택, 기본 20, 최대 100)

					**응답 (200)**
					- content[]: 매니저 목록(이름·이메일·계정 상태·담당 기수·담당 반·담당 인원·최근 접속·초대일)
					- page / size / totalElements / totalPages

					**content[].cohortId·cohortName은 가장 최근 매니저 초대의 담당 기수다.** 매니저 초대는
					`POST /organizations/{organizationId}/manager-invitations`에서 기수를 필수로 지정하므로
					초대 이력이 있는 매니저라면 항상 값이 있다.

					**content[].classroomNames·assignedTraineeCount는 현재 활성 담당 배정 기준이다.**
					담당 반이 없으면 classroomNames는 빈 배열, assignedTraineeCount는 0이다(화면의 `미배정`).
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "매니저 목록 조회 성공"),
			@ApiResponse(responseCode = "400", description = "role이 MANAGER가 아니거나 page·size·status 값이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "500", description = "인증 정보에서 organizationId를 확인할 수 없음")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@GetMapping
	public ResponseEntity<ManagerRosterResponse> findMembers(
			@Parameter(description = "조회할 역할. 현재 MANAGER만 지원한다", example = "MANAGER")
			@RequestParam Role role,
			@Parameter(description = "계정 상태 필터. INVITED/ACTIVE/INACTIVE만 지원하며 생략하면 전체")
			@RequestParam(required = false) AccountStatus status,
			@Parameter(description = "이름·이메일 부분검색", example = "강민서")
			@RequestParam(required = false) String query,
			@Parameter(description = "정렬 기준", example = "NAME")
			@RequestParam(required = false, defaultValue = "NAME") ManagerRosterSort sort,
			@Parameter(description = "0부터 시작하는 페이지 번호", example = "0")
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@Parameter(description = "페이지당 개수(최대 100)", example = "20")
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			Authentication authentication
	) {
		if (role != Role.MANAGER) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role은 현재 MANAGER만 지원합니다.");
		}
		UUID organizationId = extractOrganizationId(authentication);

		Page<ManagerRosterRepository.ManagerRosterRow> managerPage = managerRosterService.findManagers(
				organizationId, status, query, sort, PageRequest.of(page, size));

		ManagerRosterResponse response = new ManagerRosterResponse(
				managerPage.getContent().stream().map(ManagerRosterResponse.Manager::from).toList(),
				managerPage.getNumber(),
				managerPage.getSize(),
				managerPage.getTotalElements(),
				managerPage.getTotalPages()
		);
		return ResponseEntity.ok(response);
	}

	private UUID extractOrganizationId(Authentication authentication) {
		Object details = authentication.getDetails();
		if (!(details instanceof UUID organizationId)) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
					"인증 정보에서 organizationId(UUID)를 확인할 수 없습니다.");
		}
		return organizationId;
	}
}
