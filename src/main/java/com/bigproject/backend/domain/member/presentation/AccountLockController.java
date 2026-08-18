package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.member.application.AccountLockService;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.member.presentation.dto.LoginLockResponse;
import com.bigproject.backend.domain.member.presentation.dto.UpdateLoginLockRequest;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 운영자의 <b>수동 로그인 차단·해제</b>.
 *
 * <h2>왜 이 API가 필요했나</h2>
 *
 * <p>{@code app_user.login_blocked_until}은 스키마에 있었고 로그인 경로가 <b>읽기까지</b> 했다
 * ({@code AuthService.validateAccount} → 429 {@code LOGIN_TEMPORARILY_BLOCKED}). 그런데 이 값을
 * <b>쓰는 코드가 어디에도 없어서</b> 실제로는 한 번도 발동하지 않는 검사였다 — 세 곳이 {@code NULL}로
 * 초기화만 했다. 계정 탈취가 의심될 때 운영자가 쓸 수 있는 수단이 계정 정지(INACTIVE)뿐이었는데,
 * 그건 되돌리는 절차와 이력이 무거워 "몇 시간만 막고 싶다"에 맞지 않는다.
 *
 * <p>정지와 갈라 두는 이유는 <b>해제 방식이 다르기 때문</b>이다. 정지는 사람이 다시 조작해야 풀리고,
 * 차단은 <b>시각이 지나면 저절로 풀린다</b>. 정의서가 "LOCKED 상태는 쓰지 않고 일시 지연은
 * {@code login_blocked_until}로 표현한다"고 정한 것과 같은 판단이다.
 *
 * <p>경로를 매니저 계정 조작({@code ManagerAccountController})과 같은 자리에 둔다 — 같은 기관 안의
 * 계정 조작이라 화면이 부르는 곳이 같다. 다만 대상이 매니저로 한정되지 않아
 * ({@code users/{userId}}) 교육생·매니저·오퍼레이터에 모두 걸 수 있다.
 */
@Tag(name = "Member")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping(value = "/members/organizations/{organizationId}", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AccountLockController {

	private static final String REQUEST_ID_HEADER = "X-Request-Id";
	private static final String SUPER_ADMIN_AUTHORITY = "ROLE_SUPER_ADMIN";

	private final AccountLockService accountLockService;

	@Operation(
			operationId = "updateLoginLock",
			summary = "계정 로그인 차단 / 해제 | ✅ 사용 가능",
			description = """
					계정 탈취가 의심되거나 확인이 필요할 때 **그 계정의 로그인을 일정 시각까지 막는다.**

					## 계정 정지와 무엇이 다른가

					| | 계정 정지 (`PATCH .../managers/{managerId}/status`) | 로그인 차단 (이 API) |
					|---|---|---|
					| 표현 | `status = INACTIVE` | `login_blocked_until` **시각** |
					| 해제 | 사람이 다시 조작해야 한다 | **시각이 지나면 저절로 풀린다** |
					| 이력 | 계정 원장에 정지 사유가 박힌다 | 감사 로그에만 남는다 |
					| 쓰는 자리 | 퇴사·계약 종료처럼 되돌리지 않을 변화 | **지금 몇 시간** 막아야 할 때 |

					정지밖에 없으면 "일단 막자"에 정지를 쓰게 되고, 그러면 퇴사와 같은 흔적이 계정에 남는다.

					## 요청

					**경로 변수**

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 기관 식별자. 오퍼레이터는 자기 기관만 허용된다(403) |
					| `userId` | **필수** | UUID | 대상 계정. **역할을 가리지 않는다** — 교육생·매니저·오퍼레이터 모두 가능 |

					**JSON 본문**

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `locked` | **필수** | boolean | `true` 차단 / `false` 해제 |
					| `lockedUntil` | 조건부 | datetime | 차단이 끝나는 시각. **`locked=true`면 필수**, `false`면 무시된다. 과거 시각은 400 |
					| `reason` | 선택 | string | 조치 사유(최대 500자). 감사 로그에 남는다 |

					**해제 의사를 명시적으로 받는다.** 시각만 받고 "비어 있으면 해제"로 정하면
					본문을 빠뜨린 요청이 조용히 차단을 푼다 — JSON에서 *키를 안 보냄*과 *null을 보냄*은
					서버에 같은 값으로 도착해 구분할 수 없기 때문이다.
					보안 조치를 되돌리는 동작이 실수로 도달할 수 있는 기본값이면 안 된다.

					**기한 없는 차단은 만들 수 없다**(400 `LOCK_UNTIL_REQUIRED`).
					시각이 지나면 저절로 풀린다는 것이 이 방식의 요점이고, 종료 시각이 비면
					아무도 풀어 주지 않는 한 영구 차단이 된다 — 그건 계정 정지가 할 일이다.

					**헤더 (선택)** — `X-Request-Id: {문자열}` 감사 추적용. 생략하면 서버가 만든다.

					## 차단은 세션까지 끊는다

					`login_blocked_until`은 **새 로그인만** 막는다. 이미 로그인해 둔 창은 리프레시 토큰으로
					계속 연장되므로, 토큰을 함께 끊지 않으면 *"차단했는데 그 사람은 계속 쓰고 있는"* 상태가 된다.
					탈취 의심 상황에서 정확히 막아야 하는 것이 그 세션이다.

					그래서 차단 시 **그 계정의 활성 리프레시 토큰을 같은 트랜잭션에서 전부 폐기**하고
					(`revoked_reason = ADMIN_REVOKED`, `revoked_by` = 조치자) 끊은 개수를 `revokedSessionCount`로 돌려준다.
					대상은 다음 요청에서 401을 받고 로그인 화면으로 가며, 거기서 다시 429 `LOGIN_TEMPORARILY_BLOCKED`를 만난다.

					**해제할 때는 토큰을 건드리지 않는다** — 이미 끊어진 세션은 되살릴 수도, 되살릴 이유도 없다.
					대신 `failed_login_count`를 0으로 되돌린다. 카운터를 남겨 두면 풀어 준 계정이
					다음 실패 한 번에 다시 막혀 "해제했다"는 말이 사실이 아니게 된다.

					## 사유는 감사 로그에 남는다

					`app_user`에는 차단 사유 컬럼이 없고 이 하나를 위해 테이블을 늘리지 않는다
					(`inactivated_reason`은 계정 정지용이라 여기 쓰면 정지와 차단이 뒤섞인다).
					`audit_log`에 `actor_type=USER`로 **누가·언제·누구를·왜** 막았는지 남으며,
					사유는 `after_snapshot`에 들어간다. 이력이 없으면 나중에 남는 것은 시각 하나뿐이고,
					그 값은 연속 실패 자동 차단과 구분되지 않는다.

					## 자기 계정은 차단할 수 없다

					400 `LOCK_SELF_NOT_ALLOWED`. 기관에 오퍼레이터가 한 명뿐인데 자기를 막으면
					**풀어 줄 사람이 없어진다**(해제도 이 권한이 필요하다).
					마지막 활성 오퍼레이터를 정지하지 못하게 막는 `LAST_OPERATOR`와 같은 종류의 방어다.
					**해제는 자기 계정에도 허용된다** — 아무도 잠기게 하지 않기 때문이다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "로그인 차단 설정 또는 해제 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED locked 누락·lockedUntil이 과거·reason이 500자 초과 · LOCK_UNTIL_REQUIRED 차단인데 종료 시각이 없음 · LOCK_SELF_NOT_ALLOWED 자기 계정 차단 시도"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 오퍼레이터·슈퍼어드민 권한이 아님 · INVITE_CROSS_ORGANIZATION 다른 기관을 지정함"),
			@ApiResponse(responseCode = "404", description = "LOCK_TARGET_NOT_FOUND 이 기관의 계정이 아님(다른 기관·삭제된 계정도 여기로 묶는다)"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 기관을 확인할 수 없음")
	})
	@PreAuthorize("hasAnyRole('OPERATOR', 'SUPER_ADMIN')")
	@PatchMapping("/users/{userId}/login-lock")
	public ResponseEntity<LoginLockResponse> updateLoginLock(
			@Parameter(description = "기관 ID. 오퍼레이터는 자기 소속 기관만 허용됩니다.",
					example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID organizationId,
			@Parameter(description = "로그인을 차단·해제할 계정의 사용자 ID",
					example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID userId,
			@Valid @RequestBody UpdateLoginLockRequest request,
			@Parameter(description = "감사 추적용 식별자이며 생략 시 서버가 생성합니다.", example = "lock-op-001")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
			@Parameter(hidden = true) Authentication authentication
	) {
		assertOwnOrganization(organizationId, authentication);
		AccountLockService.LockResult result = accountLockService.updateLoginLock(
				organizationId,
				userId,
				Boolean.TRUE.equals(request.locked()),
				request.lockedUntil(),
				request.reason(),
				requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId
		);
		return ResponseEntity.ok(LoginLockResponse.from(result));
	}

	/**
	 * 경로의 기관과 토큰의 기관이 같은지 본다 — {@code ManagerAccountController}가 세우는 것과 같은 경계다.
	 *
	 * <p><b>슈퍼어드민은 예외다.</b> 기관에 소속되지 않아({@code org_id IS NULL}) 비교할 값이 없고,
	 * 애초에 기관 경계를 넘어 복구·부트스트랩을 하는 역할이다. 여기서 걸러 버리면 "기관의 오퍼레이터가
	 * 전부 잠긴" 상황을 풀 수 있는 사람이 아무도 없어진다.
	 */
	private void assertOwnOrganization(UUID organizationId, Authentication authentication) {
		boolean superAdmin = authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.anyMatch(SUPER_ADMIN_AUTHORITY::equals);
		if (superAdmin) {
			return;
		}
		Object details = authentication.getDetails();
		if (!(details instanceof UUID callerOrganizationId)) {
			throw new ApiException(AcademicOperationsErrorCode.ORGANIZATION_CONTEXT_MISSING);
		}
		if (!callerOrganizationId.equals(organizationId)) {
			throw new ApiException(
					MemberErrorCode.INVITE_CROSS_ORGANIZATION,
					"다른 기관의 계정은 조작할 수 없습니다.");
		}
	}
}
