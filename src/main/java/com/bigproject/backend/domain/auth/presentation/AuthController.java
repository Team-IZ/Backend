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

@Tag(name = "Auth", description = "로그인, 토큰 재발급, 초대 계정 활성화 API")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
	public static final String LOGIN_ENTRY_PATH_HEADER = "X-Login-Entry-Path";
	public static final String SWAGGER_CLIENT_ORIGIN_HEADER = "X-Swagger-Client-Origin";
	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final AuthService authService;
	private final AccountActivationService accountActivationService;
	private final InvitationResolveService invitationResolveService;
	private final RefreshTokenCookieManager refreshTokenCookieManager;
	private final LoginOriginResolver loginOriginResolver;
	private final PasswordResetService passwordResetService;

	@Operation(
			summary = "비밀번호 재설정 안내 요청",
			description = "계정 존재 여부와 상태를 노출하지 않고 재설정 또는 계정 활성화 안내 메일 처리를 요청합니다."
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
			summary = "비밀번호 재설정 확정",
			description = "1회용 재설정 토큰과 새 비밀번호를 검증하고 모든 로그인 연장 세션을 폐기합니다."
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

	@Operation(summary = "로그인", description = "이메일·비밀번호와 로그인 진입 경로를 검증해 액세스·리프레시 토큰을 발급합니다.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "로그인 성공 및 토큰 발급"),
			@ApiResponse(responseCode = "400", description = "요청 형식 오류 또는 로그인 정보 불일치"),
			@ApiResponse(responseCode = "403", description = "계정·기관 상태 또는 요청 Origin·로그인 경로 불일치")
	})
	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(
			@Valid @RequestBody LoginRequest request,
			@Parameter(description = "접속한 프론트엔드 로그인 화면 경로", example = "/manager/login")
			@RequestHeader(LOGIN_ENTRY_PATH_HEADER) String loginEntryPath,
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
				loginEntryPath,
				requestMetadata(servletRequest)
		);
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, refreshTokenCookieManager.create(result.refreshToken()).toString())
				.body(result.response());
	}

	@Operation(
			summary = "액세스 토큰 재발급",
			description = "HttpOnly 리프레시 토큰 쿠키를 검증해 새 액세스 토큰을 발급합니다.",
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
			summary = "로그아웃",
			description = "리프레시 토큰을 폐기하고 인증 쿠키를 만료시킵니다.",
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
			summary = "초대 토큰 해석",
			description = "현재 SENT 상태인 초대 원장과 현재 토큰의 목적·만료·사용·무효화 여부를 검증해 가입/활성화 대상 사용자 ID와 읽기 전용 이메일을 반환합니다."
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
			summary = "초대받은 오퍼레이터·매니저 가입",
			description = "INVITE_OPERATOR_MANAGER 현재 토큰과 초대 대상 역할(OPERATOR 또는 MANAGER), 사용자 ID, 비밀번호 확인, 필수 동의 2개를 검증합니다. "
					+ "성공하면 계정·이메일 검증·동의·초대 수락·토큰 사용을 원자적으로 확정합니다."
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
			summary = "초대받은 교육생 계정 활성화",
			description = "INVITE_TRAINEE 현재 토큰과 PENDING 교육생·기수 소속, 비밀번호 확인, 필수 동의 4개와 선택 동의 1개를 검증합니다. "
					+ "성공하면 계정·기수 소속 활성화, 이메일 검증, 동의, 초대 수락과 토큰 사용을 원자적으로 확정합니다."
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
