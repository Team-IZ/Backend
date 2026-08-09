package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.application.MemberProfileService;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerResponse;
import com.bigproject.backend.domain.member.presentation.dto.MemberProfileResponse;
import com.bigproject.backend.global.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

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
	private final MemberProfileService memberProfileService;

	@Operation(
			operationId = "getCurrentMember",
			summary = "내 정보 조회 | ✅ 사용 가능",
			description = """
					**지금 이 액세스 토큰의 주인**을 서버에서 다시 읽어 내려줍니다. 역할에 관계없이 호출할 수 있습니다.

					**요청**
					- 본문·경로 파라미터 없음. `Authorization: Bearer {accessToken}`만 필요합니다

					**응답**
					- memberId / email / name / role / organizationId(슈퍼어드민은 null) / status
					- **emailDomain**: 소속 기관의 이메일 도메인. 없으면 null (9차 Q3-④)

					## `emailDomain` — 초대 화면의 도메인 제한용

					명단·매니저 초대가 **기관 이메일 도메인 밖 주소를 막는데**, 같은 값이 있는
					`GET /organizations/{organizationId}`는 **슈퍼어드민 전용이라 오퍼레이터가 부르면 403**입니다.
					그래서 여기에 함께 내려줍니다 — 프론트 상수로 두면 기관을 하나 더 만드는 순간 틀립니다.

					**`null`이 정상인 경우가 둘 있습니다**: 기관에 도메인이 설정되지 않았거나(컬럼이 nullable),
					호출자가 소속 기관이 없는 슈퍼어드민인 경우입니다. `null`이면 도메인 제한을 걸지 않으면 됩니다.

					⚠️ **오퍼레이터 초대에는 이 제한이 걸리지 않습니다**(의도된 것입니다). 오퍼레이터는 기관의
					첫 계정이라 초대받는 시점에 그 기관 메일함을 가질 수 없기 때문입니다. 제한은 오퍼레이터가
					매니저·교육생을 초대하는 경로에만 둡니다.

					**세션 복원용입니다.** 새로고침하면 브라우저 메모리가 비워지는데 `POST /auth/refresh`는
					토큰만 돌려주고 역할을 주지 않습니다. 역할을 모르면 사이드바도 라우팅도 그릴 수 없어
					역할을 브라우저 저장소에 남기게 되는데, 그러면 **정지·역할 변경을 화면이 모릅니다.**
					`부팅 → refresh → /members/me` 순서로 부르면 역할이 서버 진실이 되고 저장소에 신원 정보를
					남기지 않아도 됩니다.

					**값은 로그인 응답과 같습니다** — `redirectPath`와 토큰만 빠집니다. 즉 로그인 직후에는
					부를 필요가 없고, 새로고침·권한 재확인 시점에 부르면 됩니다.

					`status`가 `INACTIVE`로 오는 것은 **세션 도중에 계정이 정지된 것**입니다. 액세스 토큰은
					만료 전까지 계속 통과하므로, 그 사이에 알아채려면 이 값을 봐야 합니다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "현재 로그인한 사용자 정보"),
			@ApiResponse(responseCode = "401",
					description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨 · MEMBER_NOT_FOUND 토큰은 유효하지만 계정이 삭제됨")
	})
	@GetMapping("/me")
	public ResponseEntity<MemberProfileResponse> getCurrentMember() {
		return ResponseEntity.ok(memberProfileService.currentMember());
	}

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
			@ApiResponse(responseCode = "400", description = "EMAIL_FORMAT_INVALID 이메일 형식 오류 · MANAGER_COHORT_REQUIRED 담당 기수 미지정 · COHORT_NOT_IN_ORGANIZATION 기관에 속하지 않은 기수"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰 없음 · INVITER_NOT_FOUND 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "INVITE_ROLE_NOT_ALLOWED 이 역할을 초대할 권한이 없음 · INVITE_CROSS_ORGANIZATION 다른 기관 지정 · INVITER_NOT_ACTIVE 호출자가 활성 계정이 아님"),
			@ApiResponse(responseCode = "404", description = "ORGANIZATION_NOT_FOUND 초대 대상 기관을 찾을 수 없음"),
			@ApiResponse(responseCode = "409", description = "ALREADY_INVITED 이미 등록된 계정 또는 동일 대상의 미완료 초대가 존재함"),
			@ApiResponse(responseCode = "500", description = "INVITATION_SAVE_FAILED 이메일 중복이 아닌 DB 제약 위반 등으로 초대 정보를 저장하지 못함"),
			@ApiResponse(responseCode = "502", description = "INVITE_MAIL_FAILED 초대 메일 발송 실패로 초대 트랜잭션을 완료하지 못함. 계정은 만들어지지 않았다")
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

	private UUID extractOrganizationId(Authentication authentication) {
		Object details = authentication.getDetails();
		if (!(details instanceof UUID organizationId)) {
			throw new ApiException(AcademicOperationsErrorCode.ORGANIZATION_CONTEXT_MISSING);
		}
		return organizationId;
	}
}
