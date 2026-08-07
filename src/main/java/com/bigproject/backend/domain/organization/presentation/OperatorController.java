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
import org.springframework.http.MediaType;
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
@RequestMapping(value = "/organizations/{organizationId}/operators", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OperatorController {

	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final OperatorService operatorService;

	@Operation(
			operationId = "findOperators",
			summary = "기관 오퍼레이터 계정 목록 조회 | ✅ 사용 가능",
			description = """

					SA-02 ② `이 기관의 오퍼레이터 계정` 표를 채운다.

					## 요청

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 기관 식별자 |

					페이지네이션 없음. 오퍼레이터는 기관당 소수라 전부 내려준다.

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `organizationId` | UUID | 요청한 기관 |
					| `activeCount` | int | **활성** 오퍼레이터 수. `0` 이면 `오퍼레이터 미배정` 상태 |
					| `content[]` | array | 계정 목록. 생성순 |

					**content[] 각 항목**

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `memberId` | UUID | 계정 식별자. 정지·재활성에 쓴다 |
					| `name` | string? | 이름. **초대만 되고 가입 전이면 `null`** → 화면에 `—` |
					| `email` | string | 이메일 |
					| `status` | enum | `ACTIVE`(활성) · `PENDING`(초대됨) · `INACTIVE`(정지) |
					| `invitedAt` | datetime? | 최초 초대 시각. 초대 이력이 없으면 `null` |
					| `lastLoginAt` | datetime? | 최근 로그인. 없으면 `null` → 화면에 `대기 중` |
					| `pendingInvitationTokenId` | UUID? | 대기 중 초대 토큰. **`null` 이 아니면 취소·재발송 가능** |
					| `suspendable` | boolean | `false` 면 정지 버튼을 잠근다(마지막 활성 오퍼레이터) |
					| `invitationDeliveryFailed` | boolean | `true` 면 **메일이 나가지 않았다** → 재발송 유도 |

					## 행별 버튼 노출 기준

					| 버튼 | 조건 |
					|---|---|
					| 정지 | `suspendable === true` |
					| 재활성 | `status === 'INACTIVE'` |
					| 재발송 | `invitationDeliveryFailed === true` |
					| 취소 | `pendingInvitationTokenId !== null` |

					## 상태 배지를 그리는 법

					`status` 와 `invitationDeliveryFailed` **두 값을 조합**한다. 둘 다 "활성화 전"이지만
					화면이 할 말이 다르다.

					| status | invitationDeliveryFailed | 배지 |
					|---|---|---|
					| `ACTIVE` | `false` | 활성 |
					| `PENDING` | `false` | 초대됨 (수락 대기) |
					| `PENDING` | **`true`** | **메일 발송 실패** (목업 case 4·5) |
					| `INACTIVE` | — | 정지 |

					정지된 계정도 목록에 남는다 — 목업: *"퇴사한 계정도 지우지 않고 정지로 남긴다.
					과거 기수의 배정 이력에 이름이 붙어 있고, 지우면 그 이력이 끊긴다."*
					"""
	)
	@GetMapping
	public ResponseEntity<OperatorListResponse> findOperators(@PathVariable UUID organizationId) {
		return ResponseEntity.ok(operatorService.findOperators(organizationId));
	}

	@Operation(
			operationId = "inviteOperator",
			summary = "오퍼레이터 초대 | ⚠️ 사용 보류",
			description = """
					SA-02 ② `오퍼레이터 초대` 모달. 계정 자리를 만들고 초대 메일을 보낸다.

					## 요청

					**경로 변수** — `organizationId` (**필수**, UUID)

					**JSON 본문**

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `email` | **필수** | string | 초대할 이메일. **이 API 의 유일한 입력이다** |

					**헤더 (선택)** — `X-Request-Id: {문자열}` 초대·토큰 발급 추적용. 생략하면 서버가 만든다.

					권한·기수를 고르는 칸이 없는 건 의도한 설계다 — 목업: *"이 화면에서 보내는 초대는
					오퍼레이터 하나뿐이라 초대하는 화면이 곧 역할이다. 기수 배정도 없다 —
					오퍼레이터는 기관 전체를 본다."*

					**이메일 도메인 제한이 없다.** 기관에 `emailDomain` 이 설정돼 있어도 **아무 주소로나**
					(개인 메일 포함) 초대할 수 있다. 오퍼레이터는 기관의 첫 계정이라 초대받는 시점에
					그 기관 메일함을 가질 수 없기 때문이다.

					## 응답 — `201 Created`

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `organizationId` | UUID | 기관 식별자 |
					| `memberId` | UUID | 생성된 계정 자리 |
					| `email` | string | 초대 메일 수신 주소 |
					| `status` | enum | 항상 `PENDING`(목업 `초대됨`) |
					| `invitedAt` | datetime | 초대 원장·최초 토큰 생성 시각 |

					받는 사람은 메일 링크로 AU-02 에서 **이름·비밀번호만 정하면** 활성화된다.

					**초대 직후 목록을 다시 불러야** 새 행이 표에 나타난다. 응답에 목록이 함께 오지 않는다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 404 | 활성 기관이 아님(삭제·미존재) |
					| 409 `ALREADY_INVITED` | 이미 등록되었거나 초대가 진행 중인 이메일 |
					| 502 `INVITE_MAIL_FAILED` | 메일 발송 실패 — **아래 참고** |

					### 502 여도 계정 자리는 남는다

					목업 case 4·5 *"자리는 남기고 링크만 실패"* 대로 동작한다.
					목록을 다시 부르면 그 계정이 `invitationDeliveryFailed=true` 로 나오므로,
					화면은 **그 행에** `초대 메일이 나가지 않았습니다` 안내와 [재발송] 버튼을 붙이면 된다.

					자리를 지우지 않는 이유 — 지우면 같은 주소로 다시 초대했을 때
					**중복 초대인지 재시도인지 구분할 수 없다.**
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
			operationId = "updateOperatorStatus",
			summary = "오퍼레이터 계정 정지 / 재활성 | ✅ 사용 가능",
			description = """
					SA-02 ② 표의 행별 액션 `정지` / `재활성`.

					## 요청

					**경로 변수**

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 기관 식별자 |
					| `memberId` | **필수** | UUID | 목록 응답의 `memberId` |

					**JSON 본문**

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `status` | **필수** | enum | `ACTIVE`(재활성) 또는 `INACTIVE`(정지)만. 그 외는 400 |
					| `reason` | 선택 | string | 변경 사유(예: `퇴사 처리`). 감사 이력에 남는다 |

					`PENDING` 은 지정할 수 없다 — 초대 흐름이 설정하는 값이다.

					## 응답

					**변경 후의 오퍼레이터 목록 전체**를 돌려준다(`GET .../operators` 와 같은 구조).
					`suspendable` 플래그가 함께 갱신되므로 **표를 그대로 다시 그리면 된다** —
					목록을 따로 재조회할 필요가 없다.

					## 마지막 활성 오퍼레이터는 정지할 수 없다

					409 `LAST_OPERATOR`. 목업: *"정지하면 기관에 들어갈 수 있는 사람이 아무도 없어지고,
					기수·명단·매니저를 손댈 방법이 사라진다."*

					화면은 `suspendable=false` 인 행의 정지 버튼을 **미리 잠가** 이 오류를 만나지 않게 한다.

					**기관 자체를 멈추려면** 계정이 아니라 `PUT .../operations/settings` 로 기관 상태를
					`SUSPENDED` 로 바꾼다 — 계정은 남고 로그인만 막힌다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 400 | `status` 누락, 또는 `ACTIVE`·`INACTIVE` 외의 값 |
					| 404 `OPERATOR_NOT_FOUND` | 이 기관의 오퍼레이터가 아님 |
					| 409 `LAST_OPERATOR` | 마지막 활성 오퍼레이터 정지 시도 |
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
			operationId = "cancelInvitation",
			summary = "오퍼레이터 초대 취소 | ⚠️ 사용 보류",
			description = """
					SA-02 ② 표의 `취소` 액션. 아직 수락되지 않은 초대를 무효화한다.

					## 요청

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 기관 식별자 |
					| `tokenId` | **필수** | UUID | 목록 응답의 **`pendingInvitationTokenId`** |

					본문 없음.

					`pendingInvitationTokenId` 가 `null` 인 행은 **취소 버튼을 노출하지 않는다** —
					이미 수락됐거나 취소된 초대라 넘길 토큰이 없다.

					## 응답

					**취소 후의 오퍼레이터 목록 전체**(`GET .../operators` 와 같은 구조).
					표를 그대로 다시 그리면 된다.

					해당 계정은 이렇게 바뀐다.

					| 필드 | 취소 후 |
					|---|---|
					| `status` | `INACTIVE` |
					| `pendingInvitationTokenId` | `null` |
					| `invitationDeliveryFailed` | `false` |

					## 세 가지가 함께 처리된다

					1. **초대 토큰 무효화** — 이미 나간 메일의 링크가 죽는다
					2. **초대 원장을 `CANCELLED` 로 닫기** — 감사·통계에서 발송된 초대로 세지 않는다
					3. **계정 자리는 남기고 `INACTIVE` 로** — 이력 보존
					   (*"퇴사한 계정도 지우지 않고 정지로 남긴다"* 와 같은 처리)

					## 같은 이메일로 다시 초대할 수 있다

					취소된 자리는 활성화된 적이 없으므로, 재초대 시 **새 계정을 만들지 않고 그 자리를 되살린다.**
					`memberId` 가 그대로 유지되어 이력이 한 줄로 이어진다.

					재초대는 이 API 가 아니라 `POST .../operators/invitations` 를 쓴다(재발송이 아니다 —
					취소된 초대는 토큰이 없어 재발송 대상이 아니다).

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 404 `OPERATOR_INVITATION_NOT_FOUND` | 이미 수락·취소된 초대이거나 다른 기관의 토큰 |
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

	@Operation(
			operationId = "resendOperatorInvitation",
			summary = "오퍼레이터 초대 재발송 | ⚠️ 사용 보류",
			description = """
					SA-02 ② case 4·5 의 [재발송] 액션. 초대 메일을 다시 보낸다.

					## 언제 쓰나

					| 상황 | 목록에서의 신호 |
					|---|---|
					| 메일이 나가지 않음 | `invitationDeliveryFailed === true` |
					| 링크가 만료됨 | 상태로는 구분되지 않는다. 사용자가 요청하면 호출 |

					**만료된 초대도 대상이다** — 만료야말로 재발송이 필요한 주된 상황이다.

					## 요청

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 기관 식별자 |
					| `tokenId` | **필수** | UUID | 목록 응답의 **`pendingInvitationTokenId`** |

					**헤더 (선택)** — `X-Request-Id: {문자열}` 추적용. 생략하면 서버가 만든다.

					본문 없음.

					## 응답

					**재발송 후의 오퍼레이터 목록 전체**(`GET .../operators` 와 같은 구조).

					해당 계정은 이렇게 바뀐다.

					| 필드 | 재발송 후 |
					|---|---|
					| `invitationDeliveryFailed` | `false` 로 내려감 |
					| `pendingInvitationTokenId` | **새 토큰 값으로 교체** |
					| `status` | `PENDING` 유지 |

					## 동작

					- **이전 토큰을 무효화하고 새로 발급한다** — 재발송 뒤에도 옛 링크가 살아 있으면
					  유효한 가입 링크가 둘이 된다
					- **초대 원장은 새로 만들지 않는다** — 재발송인지 새 초대인지 구분되고
					  재발송 횟수(`resend_count`)가 정확히 쌓인다

					**쿨다운·횟수 제한은 없다.** 필요하면 화면에서 버튼을 잠시 비활성화하세요.

					## 취소된 초대에는 쓸 수 없다

					취소하면 토큰이 무효화되고 `pendingInvitationTokenId` 가 `null` 이 되어 넘길 값이 없다.
					그 경우는 **재초대**(`POST .../operators/invitations`)를 쓴다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 404 `OPERATOR_INVITATION_NOT_FOUND` | 이미 수락·취소된 초대이거나 다른 기관의 토큰 |
					| 502 `INVITE_MAIL_FAILED` | 재발송도 실패. 자리와 기록은 남으므로 다시 시도할 수 있다 |
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "재발송 성공"),
			@ApiResponse(responseCode = "404", description = "OPERATOR_INVITATION_NOT_FOUND · 이미 수락·취소된 초대"),
			@ApiResponse(responseCode = "502", description = "INVITE_MAIL_FAILED · 재발송도 실패(자리와 기록은 남는다)")
	})
	@PostMapping("/invitations/{tokenId}/resend")
	public ResponseEntity<OperatorListResponse> resendInvitation(
			@PathVariable UUID organizationId,
			@PathVariable UUID tokenId,
			@Parameter(description = "재발송 요청 추적용 식별자이며 생략 시 서버가 생성합니다.", example = "resend-op-001")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
			@Parameter(hidden = true) Authentication authentication
	) {
		return ResponseEntity.ok(
				operatorService.resendInvitation(organizationId, tokenId, authentication.getName(), requestId)
		);
	}
}
