package com.bigproject.backend.domain.organization.presentation;

import com.bigproject.backend.domain.organization.application.OperatorService;
import com.bigproject.backend.domain.organization.presentation.dto.InviteOperatorRequest;
import com.bigproject.backend.domain.organization.presentation.dto.InviteOperatorResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OperatorListResponse;
import com.bigproject.backend.domain.organization.presentation.dto.UpdateOperatorStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 기관 상세 &gt; 오퍼레이터 탭(목업 SA-02 ②).
 *
 * <p>목업 경고문 그대로: "슈퍼어드민이 넣는 계정은 <b>오퍼레이터뿐</b>이다(부트스트랩·복구).
 * 반 담당 매니저 초대·반 배정·정지는 오퍼레이터가 기관 안에서(OP-06) 한다 — 테넌트 경계."
 */
// 태그 이름을 OrganizationController와 똑같이 맞춰 Swagger에서 한 그룹으로 묶는다.
// (경로가 /organizations 하위라 화면상 나뉘어 보일 이유가 없다. description은 OrganizationController 쪽 하나만 둔다 —
//  같은 태그에 description이 둘이면 springdoc이 어느 쪽을 쓸지 보장되지 않는다.)
@Tag(name = "Organization")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RestController
@RequestMapping("/organizations/{organizationId}/operators")
@RequiredArgsConstructor
public class OperatorController {

	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final OperatorService operatorService;

	@Operation(
			summary = "기관 오퍼레이터 계정 목록 조회",
			description = """
					기관에 소속된 오퍼레이터 계정을 조회한다. 목업 SA-02 ② `이 기관의 오퍼레이터 계정` 표에 대응한다.

					**응답**
					- 계정별 이름·이메일·상태·초대일·최근 로그인
					- activeCount: 활성 오퍼레이터 수. 0이면 기관이 아직 시작되지 않은 상태(`오퍼레이터 미배정`)
					- pendingInvitationTokenId: null이 아니면 아직 수락되지 않은 초대이므로 `취소` 액션을 노출한다
					- suspendable: false면 정지 버튼을 잠근다(마지막 활성 오퍼레이터)

					정지된 계정도 목록에 남는다 — 목업 주석: "퇴사한 계정도 지우지 않고 정지로 남긴다. \
					과거 기수의 배정 이력에 이름이 붙어 있고, 지우면 그 이력이 끊긴다."

					**아직 채워지지 않는 값** — 목업의 `메일 발송 실패` 상태. \
					app_user.status CHECK에 MAIL_FAILED가 없고, 현재 초대 흐름은 메일 발송 실패 시 계정 자리를 \
					남기지 않고 롤백한다.
					"""
	)
	@GetMapping
	public ResponseEntity<OperatorListResponse> findOperators(@PathVariable UUID organizationId) {
		return ResponseEntity.ok(operatorService.findOperators(organizationId));
	}

	@Operation(
			summary = "오퍼레이터 초대",
			description = """
					기관에 오퍼레이터를 초대한다. 목업 SA-02 ② `오퍼레이터 초대` 모달에 대응한다.

					**요청**
					- email (필수): 초대할 이메일. **이 API의 유일한 입력이다.**

					권한·기수를 고르는 필드가 없는 건 의도한 설계다 — 목업 주석: "권한을 고르는 칸이 없다 — \
					이 화면에서 보내는 초대는 오퍼레이터 하나뿐이라 초대하는 화면이 곧 역할이다. \
					기수 배정도 없다 — 오퍼레이터는 기관 전체를 본다."

					**응답**
					- 생성된 계정 자리(memberId)와 PENDING 상태. 받는 사람은 초대 메일 링크로 AU-02에서 \
					이름·비밀번호만 정하면 활성화된다.

					**이메일 도메인 제한 없음** — 기관에 emailDomain이 설정돼 있어도 \
					오퍼레이터는 **아무 주소로나**(개인 이메일 포함) 초대할 수 있다. 오퍼레이터는 기관의 첫 계정이라 \
					초대받는 시점에 그 기관 메일함을 가질 수 없기 때문이다. 도메인 제한은 오퍼레이터가 \
					매니저·교육생을 초대하는 경로(OP-06)에 적용된다.

					**오류**
					- 409: 이미 등록되었거나 초대된 이메일
					- 502: 초대 메일 발송 실패. ⚠ 현재는 이때 **계정 자리도 롤백된다** — \
					목업이 요구하는 "자리는 남기고 링크만 실패" 동작은 별도 작업으로 분리했다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "오퍼레이터 계정 생성 및 초대 발송 성공"),
			@ApiResponse(responseCode = "404", description = "활성 기관을 찾을 수 없음"),
			@ApiResponse(responseCode = "409", description = "ALREADY_INVITED · 이미 등록되었거나 초대된 이메일"),
			@ApiResponse(responseCode = "502", description = "INVITE_MAIL_FAILED · 초대 메일 발송 실패")
	})
	@PostMapping("/invitations")
	public ResponseEntity<InviteOperatorResponse> inviteOperator(
			@PathVariable UUID organizationId,
			@Valid @RequestBody InviteOperatorRequest request,
			@Parameter(description = "초대 요청 추적용 식별자이며 생략 시 서버가 생성합니다.", example = "invite-op-001")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
			@Parameter(hidden = true) Authentication authentication
	) {
		InviteOperatorResponse response = operatorService.inviteOperator(
				organizationId, request, authentication.getName(), requestId
		);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@Operation(
			summary = "오퍼레이터 계정 정지 / 재활성",
			description = """
					오퍼레이터 계정 상태를 변경한다. 목업 SA-02 ② 표의 행별 액션 `정지` / `재활성`에 대응한다.

					**요청**
					- status (필수): ACTIVE(재활성) 또는 INACTIVE(정지)만 지정 가능
					- reason (선택): 변경 사유

					**마지막 오퍼레이터 정지는 차단된다(409).** 목업 주석: "정지하면 기관에 들어갈 수 있는 사람이 \
					아무도 없어지고, 기수·명단·매니저를 손댈 방법이 사라진다." 기관 자체를 멈추려면 계정이 아니라 \
					`PUT /organizations/{organizationId}/operations/settings`로 기관 상태를 SUSPENDED로 바꿔야 한다 \
					— 계정은 남고 로그인만 막힌다.

					**응답**
					- 변경 후의 오퍼레이터 목록 전체(suspendable 플래그가 함께 갱신되므로 표를 그대로 다시 그릴 수 있다)
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "상태 변경 성공"),
			@ApiResponse(responseCode = "400", description = "ACTIVE/INACTIVE 외의 상태를 지정함"),
			@ApiResponse(responseCode = "404", description = "OPERATOR_NOT_FOUND"),
			@ApiResponse(responseCode = "409", description = "LAST_OPERATOR · 마지막 활성 오퍼레이터는 정지할 수 없음")
	})
	@PatchMapping("/{memberId}/status")
	public ResponseEntity<OperatorListResponse> updateOperatorStatus(
			@PathVariable UUID organizationId,
			@PathVariable UUID memberId,
			@Valid @RequestBody UpdateOperatorStatusRequest request
	) {
		return ResponseEntity.ok(operatorService.updateOperatorStatus(organizationId, memberId, request));
	}

	@Operation(
			summary = "오퍼레이터 초대 취소",
			description = """
					아직 수락되지 않은 초대를 취소한다. 목업 SA-02 ② 표의 `취소` 액션에 대응한다.

					**요청**
					- tokenId (경로): 목록 응답의 pendingInvitationTokenId

					초대 토큰을 무효화하고, 계정 자리는 남긴 채 INACTIVE로 내린다 \
					(이력 보존 — 목업의 "퇴사한 계정도 지우지 않고 정지로 남긴다"와 같은 처리).

					⚠ app_user의 이메일 유니크 제약(uq_app_user_email)이 부분 인덱스가 아니라서, \
					취소한 뒤 **같은 이메일로 다시 초대하면 409가 발생한다.** 재초대를 허용하려면 이 제약을 \
					`WHERE deleted_at IS NULL` 부분 유니크로 바꾸는 DDL 변경이 필요하다.

					**응답**
					- 취소 후의 오퍼레이터 목록 전체
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "초대 취소 성공"),
			@ApiResponse(responseCode = "404", description = "OPERATOR_INVITATION_NOT_FOUND · 이미 수락·취소된 초대")
	})
	@DeleteMapping("/invitations/{tokenId}")
	public ResponseEntity<OperatorListResponse> cancelInvitation(
			@PathVariable UUID organizationId,
			@PathVariable UUID tokenId
	) {
		return ResponseEntity.ok(operatorService.cancelInvitation(organizationId, tokenId));
	}
}
