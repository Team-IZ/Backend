package com.bigproject.backend.domain.auth.presentation;

import com.bigproject.backend.domain.auth.application.AccountActivationService;
import com.bigproject.backend.domain.auth.application.AuthService;
import com.bigproject.backend.domain.auth.application.InvitationResendService;
import com.bigproject.backend.domain.auth.application.InvitationResolveService;
import com.bigproject.backend.domain.auth.application.LoginResult;
import com.bigproject.backend.domain.auth.application.LoginOriginResolver;
import com.bigproject.backend.domain.auth.application.PasswordResetService;
import com.bigproject.backend.domain.auth.domain.TokenRequestMetadata;
import com.bigproject.backend.domain.auth.presentation.dto.ActivateAccountResponse;
import com.bigproject.backend.domain.auth.presentation.dto.InvitationResendRequest;
import com.bigproject.backend.domain.auth.presentation.dto.InvitationResendResponse;
import com.bigproject.backend.domain.auth.presentation.dto.InvitationResolveRequest;
import com.bigproject.backend.domain.auth.presentation.dto.InvitationResolveResponse;
import com.bigproject.backend.domain.auth.presentation.dto.LoginRequest;
import com.bigproject.backend.domain.auth.presentation.dto.LoginResponse;
import com.bigproject.backend.domain.auth.presentation.dto.ManagerSignupRequest;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetConfirmationRequest;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetConfirmationResponse;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetRequest;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetRequestResponse;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetValidationRequest;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetValidationResponse;
import com.bigproject.backend.domain.auth.presentation.dto.RefreshTokenResponse;
import com.bigproject.backend.domain.auth.presentation.dto.TraineeActivationRequest;
import com.bigproject.backend.global.security.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Auth", description = "로그인, Access/Refresh Token 재발급·세션 폐기, 비밀번호 재설정, 초대 계정 활성화 API")
@RestController
@RequestMapping(value = "/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AuthController {
	public static final String SWAGGER_CLIENT_ORIGIN_HEADER = "X-Swagger-Client-Origin";
	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final AuthService authService;
	private final AccountActivationService accountActivationService;
	private final InvitationResolveService invitationResolveService;
	private final InvitationResendService invitationResendService;
	private final RefreshTokenCookieManager refreshTokenCookieManager;
	private final LoginOriginResolver loginOriginResolver;
	private final PasswordResetService passwordResetService;
	private final ClientIpResolver clientIpResolver;

	@Operation(
			operationId = "requestPasswordReset",
			summary = "비밀번호 재설정 안내 요청 | ✅ 사용 가능",
			description = """
					비밀번호 재설정 안내 메일 발송을 요청한다. 인증 없이 호출한다.

					**요청**
					- email (필수, 최대 320자): 안내를 받을 이메일

					**응답 (202)**
					- message: 계정 존재 여부와 무관하게 **항상 같은 문구**를 반환한다

					**계정 유무를 알려주지 않는 것이 이 API의 핵심이다.** 등록되지 않은 주소든, 정지된 계정이든,
					아직 활성화되지 않은 계정이든 응답이 동일하다 — 응답이 갈리면 그 자체가 계정 존재 여부를
					확인하는 수단이 된다. 그래서 화면도 "메일이 도착하지 않았다"를 오류로 처리하면 안 된다.

					아직 활성화되지 않은 초대 계정이 요청하면 재설정이 아니라 **계정 활성화 안내**가 발송된다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "계정 상태와 무관한 동일 안내 응답"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 이메일 형식 오류")
	})
	@PostMapping("/password-reset/requests")
	public ResponseEntity<PasswordResetRequestResponse> requestPasswordReset(
			@Valid @RequestBody PasswordResetRequest request,
			@Parameter(description = "요청 추적용 식별자이며 생략 시 서버가 생성합니다.")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId
	) {
		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(passwordResetService.request(request.email(), requestId(requestId)));
	}

	@Operation(
			operationId = "validatePasswordResetToken",
			summary = "재설정 토큰 사전 검증 | ✅ 사용 가능",
			description = """
					비밀번호 재설정 메일 링크로 들어온 화면이 **입력폼을 그리기 전에** 호출한다. 인증 없이 호출한다.

					**요청**
					- token (필수, 최대 512자): 메일 링크에 담긴 원문 토큰. 서버에는 해시만 저장된다

					**응답**
					- email: 재설정 대상 이메일. 읽기 전용으로 표시한다
					- expiresAt: 이 토큰이 만료되는 시각

					**토큰을 소비하지 않는다.** 확인만 하므로 이 호출 뒤에도 같은 토큰으로
					`POST /auth/password-reset/confirmations`를 그대로 진행할 수 있고, 화면을 새로고침해도 된다.

					조회성 동작이지만 **토큰을 경로가 아니라 본문으로 받는다.** 30분간 비밀번호를 바꿀 수 있는
					자격증명이 URL에 실리면 접근 로그·프록시 로그·APM 트레이스에 평문으로 남기 때문이다.
					초대 링크를 확인하는 `POST /auth/invitations/resolve`와 같은 이유·같은 모양이다.

					**오류 구분은 확정 API와 같다.** 죽은 링크에 비밀번호 입력폼을 그려놓고 제출 시점에야
					실패를 알리지 않기 위한 API이므로, 아래 상태를 받으면 폼 대신 안내 화면을 띄운다.
					- 400: 토큰이 없거나 위변조됨, 계정이 활성 상태가 아님
					- 409: 이미 사용된 토큰
					- 410: 만료된 토큰 → 화면은 재발송 안내를 띄운다
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "아직 사용할 수 있는 토큰"),
			@ApiResponse(responseCode = "400", description = "RESET_TOKEN_INVALID 유효하지 않은 토큰 또는 활성 상태가 아닌 계정"),
			@ApiResponse(responseCode = "409", description = "RESET_TOKEN_USED 이미 사용된 토큰"),
			@ApiResponse(responseCode = "410", description = "RESET_TOKEN_EXPIRED 만료된 토큰")
	})
	@PostMapping("/password-reset/validations")
	public ResponseEntity<PasswordResetValidationResponse> validatePasswordResetToken(
			@Valid @RequestBody PasswordResetValidationRequest request
	) {
		return ResponseEntity.ok(passwordResetService.validate(request));
	}

	@Operation(
			operationId = "confirmPasswordReset",
			summary = "비밀번호 재설정 확정 | ✅ 사용 가능",
			description = """
					메일 링크의 1회용 토큰으로 비밀번호를 실제로 바꾼다. 인증 없이 호출한다.

					**요청**
					- token (필수, 최대 512자): 메일 링크에 담긴 원문 토큰. 서버에는 해시만 저장된다
					- newPassword (필수, 8~64자): 영문·숫자·특수문자를 포함해야 한다
					- newPasswordConfirmation (필수): newPassword와 같아야 한다. 다르면 400

					**응답**
					- status: `COMPLETED`
					- message: 재로그인을 안내하는 문구

					**성공하면 그 계정의 모든 리프레시 토큰이 폐기된다.** 비밀번호를 바꾸는 상황은 대개
					탈취를 의심하는 상황이라, 다른 기기에 남아 있던 세션도 함께 끊는다 — 사용자는 모든 기기에서
					다시 로그인해야 한다.

					**오류 구분**
					- 400: 토큰이 없거나 위변조됨, 요청 형식 오류
					- 409: 이미 사용된 토큰(같은 링크를 두 번 눌렀을 때)
					- 410: 만료된 토큰 → 화면은 재발송 안내를 띄운다
					- 422: 비밀번호 정책 미충족 또는 현재 비밀번호와 동일
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "비밀번호 변경 완료"),
			@ApiResponse(responseCode = "400", description = "RESET_TOKEN_INVALID 유효하지 않은 토큰 · VALIDATION_FAILED 요청 형식 오류"),
			@ApiResponse(responseCode = "409", description = "RESET_TOKEN_USED 이미 사용된 토큰"),
			@ApiResponse(responseCode = "410", description = "RESET_TOKEN_EXPIRED 만료된 토큰"),
			@ApiResponse(responseCode = "422", description = "WEAK_PASSWORD 비밀번호 정책 미충족 · SAME_AS_CURRENT 현재 비밀번호와 동일"),
			@ApiResponse(responseCode = "500", description = "RESET_FAILED 변경 저장 실패. 비밀번호는 바뀌지 않았다")
	})
	@PostMapping("/password-reset/confirmations")
	public ResponseEntity<PasswordResetConfirmationResponse> confirmPasswordReset(
			@Valid @RequestBody PasswordResetConfirmationRequest request,
			@Parameter(description = "요청 추적용 식별자이며 생략 시 서버가 생성합니다.")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId
	) {
		return ResponseEntity.ok(passwordResetService.confirm(request, requestId(requestId)));
	}

	@Operation(
			operationId = "login",
			summary = "통합 로그인 | ✅ 사용 가능",
			description = """
					슈퍼어드민·오퍼레이터·매니저·교육생이 **같은 화면에서** 로그인한다. 역할별 로그인 URL이 따로 없다.

					**요청**
					- email (필수) / password (필수)
					- X-Swagger-Client-Origin (헤더, 선택): Swagger UI에서 테스트할 때만 실제 프론트엔드 Origin을
					  넣는다. 일반 프론트 요청에서는 생략한다(브라우저가 보내는 Origin 헤더를 그대로 쓴다)

					**응답**
					- memberId / email / name / role / organizationId (슈퍼어드민은 organizationId가 null)
					- redirectPath: 로그인 후 이동할 경로 제안. 낼 수 있는 값은 넷뿐이다.
					  | role | 값 |
					  |---|---|
					  | SUPER_ADMIN | `/admin/orgs` |
					  | OPERATOR | 기관의 최신 기수가 있으면 `/cohorts/{기수명 URL 인코딩}`, 없으면 `/cohorts` |
					  | MANAGER | 담당 최신 기수가 있으면 `/cohorts/{기수명 URL 인코딩}`, 없으면 `/cohorts` |
					  | TRAINEE | `/home` |

					  즉 `role` 외에 담기는 정보는 **최신 기수의 이름 하나**이며, 그것도 ID가 아니라 표시명이다.
					  라우트 구조는 프론트 지식이므로 **이 값을 따르지 않고 `role`로 라우팅해도 된다** —
					  기수명이 필요하면 기수 목록 API에서 ID와 함께 받는 편이 낫다.
					- accessToken: 액세스 토큰(Authorization: Bearer)
					- accessTokenExpiresIn: 만료까지 남은 시간 — **단위는 밀리초다**(1시간이면 `3600000`).
					  `Date.now() + accessTokenExpiresIn`이 만료 시각이며, 만료 전 미리 재발급할 때 이 값을 쓴다

					**리프레시 토큰은 응답 본문에 없다.** `Set-Cookie`로 HttpOnly 쿠키에 담겨 나가므로
					자바스크립트가 읽을 수 없고, 재발급은 `POST /auth/refresh`가 쿠키를 자동으로 실어 보내 처리한다.
					쿠키는 `SameSite=None; Secure`라 다른 사이트인 프론트에서 보내는 fetch에도 실린다 —
					`credentials: 'include'`만 켜면 된다.

					**연속 실패는 잠시 지연된다.** 같은 **이메일+IP**로 5회 연속 실패하면 60초,
					이후 실패마다 2배로 늘어 최대 15분까지 `429 LOGIN_TEMPORARILY_BLOCKED`가 나간다.
					한 번 성공하면 카운터는 0으로 돌아간다. 계정을 잠그는 것이 아니므로 해제 절차는 없고,
					다른 자리(다른 IP)에서의 로그인은 영향받지 않는다.

					**오류 구분** — `code`로 분기한다. `message`는 사람이 읽는 문구라 바뀔 수 있다.

					| code | 뜻 | 화면이 할 일 |
					|---|---|---|
					| `LOGIN_INVALID` | 이메일이 없거나 비밀번호가 틀림 | 문구만. **둘을 구분해 주지 않는다** — 구분하면 어떤 이메일이 가입돼 있는지 외부에서 확인할 수 있다 |
					| `LOGIN_TEMPORARILY_BLOCKED` | 이메일+IP 5회 연속 실패 | 응답의 `retryAfter`(**초**)와 `Retry-After` 헤더만큼 버튼을 잠근다 |
					| `LOGIN_ACCOUNT_INACTIVE` | 정지·퇴사 계정 | 문의 안내. 재시도해도 같다 |
					| `LOGIN_ORG_SUSPENDED` | 계정은 정상이나 소속 기관이 정지 | 기관 문의 안내 |
					| `LOGIN_ORIGIN_NOT_ALLOWED` | 허용되지 않은 Origin | 계정 문제가 아니다 |
					| `LOGIN_NO_ORG_CONTEXT` | 기관 소속이 없는 비-슈퍼어드민 계정 | **계정 데이터 결함**이라 5xx다. 관리자 문의 |

					**아직 활성화하지 않은 계정은 `LOGIN_INVALID`로 온다.** 그 계정은 비밀번호가 아예 없어
					상태 검사에 도달하지 못한다. 구분해 주려면 비밀번호 검사 앞에서 상태를 봐야 하는데
					그러면 계정 열거가 가능해지므로 <b>의도적으로 합쳤다.</b>
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "로그인 성공 및 토큰 발급"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 요청 형식 오류 · LOGIN_INVALID 로그인 정보 불일치"),
			@ApiResponse(responseCode = "403",
					description = "LOGIN_ACCOUNT_INACTIVE 정지 계정 · LOGIN_ORG_SUSPENDED 기관 정지 · LOGIN_ORIGIN_NOT_ALLOWED 허용되지 않은 출처"),
			@ApiResponse(responseCode = "429", description = "LOGIN_TEMPORARILY_BLOCKED 연속 실패로 일시 차단. retryAfter(초) 동봉"),
			@ApiResponse(responseCode = "500", description = "LOGIN_NO_ORG_CONTEXT 기관 소속이 없는 계정")
	})
	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(
			@Valid @RequestBody LoginRequest request,
			@Parameter(
					description = "Swagger UI 테스트 시 실제 프론트엔드 Origin. 일반 프론트 요청에서는 생략합니다.",
					example = "http://localhost:5173"
			)
			@RequestHeader(value = SWAGGER_CLIENT_ORIGIN_HEADER, required = false) String swaggerClientOrigin,
			@Parameter(hidden = true)
			HttpServletRequest servletRequest
	) {
		String origin = resolveClientOrigin(servletRequest, swaggerClientOrigin);
		LoginResult result = authService.login(
				request,
				origin,
				requestMetadata(servletRequest)
		);
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, refreshTokenCookieManager.create(result.refreshToken()).toString())
				.body(result.response());
	}

	@Operation(
			operationId = "refresh",
			summary = "액세스 토큰 재발급 | ✅ 사용 가능",
			description = """
					액세스 토큰이 만료됐을 때 새로 발급받는다. 만료된 액세스 토큰을 보낼 필요는 없다 —
					인증은 **리프레시 토큰 쿠키로만** 한다.

					**요청**
					- refresh_token (쿠키, 필수): 로그인 시 발급된 HttpOnly 쿠키. 브라우저가 자동으로 실어 보내므로
					  클라이언트가 직접 다룰 필요가 없다(`credentials: 'include'`만 켜면 된다).
					  쿠키는 `SameSite=None; Secure`이므로 프론트가 백엔드와 다른 사이트여도 실린다
					- X-Swagger-Client-Origin (헤더, 선택): Swagger UI 테스트 전용

					**응답**
					- accessToken
					- accessTokenExpiresIn: 만료까지 남은 시간 — **단위는 밀리초다**(1시간이면 `3600000`)

					**역할·이름은 주지 않는다.** 새로고침 후 세션을 복원할 때는 이 호출 뒤에
					`GET /members/me`를 부른다 — 역할을 브라우저 저장소에 남기지 않아도 되고,
					정지·역할 변경이 즉시 반영된다.

					**401을 받으면 재시도하지 말고 로그인 화면으로 보내야 한다.** 쿠키가 없거나 만료·위조됐거나,
					비밀번호 변경 등으로 세션이 폐기된 상태이므로 다시 호출해도 결과가 같다.
					""",
			parameters = @Parameter(
					name = "refresh_token",
					description = "로그인 시 발급된 HttpOnly 리프레시 토큰 쿠키",
					in = ParameterIn.COOKIE,
					required = true
			)
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "액세스 토큰 재발급 성공"),
			@ApiResponse(responseCode = "401",
					description = "REFRESH_TOKEN_INVALID 토큰 누락·만료·위조 · REFRESH_IDENTITY_CHANGED 역할·기관이 바뀌어 재로그인 필요"),
			@ApiResponse(responseCode = "403",
					description = "LOGIN_ACCOUNT_INACTIVE 정지 계정 · LOGIN_ORG_SUSPENDED 기관 정지 · LOGIN_ORIGIN_NOT_ALLOWED 허용되지 않은 출처"),
			@ApiResponse(responseCode = "429", description = "LOGIN_TEMPORARILY_BLOCKED 일시 차단. retryAfter(초) 동봉"),
			@ApiResponse(responseCode = "500", description = "LOGIN_NO_ORG_CONTEXT 기관 소속이 없는 계정")
	})
	@PostMapping("/refresh")
	public ResponseEntity<RefreshTokenResponse> refresh(
			@Parameter(hidden = true)
			HttpServletRequest request,
			@Parameter(
					description = "Swagger UI 테스트 시 실제 프론트엔드 Origin. 일반 프론트 요청에서는 생략합니다.",
					example = "http://localhost:5173"
			)
			@RequestHeader(value = SWAGGER_CLIENT_ORIGIN_HEADER, required = false) String swaggerClientOrigin
	) {
		String refreshToken = refreshTokenCookieManager.resolve(request);
		String origin = resolveClientOrigin(request, swaggerClientOrigin);
		return ResponseEntity.ok(authService.refresh(refreshToken, origin, requestMetadata(request)));
	}

	@Operation(
			operationId = "logout",
			summary = "로그아웃 | ✅ 사용 가능",
			description = """
					서버에서 리프레시 토큰을 폐기하고 인증 쿠키를 만료시킨다.

					**요청**
					- refresh_token (쿠키, 선택): 없어도 204로 응답한다
					- X-Swagger-Client-Origin (헤더, 선택): Swagger UI 테스트 전용

					**응답 (204)**
					- 본문 없음. 쿠키를 지우는 `Set-Cookie`가 함께 나간다

					**쿠키가 없어도 실패하지 않는다.** 이미 로그아웃된 상태에서 다시 눌러도 화면이 오류를 띄우면
					안 되기 때문이다. 다만 이미 발급된 **액세스 토큰은 만료 시각까지 유효하다** —
					서버가 액세스 토큰을 따로 무효화하지 않으므로 클라이언트가 메모리에서 지워야 한다.
					""",
			parameters = @Parameter(
					name = "refresh_token",
					description = "폐기할 HttpOnly 리프레시 토큰 쿠키",
					in = ParameterIn.COOKIE
			)
	)
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "로그아웃 처리 및 인증 쿠키 삭제"),
			@ApiResponse(responseCode = "403", description = "LOGIN_ORIGIN_NOT_ALLOWED 허용되지 않은 요청 출처")
	})
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
			@Parameter(hidden = true)
			HttpServletRequest request,
			@Parameter(
					description = "Swagger UI 테스트 시 실제 프론트엔드 Origin. 일반 프론트 요청에서는 생략합니다.",
					example = "http://localhost:5173"
			)
			@RequestHeader(value = SWAGGER_CLIENT_ORIGIN_HEADER, required = false) String swaggerClientOrigin
	) {
		String origin = resolveClientOrigin(request, swaggerClientOrigin);
		authService.logout(refreshTokenCookieManager.resolve(request), origin);
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, refreshTokenCookieManager.clear().toString())
				.build();
	}

	@Operation(
			operationId = "resolveInvitation",
			summary = "초대 토큰 해석 | ✅ 사용 가능",
			description = """
					초대 메일 링크를 열었을 때 **가장 먼저** 호출한다. 토큰이 아직 쓸 수 있는지 확인하고,
					가입 화면에 뿌릴 대상 사용자 ID와 이메일을 받아온다. 인증 없이 호출한다.

					**요청**
					- invitationToken (필수): 초대 링크에 담긴 원문 토큰. 서버에는 해시만 저장된다

					**응답**
					- user_id: 후속 가입·활성화 요청(`/auth/manager-signup`, `/auth/trainee-activation`)에 그대로 넘긴다
					- email: 초대 원장에서 확인한 이메일. **읽기 전용으로 표시하고 수정 입력을 열지 않는다** —
					  초대받은 주소가 아닌 곳으로 계정이 만들어지면 안 되기 때문이다
					- role: **초대 대상자**의 역할(`SUPER_ADMIN` · `OPERATOR` · `MANAGER` · `TRAINEE`)

					슈퍼어드민·오퍼레이터·매니저·교육생 초대를 **모두 이 한 API로** 해석한다.
					role 을 링크나 화면에서 추측하지 말고 이 응답을 따라간다 — 활성화 단계에서 필수 동의를
					강제하는 값과 같은 값이라, 화면이 보여준 동의 항목과 서버가 검증하는 항목이 어긋나지 않는다.

					**후속 흐름**
					1. `GET /consents?role={role}` 로 표시할 동의 항목을 받는다
					2. TRAINEE 면 `POST /auth/trainee-activation`, 그 외에는 `POST /auth/manager-signup`

					**링크가 죽은 이유는 코드로 갈라 내려간다.** 화면이 안내할 다음 행동이 서로 다르기 때문이다.

					| 코드 | 상태 | 화면이 안내할 것 |
					|---|---|---|
					| 400 `INVITATION_INVALID` | 토큰 누락·위변조, 계정·기관이 링크를 받을 수 없는 상태 | 문의 |
					| 409 `INVITATION_ALREADY_ACCEPTED` | 이미 수락·활성화된 초대 | 로그인 |
					| 410 `INVITATION_EXPIRED` | 기한 경과, 또는 재발송으로 교체된 이전 링크 | 재발송 요청 |
					| 403 `INVITATION_NOT_IN_ROSTER` | 교육생인데 명단에 자리가 없음(초대 취소·기수 이탈) | 문의 |

					**판정 순서가 정해져 있다.** 한 토큰이 여러 조건에 동시에 걸리므로 먼저 보는 것이 답이 된다 —
					구조적 무효 → 이미 수락 → 만료 → 명단 외 순이다. 수락된 초대는 시간이 지나면 만료 조건에도
					걸리는데, 그때 맞는 안내는 재발송이 아니라 로그인이라 이미 수락을 먼저 본다.

					`INVITATION_INVALID`는 **세부 사유를 알려 주지 않는다** — 토큰을 긁어 보는 쪽에 단서가 되기 때문이다.
					나머지 셋은 사용자가 다음 행동을 골라야 하므로 갈라 준다. 이 분기는 추측할 수 없는 토큰을 이미
					가진 사람에게만 보이므로 계정 존재 여부가 새지 않는다.

					같은 판정을 `/auth/manager-signup`·`/auth/trainee-activation`도 그대로 쓴다 —
					링크를 열 때는 "만료"라고 했다가 제출할 때 "유효하지 않음"이라고 하면 안내가 갈린다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "현재 유효한 SUPER_ADMIN·OPERATOR·MANAGER·TRAINEE 초대 대상 해석 성공"),
			@ApiResponse(responseCode = "400", description = "INVITATION_INVALID 토큰 누락·위변조 또는 계정·기관이 링크를 받을 수 없는 상태"),
			@ApiResponse(responseCode = "403", description = "INVITATION_NOT_IN_ROSTER 교육생 명단에 살아 있는 자리가 없음"),
			@ApiResponse(responseCode = "409", description = "INVITATION_ALREADY_ACCEPTED 이미 수락·활성화된 초대"),
			@ApiResponse(responseCode = "410", description = "INVITATION_EXPIRED 초대 링크 만료 또는 재발송으로 교체됨")
	})
	@PostMapping("/invitations/resolve")
	public ResponseEntity<InvitationResolveResponse> resolveInvitation(
			@Valid @RequestBody InvitationResolveRequest request
	) {
		return ResponseEntity.ok(invitationResolveService.resolve(request));
	}

	@Operation(
			operationId = "resendAccountInvitation",
			summary = "초대 메일 재발송 | ✅ 사용 가능",
			description = """
					초대 링크가 만료됐거나 아직 활성화하지 않은 계정이 초대 메일을 다시 받는다. 인증 없이 호출한다.

					**요청**
					- email (필수, 최대 320자): 초대받았던 이메일

					**응답 (202)**
					- message: 계정 존재 여부와 무관하게 **항상 같은 문구**를 반환한다

					`POST /auth/password-reset/requests`와 같은 이유로 응답을 하나로 합친다 — 응답이 갈리면
					그 자체가 계정 존재 여부를 확인하는 수단이 된다. 화면도 "메일이 도착하지 않았다"를
					오류로 처리하면 안 된다.

					**재발송하면 이전 링크는 즉시 죽는다.** 새 토큰이 발급되고 기존 토큰은 교체 처리되므로,
					사용자가 예전 메일의 링크를 누르면 400이 난다. 화면 안내에 "가장 최근 메일을 사용하세요"를 포함한다.

					**이미 활성화된 계정에는 아무것도 보내지 않는다**(응답은 동일하다). 비밀번호를 잊은 경우이므로
					`POST /auth/password-reset/requests`로 안내한다.

					재설정 안내 요청과 **같은 쿨다운 창을 공유한다.** 짧은 간격으로 다시 호출하면 응답은 202지만
					메일은 나가지 않는다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "계정 상태와 무관한 동일 안내 응답"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 이메일 형식 오류")
	})
	@PostMapping("/invitations/resend")
	public ResponseEntity<InvitationResendResponse> resendInvitation(
			@Valid @RequestBody InvitationResendRequest request,
			@Parameter(description = "요청 추적용 식별자이며 생략 시 서버가 생성합니다.")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId
	) {
		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(invitationResendService.request(request.email(), requestId(requestId)));
	}

	@Operation(
			operationId = "signupManager",
			summary = "초대받은 오퍼레이터·매니저 가입 | ✅ 사용 가능",
			description = """
					초대받은 오퍼레이터 또는 매니저가 이름·비밀번호를 정해 계정을 활성화한다(AU-02).
					인증 없이 호출하며, 신원은 초대 토큰이 증명한다. 앞서 `/auth/invitations/resolve`로 받은
					user_id를 그대로 넘긴다.

					**요청**
					- user_id (필수): 초대 토큰 해석 응답의 사용자 ID
					- invitationToken (필수, 최대 512자): 초대 링크의 원문 토큰
					- name (필수, 최대 200자): 표시 이름
					- password (필수, 8~64자): 영문·숫자·특수문자를 각각 하나 이상 포함해야 한다
					- passwordConfirmation (필수): password와 같아야 한다
					- serviceTermsAgreed (필수, true): 서비스 이용약관 동의
					- privacyCollectionAgreed (필수, true): 개인정보 수집 동의

					필수 동의 2개는 **false로 보내면 400**이다. 화면에서 체크하지 않으면 제출 버튼을 막아야 한다.

					**응답**
					- user_id / email / name / role (OPERATOR 또는 MANAGER) / activated

					**이메일은 요청에 없다.** 초대 원장의 주소를 그대로 쓰며, 링크를 여는 것으로 소유가 확인된 것으로
					본다 — 그래서 가입 직후 별도 이메일 인증 단계가 없다.

					계정 활성화·이메일 검증·동의 기록·초대 수락·토큰 사용 처리가 **한 트랜잭션**으로 함께 확정된다.

					**초대 링크 상태 코드는 `/auth/invitations/resolve`와 같다** — 만료 410, 이미 수락 409
					`INVITATION_ALREADY_ACCEPTED`, 무효 400. 링크를 연 뒤 제출까지 사이에 상태가 바뀔 수 있으므로
					제출 단계에서도 같은 분기를 처리해야 한다.

					409가 두 종류다. `INVITATION_ALREADY_ACCEPTED`는 로그인으로 보내고,
					`ACTIVATION_STATE_CHANGED`는 동시 제출로 확정이 밀린 경우라 새로고침 후 재시도를 안내한다 —
					입력이 틀린 것이 아니므로 입력칸에 오류를 붙이면 안 된다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "OPERATOR 또는 MANAGER 계정 활성화와 초대 ACCEPTED 전환 성공"),
			@ApiResponse(responseCode = "400",
					description = "INVITATION_INVALID 초대 토큰·목적·대상 역할이 유효하지 않음 · PASSWORD_CONFIRMATION_MISMATCH 비밀번호 확인 불일치 · REQUIRED_CONSENT_MISSING 필수 동의 누락"),
			@ApiResponse(responseCode = "409",
					description = "INVITATION_ALREADY_ACCEPTED 이미 수락된 초대 · ACTIVATION_STATE_CHANGED 동시 요청으로 계정·초대·토큰 상태가 먼저 변경됨"),
			@ApiResponse(responseCode = "410", description = "INVITATION_EXPIRED 초대 링크 만료 또는 재발송으로 교체됨"),
			@ApiResponse(responseCode = "422", description = "WEAK_PASSWORD 비밀번호 정책 미충족")
	})
	@PostMapping("/manager-signup")
	public ResponseEntity<ActivateAccountResponse> signupManager(
			@Valid @RequestBody ManagerSignupRequest request,
			@Parameter(description = "활성화 요청 추적용 식별자이며 생략 시 서버가 생성합니다.", example = "signup-request-001")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
			@Parameter(hidden = true)
			HttpServletRequest servletRequest
	) {
		return ResponseEntity.ok(accountActivationService.activateManager(
				request,
				requestMetadata(servletRequest),
				requestId(requestId),
				locale(servletRequest)
		));
	}

	@Operation(
			operationId = "activateTrainee",
			summary = "초대받은 교육생 계정 활성화 | ✅ 사용 가능",
			description = """
					초대받은 교육생이 비밀번호를 정해 계정을 활성화한다. 인증 없이 호출하며,
					신원은 초대 토큰이 증명한다.

					**요청**
					- user_id (필수): 초대 토큰 해석 응답의 사용자 ID
					- invitationToken (필수, 최대 512자): 초대 링크의 원문 토큰
					- password (필수, 8~64자): 영문·숫자·특수문자를 각각 하나 이상 포함해야 한다
					- passwordConfirmation (필수): password와 같아야 한다
					- serviceTermsAgreed (필수, true): 서비스 이용약관
					- privacyCollectionAgreed (필수, true): 개인정보 수집
					- aiAnalysisAgreed (필수, true): AI 분석
					- organizationSharingAgreed (필수, true): 기관 공유
					- anonymousImprovementAgreed (선택): 익명 데이터 서비스 개선. **유일하게 false를 허용**한다

					**이름을 받지 않는다.** 교육생 이름은 오퍼레이터가 명단(CSV·직접 입력)으로 등록할 때 이미 정해져 있다.
					매니저 가입(`/auth/manager-signup`)과 다른 점이니 화면에 이름 입력칸을 두지 않는다.

					필수 동의 4개는 false로 보내면 400이다. AI 분석·기관 공유가 필수인 것은 서비스가 그 데이터를
					전제로 동작하기 때문이고, 익명 개선 동의만 거부해도 서비스를 쓸 수 있다.

					**응답**
					- user_id / email / name / role (TRAINEE) / activated

					계정 활성화와 **기수 소속(cohort_member) 활성화**가 함께 확정된다 — 초대 상태로 남아 있던
					명단 항목이 이 시점에 실제 수강생이 된다.

					**명단에 자리가 없으면 403 `INVITATION_NOT_IN_ROSTER`로 먼저 막는다.** 초대가 취소됐거나
					기수에서 빠진 경우이며, 비밀번호를 쓰기 전에 판정한다. 전에는 이 상황이 멤버십 갱신 단계에서
					터져 "다시 시도해 주세요"(409)로 나갔는데, 다시 시도해도 결과가 같은 상황이라 오답이었다.

					나머지 초대 링크 상태 코드는 `/auth/invitations/resolve`와 같다 — 만료 410, 이미 활성화 409
					`INVITATION_ALREADY_ACCEPTED`(재수강생 재활성화 시도 포함), 무효 400.
					`ACTIVATION_STATE_CHANGED`(409)는 동시 제출로 확정이 밀린 경우이며 새로고침 후 재시도를 안내한다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "TRAINEE 계정·기수 소속 활성화와 초대 ACCEPTED 전환 성공"),
			@ApiResponse(responseCode = "400",
					description = "INVITATION_INVALID 교육생 초대 토큰·목적·역할이 유효하지 않음 · PASSWORD_CONFIRMATION_MISMATCH 비밀번호 확인 불일치 · REQUIRED_CONSENT_MISSING 필수 동의 누락"),
			@ApiResponse(responseCode = "403", description = "INVITATION_NOT_IN_ROSTER 명단에 살아 있는 자리가 없음(초대 취소·기수 이탈)"),
			@ApiResponse(responseCode = "409",
					description = "INVITATION_ALREADY_ACCEPTED 이미 수락·활성화된 초대 · ACTIVATION_STATE_CHANGED 동시 요청으로 계정·기수 소속·초대·토큰 상태가 먼저 변경됨"),
			@ApiResponse(responseCode = "410", description = "INVITATION_EXPIRED 초대 링크 만료 또는 재발송으로 교체됨"),
			@ApiResponse(responseCode = "422", description = "WEAK_PASSWORD 비밀번호 정책 미충족")
	})
	@PostMapping("/trainee-activation")
	public ResponseEntity<ActivateAccountResponse> activateTrainee(
			@Valid @RequestBody TraineeActivationRequest request,
			@Parameter(description = "활성화 요청 추적용 식별자이며 생략 시 서버가 생성합니다.", example = "activation-request-001")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
			@Parameter(hidden = true)
			HttpServletRequest servletRequest
	) {
		return ResponseEntity.ok(accountActivationService.activateTrainee(
				request,
				requestMetadata(servletRequest),
				requestId(requestId),
				locale(servletRequest)
		));
	}

	private String requestId(String requestId) {
		return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.trim();
	}

	private String locale(HttpServletRequest request) {
		String languageTag = request.getLocale().toLanguageTag();
		return languageTag.isBlank() || "und".equals(languageTag) ? "ko-KR" : languageTag;
	}

	/**
	 * <b>{@code getRemoteAddr()}를 그대로 쓰지 않는다.</b> 이 서비스는 프록시 뒤에 있어서 그 값은
	 * 브라우저가 아니라 <b>프록시의 주소</b>이고, 프록시가 여러 대라 요청마다 값이 달라진다.
	 * 그 값이 연속 실패 차단의 키로 들어가면서 <b>카운터가 흩어져 차단이 새어나갔다</b>
	 * (4차 요청서 Q1). 재발급 토큰의 감사 기록에 남는 IP도 같은 이유로 프록시 주소였다 —
	 * "어디서 로그인했나"를 남기려던 칼럼이 아무 정보도 담지 못하고 있었다.
	 */
	private TokenRequestMetadata requestMetadata(HttpServletRequest request) {
		String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
		return new TokenRequestMetadata(
				clientIpResolver.resolve(request),
				userAgent == null ? "" : userAgent
		);
	}

	private String resolveClientOrigin(HttpServletRequest request, String swaggerClientOrigin) {
		return loginOriginResolver.resolve(
				request.getHeader(HttpHeaders.ORIGIN),
				swaggerClientOrigin
		);
	}
}
