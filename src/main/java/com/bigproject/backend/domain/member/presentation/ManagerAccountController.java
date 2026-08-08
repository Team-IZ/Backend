package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.member.application.ManagerAccountService;
import com.bigproject.backend.domain.member.domain.ManagerRosterRepository;
import com.bigproject.backend.domain.member.presentation.dto.ManagerRosterResponse;
import com.bigproject.backend.domain.member.presentation.dto.ReplaceManagerClassroomsRequest;
import com.bigproject.backend.domain.member.presentation.dto.UpdateManagerStatusRequest;
import com.bigproject.backend.global.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * OP-06 `매니저` 탭 표의 행별 액션 — 정지·재활성 / 초대 재발송 / 초대 취소(9차 R7).
 *
 * <p>오퍼레이터 쪽 SA-02 ②에 이미 있던 세 조작을 매니저에도 같은 모양으로 둔다. 목록
 * ({@code GET /managers})이 {@code status}를 이미 내려주고 있었는데 그 값을 바꿀 곳이 없어,
 * 화면이 상태를 <b>보여 주기만 하고 아무것도 못 하는</b> 상태였다.
 *
 * <p>목록 조회는 {@code ManagerController}(`/managers`)에 있고 이 컨트롤러는 조작만 담당한다.
 * 경로가 {@code /members/organizations/{organizationId}/…}인 것은 매니저 초대
 * ({@code POST …/manager-invitations})와 같은 자리에 두기 위해서다.
 */
@Tag(name = "Member")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping(value = "/members/organizations/{organizationId}", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ManagerAccountController {

	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final ManagerAccountService managerAccountService;

	@Operation(
			operationId = "updateManagerStatus",
			summary = "매니저 계정 정지 / 재활성 | ✅ 사용 가능",
			description = """
					OP-06 `매니저` 탭 표의 행별 액션 `정지` / `재활성`.

					## 요청

					**경로 변수**

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 기관 식별자. 호출자의 소속 기관과 다르면 403 |
					| `managerId` | **필수** | UUID | 목록 응답의 `managerId` |

					**JSON 본문**

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `status` | **필수** | enum | `ACTIVE`(재활성) 또는 `INACTIVE`(정지)만. 그 외는 400 |
					| `reason` | 선택 | string | 변경 사유(예: `퇴사 처리`). 감사 이력에 남는다 |

					`INVITED`는 초대 흐름이 설정하는 값이라 지정할 수 없다.
					(`LOCKED`는 9차 Q3-②로 `AccountStatus`에서 제거했다.)

					## 정지하면 담당 반이 **서버에서 함께 풀린다**

					상태만 바꾸면 그만둔 사람이 반을 붙들고 있어 `담당 매니저 없음` 경고
					(`ClassroomResponse.managerAssignmentRequired`)에 잡히지 않는다 — 그 반 학생의 면담·독촉을
					아무도 처리하지 않는데 대시보드의 `조치 필요`에도 올라오지 않는다.
					**경고 체계가 막으려던 상황을 정지 기능이 만드는 셈**이라 서버가 같은 트랜잭션에서 함께 푼다.

					화면이 반마다 `PATCH …/managers`를 따로 부르면 중간에 실패했을 때 절반만 풀린다.
					해제된 배정은 지워지지 않고 사유 `ACCOUNT_INACTIVATED`로 이력에 남는다.

					⚠️ **재활성해도 반은 돌려주지 않는다.** 그 사이 다른 사람이 맡았을 수 있고,
					되돌릴 것은 배정이지 상태가 아니다. 담당은 `PATCH …/classrooms/{classroomId}/managers`로 다시 정한다.

					## 응답 (200)

					**변경 후의 그 매니저 한 행**(`GET /managers` 응답 `content[]`의 항목과 같은 구조).
					`status`·`classroomNames`·`assignedTraineeCount`·`suspendable`이 갱신되어 오므로
					그 행만 갈아 끼우면 된다.

					⚠️ **`suspendable`은 다른 행에서도 바뀔 수 있다.** 기관의 활성 매니저 수로 판정하는 값이라
					한 명을 정지하면 남은 한 명이 `suspendable=false`가 된다. 정지·재활성 뒤에는 목록을 다시 읽는다.

					## 마지막 활성 매니저는 정지할 수 없다

					409 `LAST_MANAGER`. 정지하면 담당 매니저가 한 명도 남지 않는다 —
					오퍼레이터의 `LAST_OPERATOR`와 같은 규칙이다.
					화면은 `suspendable=false`인 행의 정지 버튼을 **미리 잠가** 이 오류를 만나지 않게 한다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "상태 변경 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED ACTIVE/INACTIVE 외의 상태를 지정함"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 오퍼레이터 권한이 아님 · INVITE_CROSS_ORGANIZATION 다른 기관을 지정함"),
			@ApiResponse(responseCode = "404", description = "MANAGER_NOT_FOUND 이 기관의 매니저가 아님"),
			@ApiResponse(responseCode = "409", description = "LAST_MANAGER 마지막 활성 매니저는 정지할 수 없음"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 기관을 확인할 수 없음")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@PatchMapping("/managers/{managerId}/status")
	public ResponseEntity<ManagerRosterResponse.Manager> updateManagerStatus(
			@Parameter(description = "기관 ID. 호출자인 오퍼레이터의 소속 기관만 허용됩니다.",
					example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID organizationId,
			@Parameter(description = "상태를 바꿀 매니저의 사용자 ID(목록 응답의 managerId)",
					example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID managerId,
			@Valid @RequestBody UpdateManagerStatusRequest request,
			@Parameter(hidden = true) Authentication authentication
	) {
		assertOwnOrganization(organizationId, authentication);
		ManagerRosterRepository.ManagerRosterRow row = managerAccountService.updateManagerStatus(
				organizationId, managerId, request.status(), request.reason());
		return ResponseEntity.ok(toResponse(organizationId, row));
	}

	@Operation(
			operationId = "replaceManagerClassrooms",
			summary = "매니저 담당 반 전체 교체 | ✅ 사용 가능",
			description = """
					매니저 **한 명**의 담당 반을 한 번에 저장한다(9차 Q3-①).
					보낸 목록이 그대로 최종 상태가 되며, **전부 반영되거나 전부 반영되지 않는다.**

					## 왜 이 방향이 따로 있나

					`PATCH /cohorts/{cohortId}/classrooms/{classroomId}/managers`는 **반 하나 = 매니저 여럿**이라,
					매니저 상세 모달에서 담당 반 셋을 체크하면 `PATCH`를 반 수만큼 나눠 불러야 한다 —
					담당을 뗀 반까지 세면 더 늘어나고, **중간에 하나가 실패하면 절반만 반영된 상태**로 남는다.
					되돌릴 방법이 없다.

					이 API는 한 트랜잭션이라 그 상태가 생기지 않는다.

					💡 **두 방향이 같은 사실을 두 곳에서 갱신하는 것이 아니다.** 갱신 대상은 `manager_assignment`
					한 테이블이고, 각자 **자기 축의 현재 상태를 통째로 다시 쓴다**(둘 다 전체 교체).
					반 기준 모달은 반 기준 API를, 매니저 기준 모달은 이 API를 쓰면 된다.

					## 요청

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 기관 식별자. 호출자의 소속 기관과 다르면 403 |
					| `managerId` | **필수** | UUID | 목록 응답의 `managerId` |
					| `classroomIds` | **필수** | UUID[] | 최종 담당 반 목록. 중복은 서버가 제거한다. **빈 배열이면 전체 해제** |

					`classroomIds`에 `null`은 허용하지 않는다 — 빈 배열(전체 해제)과 구분되지 않기 때문이다.

					**반은 여러 기수에 걸쳐도 된다.** 이 경로는 기수를 받지 않으므로, 매니저가 두 기수의 반을
					동시에 담당하는 편성도 한 번에 저장된다.

					## 응답 (200)

					**변경 후의 그 매니저 한 행**(`GET /managers` 응답 `content[]`의 항목과 같은 구조).
					`classroomNames`·`assignedTraineeCount`가 갱신되어 오므로 그 행만 갈아 끼우면 된다.

					## 동작

					- 지금 담당 중인 배정을 **전부 해제**(사유 `MANUAL_UNASSIGN`)한 뒤 요청받은 반으로 새로 만든다.
					  계속 담당할 반도 일단 놓고 다시 만든다 — "유지되는 것만 남긴다"로 하면 유지 판정이 한 벌 더 생긴다
					- 해제된 배정은 지워지지 않고 이력으로 남는다
					- **넘어온 반이 하나라도 이 기관 것이 아니면 아무것도 바꾸지 않고 404**다.
					  일부만 배정하면 화면이 무엇이 반영됐는지 알 수 없다
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "담당 반 교체 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED classroomIds가 누락됨"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 오퍼레이터 권한이 아님 · INVITE_CROSS_ORGANIZATION 다른 기관을 지정함"),
			@ApiResponse(responseCode = "404", description = "MANAGER_NOT_FOUND 이 기관의 매니저가 아님 · CLASSROOM_NOT_FOUND 이 기관의 반이 아닌 ID가 포함됨(전체 거부)"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 기관을 확인할 수 없음")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@PutMapping("/managers/{managerId}/classrooms")
	public ResponseEntity<ManagerRosterResponse.Manager> replaceManagerClassrooms(
			@Parameter(description = "기관 ID. 호출자인 오퍼레이터의 소속 기관만 허용됩니다.",
					example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID organizationId,
			@Parameter(description = "담당 반을 바꿀 매니저의 사용자 ID(목록 응답의 managerId)",
					example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID managerId,
			@Valid @RequestBody ReplaceManagerClassroomsRequest request,
			@Parameter(hidden = true) Authentication authentication
	) {
		assertOwnOrganization(organizationId, authentication);
		ManagerRosterRepository.ManagerRosterRow row = managerAccountService.replaceClassrooms(
				organizationId, managerId, request.classroomIds());
		return ResponseEntity.ok(toResponse(organizationId, row));
	}

	@Operation(
			operationId = "resendManagerInvitation",
			summary = "매니저 초대 재발송 | ✅ 사용 가능",
			description = """
					OP-06 `매니저` 탭의 `재발송` 액션. 초대 메일을 다시 보낸다.

					## 언제 쓰나

					메일이 도착하지 않았거나 링크가 만료됐을 때다. **만료된 초대도 대상이다** —
					만료야말로 재발송이 필요한 주된 상황이다.

					## 요청

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 기관 식별자 |
					| `tokenId` | **필수** | UUID | 목록 응답의 **`pendingInvitationTokenId`** |

					**헤더 (선택)** — `X-Request-Id: {문자열}` 추적용. 생략하면 서버가 만든다. 본문 없음.

					`pendingInvitationTokenId`가 `null`인 행은 **재발송 버튼을 노출하지 않는다** —
					이미 수락됐거나 취소된 초대라 넘길 토큰이 없다.

					## 동작

					- **이전 토큰을 무효화하고 새로 발급한다** — 재발송 뒤에도 옛 링크가 살아 있으면
					  유효한 가입 링크가 둘이 된다
					- **초대 원장은 새로 만들지 않는다** — 재발송 횟수(`resend_count`)가 정확히 쌓인다

					**쿨다운·횟수 제한은 없다.** 필요하면 화면에서 버튼을 잠시 비활성화하세요.

					## 응답 (200)

					**재발송 후의 그 매니저 한 행.** `pendingInvitationTokenId`가 **새 토큰 값으로 교체**되고
					`status`는 `INVITED`로 유지된다.

					## 교육생용 `POST /auth/invitations/resend`와 다르다

					그쪽은 **받는 사람용**이라 인증 없이 부르고 계정 존재 여부를 숨기려 항상 같은 202를 준다 —
					오퍼레이터가 눌러도 나갔는지 알 수 없다. 이 API는 **운영자용**이라 토큰을 지목하고
					실패하면 실패로 답한다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "재발송 성공"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 오퍼레이터 권한이 아님 · INVITE_CROSS_ORGANIZATION 다른 기관의 초대"),
			@ApiResponse(responseCode = "404", description = "MANAGER_INVITATION_NOT_FOUND 이미 수락·취소된 초대이거나 다른 기관·다른 역할의 토큰"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 기관을 확인할 수 없음"),
			@ApiResponse(responseCode = "502", description = "INVITE_MAIL_FAILED 재발송도 실패(자리와 기록은 남으므로 다시 시도할 수 있다)")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@PostMapping("/manager-invitations/{tokenId}/resend")
	public ResponseEntity<ManagerRosterResponse.Manager> resendManagerInvitation(
			@Parameter(description = "기관 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID organizationId,
			@Parameter(description = "목록 응답의 pendingInvitationTokenId",
					example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID tokenId,
			@Parameter(description = "재발송 요청 추적용 식별자이며 생략 시 서버가 생성합니다.", example = "resend-mgr-001")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
			@Parameter(hidden = true) Authentication authentication
	) {
		assertOwnOrganization(organizationId, authentication);
		ManagerRosterRepository.ManagerRosterRow row = managerAccountService.resendInvitation(
				organizationId, tokenId, authentication.getName(), requestId);
		return ResponseEntity.ok(toResponse(organizationId, row));
	}

	@Operation(
			operationId = "cancelManagerInvitation",
			summary = "매니저 초대 취소 | ✅ 사용 가능",
			description = """
					OP-06 `매니저` 탭의 `취소` 액션. 아직 수락되지 않은 초대를 무효화한다.
					오타 난 이메일로 보낸 초대가 목록에 `초대 대기`로 영원히 쌓이는 것을 막는다.

					## 요청

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 기관 식별자 |
					| `tokenId` | **필수** | UUID | 목록 응답의 **`pendingInvitationTokenId`** |

					본문 없음. `pendingInvitationTokenId`가 `null`인 행은 취소 버튼을 노출하지 않는다.

					## 세 가지가 함께 처리된다

					1. **초대 토큰 무효화** — 이미 나간 메일의 링크가 죽는다
					2. **초대 원장을 `CANCELLED`로 닫기** — 감사·통계에서 발송된 초대로 세지 않는다
					3. **계정 자리는 남기고 `INACTIVE`로** — 이력 보존

					## 응답 (200)

					**취소 후의 그 매니저 한 행.** `status`는 `INACTIVE`, `pendingInvitationTokenId`는 `null`이 된다.

					## 같은 이메일로 다시 초대할 수 있다

					취소된 자리는 활성화된 적이 없으므로, 재초대 시 **새 계정을 만들지 않고 그 자리를 되살린다** —
					`managerId`가 그대로 유지되어 이력이 한 줄로 이어진다.
					재초대는 이 API가 아니라 `POST …/manager-invitations`를 쓴다(재발송이 아니다 —
					취소된 초대는 토큰이 없어 재발송 대상이 아니다).
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "초대 취소 성공"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 오퍼레이터 권한이 아님 · INVITE_CROSS_ORGANIZATION 다른 기관의 초대"),
			@ApiResponse(responseCode = "404", description = "MANAGER_INVITATION_NOT_FOUND 이미 수락·취소된 초대이거나 다른 기관·다른 역할의 토큰"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 기관을 확인할 수 없음")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@DeleteMapping("/manager-invitations/{tokenId}")
	public ResponseEntity<ManagerRosterResponse.Manager> cancelManagerInvitation(
			@Parameter(description = "기관 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID organizationId,
			@Parameter(description = "목록 응답의 pendingInvitationTokenId",
					example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID tokenId,
			@Parameter(hidden = true) Authentication authentication
	) {
		assertOwnOrganization(organizationId, authentication);
		ManagerRosterRepository.ManagerRosterRow row = managerAccountService.cancelInvitation(organizationId, tokenId);
		return ResponseEntity.ok(toResponse(organizationId, row));
	}

	/**
	 * {@code suspendable}은 기관 전체의 활성 매니저 수로 판정하는 값이라, 조작이 끝난 <b>뒤에</b> 다시 센다.
	 * 목록 응답이 같은 기준으로 그리므로 한 행만 갈아 끼워도 표가 어긋나지 않는다.
	 */
	private ManagerRosterResponse.Manager toResponse(UUID organizationId, ManagerRosterRepository.ManagerRosterRow row) {
		return ManagerRosterResponse.Manager.from(row, managerAccountService.countActiveManagers(organizationId));
	}

	/**
	 * 경로의 기관과 토큰의 기관이 같은지 본다. 다른 기관 ID를 넣어 남의 매니저를 정지하는 경로를 막는다 —
	 * 매니저 초대({@code MemberInvitationService})가 세우는 것과 같은 경계다.
	 */
	private void assertOwnOrganization(UUID organizationId, Authentication authentication) {
		Object details = authentication.getDetails();
		if (!(details instanceof UUID callerOrganizationId)) {
			throw new ApiException(AcademicOperationsErrorCode.ORGANIZATION_CONTEXT_MISSING);
		}
		if (!callerOrganizationId.equals(organizationId)) {
			throw new ApiException(
					com.bigproject.backend.domain.member.domain.MemberErrorCode.INVITE_CROSS_ORGANIZATION,
					"다른 기관의 매니저는 조작할 수 없습니다.");
		}
	}
}
