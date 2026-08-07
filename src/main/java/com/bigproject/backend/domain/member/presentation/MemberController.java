package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@Tag(name = "Member", description = "사용자 계정, 역할, 초대, 활성화, 계정 상태, 약관 동의 API (매니저·교육생)")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping("/members")
@lombok.RequiredArgsConstructor
public class MemberController {
	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final MemberInvitationService memberInvitationService;

	@Operation(
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
}
