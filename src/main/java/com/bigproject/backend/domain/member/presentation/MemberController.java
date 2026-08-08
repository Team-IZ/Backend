package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.member.application.ManagerRosterService;
import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.application.MemberProfileService;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.ManagerRosterRepository;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.member.domain.ManagerRosterSort;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerResponse;
import com.bigproject.backend.domain.member.presentation.dto.MemberProfileResponse;
import com.bigproject.backend.domain.member.presentation.dto.ManagerRosterResponse;
import com.bigproject.backend.global.exception.ApiException;
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

	@Operation(
			operationId = "findMembers",
			summary = "회원 목록 조회 | ✅ 사용 가능",
			description = """
					운영 관리 `매니저` 탭의 매니저 목록 표를 채운다. 상태 필터·이름 검색·정렬·페이지네이션을
					**모두 서버가 처리**하므로 화면은 파라미터만 넘기면 된다. 조회 범위인 기관은 액세스 토큰에서
					가져온다. `role=MANAGER`만 지원하며, 다른 역할 목록은 각자의 화면 전용 API(SA-02 오퍼레이터
					목록 등)를 쓴다.

					## 요청 (쿼리 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `role` | **필수** | enum | 현재 `MANAGER`만 지원. 다른 값은 400 |
					| `cohortId` | 선택 | UUID | 담당 기수로 좁힌다. 화면 상단 기수 선택기에 대응. 비우면 기관 전체 |
					| `status` | 선택 | enum | `INVITED`(초대 대기) · `ACTIVE`(활성) · `INACTIVE`(정지). 비우면 전체(화면의 `상태 · 전체`) |
					| `query` | 선택 | string | 이름·이메일 부분검색. 비우면 전체 |
					| `sort` | 선택 | enum | `NAME`(이름순, 기본) · `ASSIGNED_TRAINEE_COUNT`(담당 인원순) |
					| `page` | 선택 | int | 0부터 시작. 기본 `0` |
					| `size` | 선택 | int | 페이지당 개수. 기본 `20`, 최대 `100` |

					⚠️ **`status`에 `LOCKED`는 쓸 수 없다.** 이 화면이 쓰지 않는 값이라 지정하면 400이다.

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `content[]` | array | 매니저 목록. 각 항목 구조는 아래 |
					| `page` | int | 현재 페이지(0부터) |
					| `size` | int | 페이지당 개수 |
					| `totalElements` | long | **필터 적용 후** 전체 건수 |
					| `totalPages` | int | 전체 페이지 수 |
					| `statusCounts` | object | 계정 상태별 인원. 화면 상단 `활성 7 · 초대 대기 1 · 정지 0` |

					### statusCounts (object)

					키는 계정 상태, 값은 인원 수입니다. 예: `{"INVITED": 1, "ACTIVE": 7, "INACTIVE": 0}`

					| 키 | 타입 | 설명 |
					| --- | --- | --- |
					| `INVITED` | long | 초대 대기 인원 |
					| `ACTIVE` | long | 활성 인원 |
					| `INACTIVE` | long | 정지 인원 |

					### content[] 각 항목

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `managerId` | UUID | 매니저 사용자 ID |
					| `name` | string? | 이름. 초대만 되고 미활성이면 `null`(화면에서는 `가입 대기`) |
					| `email` | string | 이메일 |
					| `status` | enum | `INVITED` · `ACTIVE` · `INACTIVE` |
					| `cohortId` | UUID? | 담당 기수 ID |
					| `cohortName` | string? | 담당 기수명 |
					| `classroomNames` | string[] | 현재 담당 반 이름 목록. 없으면 `[]`(화면의 `미배정`) |
					| `assignedTraineeCount` | long | 담당 반의 재학(ACTIVE) 교육생 합계. 없으면 `0` |
					| `lastLoginAt` | date-time? | 최근 로그인. 이력이 없으면 `null`(화면에서는 `—`) |
					| `invitedAt` | date-time? | 최초 초대 시각 |
					| `invitedByName` | string? | 초대한 사람 이름 |

					⚠️ **`statusCounts`는 상태·검색 필터와 무관한 모집단**이라 `totalElements`와 다르다.
					상태 칩이 자기 자신을 필터링하면 안 되므로 목록 한 페이지로는 만들 수 없다.
					`INVITED`·`ACTIVE`·`INACTIVE` 세 키가 **항상 모두 있고**, 0명인 상태는 `0`으로 온다 —
					키가 빠지는 것과 0명인 것은 다르다.

					⚠️ **단 `cohortId`는 필터가 아니라 조회 범위라 `statusCounts`에도 똑같이 걸린다.**
					여기만 기관 전체로 세면 칩 합계가 목록 건수와 어긋난다.

					💡 **`cohortId`를 넘기면 그 기수를 담당하는 매니저만 나온다.** 판정 기준은
					**그 기수 반에 활성 배정이 있거나, 그 기수를 대상으로 한 매니저 초대가 있는 것**이다.
					초대만 되고 아직 반이 없는 매니저(화면의 `가입 대기 · 미배정`)도 나와야 해서 OR로 본다.
					이때 `classroomNames`·`assignedTraineeCount`도 그 기수 것만 세므로, 과거 기수를 담당했던
					매니저의 이전 반이 현재 기수 화면에 섞이지 않는다.

					💡 **`cohortId`·`cohortName`은 가장 최근 매니저 초대의 담당 기수다.** 매니저 초대는
					`POST /organizations/{organizationId}/manager-invitations`에서 기수를 필수로 지정하므로
					초대 이력이 있는 매니저라면 항상 값이 있다.

					💡 **`invitedAt`·`invitedByName`은 같은 초대 행에서 읽는다.** 화면 비고가
					`2026-07-24 초대 · 김오퍼레이터`처럼 둘을 한 문장으로 쓰기 때문이다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "매니저 목록 조회 성공"),
			@ApiResponse(responseCode = "400", description = "ROSTER_ROLE_NOT_SUPPORTED role이 MANAGER가 아님 · ACCOUNT_STATUS_FILTER_NOT_SUPPORTED 이 화면이 쓰지 않는 계정 상태(LOCKED) · VALIDATION_FAILED page·size 값이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 오퍼레이터 권한이 아님"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 기관을 확인할 수 없음")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@GetMapping
	public ResponseEntity<ManagerRosterResponse> findMembers(
			@Parameter(description = "조회할 역할. 현재 MANAGER만 지원한다", example = "MANAGER")
			@RequestParam Role role,
			@Parameter(description = "담당 기수로 좁힌다. 생략하면 기관 전체",
					example = "123e4567-e89b-12d3-a456-426614174000")
			@RequestParam(required = false) UUID cohortId,
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
			throw new ApiException(MemberErrorCode.ROSTER_ROLE_NOT_SUPPORTED, "role은 현재 MANAGER만 지원합니다.");
		}
		UUID organizationId = extractOrganizationId(authentication);

		ManagerRosterService.RosterResult result = managerRosterService.findManagers(
				organizationId, cohortId, status, query, sort, PageRequest.of(page, size));

		Page<ManagerRosterRepository.ManagerRosterRow> managerPage = result.page();
		ManagerRosterResponse response = new ManagerRosterResponse(
				managerPage.getContent().stream().map(ManagerRosterResponse.Manager::from).toList(),
				managerPage.getNumber(),
				managerPage.getSize(),
				managerPage.getTotalElements(),
				managerPage.getTotalPages(),
				result.statusCounts()
		);
		return ResponseEntity.ok(response);
	}

	private UUID extractOrganizationId(Authentication authentication) {
		Object details = authentication.getDetails();
		if (!(details instanceof UUID organizationId)) {
			throw new ApiException(AcademicOperationsErrorCode.ORGANIZATION_CONTEXT_MISSING);
		}
		return organizationId;
	}
}
