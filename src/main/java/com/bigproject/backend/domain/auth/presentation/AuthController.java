package com.bigproject.backend.domain.auth.presentation;

import com.bigproject.backend.domain.auth.application.AccountActivationService;
import com.bigproject.backend.domain.auth.application.AuthService;
import com.bigproject.backend.domain.auth.application.InvitationResolveService;
import com.bigproject.backend.domain.auth.application.LoginResult;
import com.bigproject.backend.domain.auth.application.LoginOriginResolver;
import com.bigproject.backend.domain.auth.application.PasswordResetService;
import com.bigproject.backend.domain.auth.domain.TokenRequestMetadata;
import com.bigproject.backend.domain.auth.presentation.dto.ActivateAccountResponse;
import com.bigproject.backend.domain.auth.presentation.dto.InvitationResolveRequest;
import com.bigproject.backend.domain.auth.presentation.dto.InvitationResolveResponse;
import com.bigproject.backend.domain.auth.presentation.dto.LoginRequest;
import com.bigproject.backend.domain.auth.presentation.dto.LoginResponse;
import com.bigproject.backend.domain.auth.presentation.dto.ManagerSignupRequest;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetConfirmationRequest;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetConfirmationResponse;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetRequest;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetRequestResponse;
import com.bigproject.backend.domain.auth.presentation.dto.RefreshTokenResponse;
import com.bigproject.backend.domain.auth.presentation.dto.TraineeActivationRequest;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Auth", description = "로그인, Access/Refresh Token 재발급·세션 폐기, 비밀번호 재설정, 초대 계정 활성화 API")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
	public static final String SWAGGER_CLIENT_ORIGIN_HEADER = "X-Swagger-Client-Origin";
	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final AuthService authService;
	private final AccountActivationService accountActivationService;
	private final InvitationResolveService invitationResolveService;
	private final RefreshTokenCookieManager refreshTokenCookieManager;
	private final LoginOriginResolver loginOriginResolver;
	private final PasswordResetService passwordResetService;

	@Operation(
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
			@ApiResponse(responseCode = "400", description = "이메일 형식 오류")
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
			@ApiResponse(responseCode = "400", description = "유효하지 않은 토큰 또는 요청 형식 오류"),
			@ApiResponse(responseCode = "409", description = "이미 사용된 토큰"),
			@ApiResponse(responseCode = "410", description = "만료된 토큰"),
			@ApiResponse(responseCode = "422", description = "비밀번호 정책 미충족 또는 현재 비밀번호와 동일"),
			@ApiResponse(responseCode = "500", description = "변경 저장 실패 및 롤백")
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
			summary = "통합 로그인 | ✅ 사용 가능",
			description = """
					슈퍼어드민·오퍼레이터·매니저·교육생이 **같은 화면에서** 로그인한다. 역할별 로그인 URL이 따로 없다.

					**요청**
					- email (필수) / password (필수)
					- X-Swagger-Client-Origin (헤더, 선택): Swagger UI에서 테스트할 때만 실제 프론트엔드 Origin을
					  넣는다. 일반 프론트 요청에서는 생략한다(브라우저가 보내는 Origin 헤더를 그대로 쓴다)

					**응답**
					- memberId / email / name / role / organizationId (슈퍼어드민은 organizationId가 null)
					- redirectPath: **로그인 후 이동할 경로를 서버가 정해서 내려준다.** 클라이언트가 역할을 보고
					  분기하지 말고 이 값을 그대로 따라가면 된다 — 접근 범위 판단(담당 기수가 하나뿐인 매니저 등)이
					  서버에만 있기 때문이다
					- accessToken: 액세스 토큰(Authorization: Bearer)
					- accessTokenExpiresIn: 만료까지 남은 초

					**리프레시 토큰은 응답 본문에 없다.** `Set-Cookie`로 HttpOnly 쿠키에 담겨 나가므로
					자바스크립트가 읽을 수 없고, 재발급은 `POST /auth/refresh`가 쿠키를 자동으로 실어 보내 처리한다.

					**오류 구분** — 이메일이 없는 경우와 비밀번호가 틀린 경우를 400으로 합쳐 응답한다(계정 존재 여부 비노출).
					403은 계정·기관이 정지·비활성 상태이거나 허용되지 않은 Origin에서 온 요청이다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "로그인 성공 및 토큰 발급"),
			@ApiResponse(responseCode = "400", description = "요청 형식 오류 또는 로그인 정보 불일치"),
			@ApiResponse(responseCode = "403", description = "계정·기관 상태 또는 요청 Origin이 허용되지 않음")
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
			summary = "액세스 토큰 재발급 | ✅ 사용 가능",
			description = """
					액세스 토큰이 만료됐을 때 새로 발급받는다. 만료된 액세스 토큰을 보낼 필요는 없다 —
					인증은 **리프레시 토큰 쿠키로만** 한다.

					**요청**
					- refresh_token (쿠키, 필수): 로그인 시 발급된 HttpOnly 쿠키. 브라우저가 자동으로 실어 보내므로
					  클라이언트가 직접 다룰 필요가 없다(`credentials: 'include'`만 켜면 된다)
					- X-Swagger-Client-Origin (헤더, 선택): Swagger UI 테스트 전용

					**응답**
					- accessToken / accessTokenExpiresIn

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
			@ApiResponse(responseCode = "401", description = "리프레시 토큰 누락·만료·위조 또는 인증 정보 변경"),
			@ApiResponse(responseCode = "403", description = "계정·기관 상태 또는 요청 Origin이 허용되지 않음")
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
			@ApiResponse(responseCode = "403", description = "요청 Origin이 허용되지 않음")
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

					오퍼레이터·매니저·교육생 초대를 **모두 이 한 API로** 해석한다. 다만 응답에 역할이 없으므로
					어느 가입 화면으로 갈지는 링크(초대 메일)가 결정한다.

					**400이면 링크가 죽은 것이다** — 만료, 이미 사용됨, 재발송으로 교체됨, 초대가 취소됨 중 하나다.
					모두 400 하나로 합쳐 응답하므로 화면은 "링크가 유효하지 않습니다 · 재발송을 요청하세요"로 안내한다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "현재 유효한 OPERATOR·MANAGER·TRAINEE 초대 대상 해석 성공"),
			@ApiResponse(responseCode = "400", description = "토큰 누락·위변조·만료·사용 완료·교체 또는 초대 상태가 SENT가 아님")
	})
	@PostMapping("/invitations/resolve")
	public ResponseEntity<InvitationResolveResponse> resolveInvitation(
			@Valid @RequestBody InvitationResolveRequest request
	) {
		return ResponseEntity.ok(invitationResolveService.resolve(request));
	}

	@Operation(
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
					409는 같은 링크로 동시에 두 번 제출한 경우이며, 화면은 로그인 화면으로 보내면 된다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "OPERATOR 또는 MANAGER 계정 활성화와 초대 ACCEPTED 전환 성공"),
			@ApiResponse(responseCode = "400", description = "사용자 ID·비밀번호 확인·필수 동의·현재 초대 토큰 또는 대상 역할이 유효하지 않음"),
			@ApiResponse(responseCode = "409", description = "동시 요청으로 계정·초대·토큰 상태가 먼저 변경됨")
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
					명단 항목이 이 시점에 실제 수강생이 된다. 409는 동시 제출로 상태가 먼저 바뀐 경우다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "TRAINEE 계정·기수 소속 활성화와 초대 ACCEPTED 전환 성공"),
			@ApiResponse(responseCode = "400", description = "사용자 ID·비밀번호 확인·필수 동의·현재 교육생 초대 토큰 또는 명단 범위가 유효하지 않음"),
			@ApiResponse(responseCode = "409", description = "동시 요청으로 계정·기수 소속·초대·토큰 상태가 먼저 변경됨")
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

	private TokenRequestMetadata requestMetadata(HttpServletRequest request) {
		String ipAddress = request.getRemoteAddr();
		String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
		return new TokenRequestMetadata(
				ipAddress == null || ipAddress.isBlank() ? "0.0.0.0" : ipAddress,
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
