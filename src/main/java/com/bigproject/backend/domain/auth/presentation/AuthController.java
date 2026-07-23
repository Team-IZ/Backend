package com.bigproject.backend.domain.auth.presentation;

import com.bigproject.backend.domain.auth.application.AccountActivationService;
import com.bigproject.backend.domain.auth.application.AuthService;
import com.bigproject.backend.domain.auth.application.InvitationResolveService;
import com.bigproject.backend.domain.auth.application.LoginResult;
import com.bigproject.backend.domain.auth.application.LoginOriginResolver;
import com.bigproject.backend.domain.auth.domain.TokenRequestMetadata;
import com.bigproject.backend.domain.auth.presentation.dto.ActivateAccountResponse;
import com.bigproject.backend.domain.auth.presentation.dto.InvitationResolveRequest;
import com.bigproject.backend.domain.auth.presentation.dto.InvitationResolveResponse;
import com.bigproject.backend.domain.auth.presentation.dto.LoginRequest;
import com.bigproject.backend.domain.auth.presentation.dto.LoginResponse;
import com.bigproject.backend.domain.auth.presentation.dto.ManagerSignupRequest;
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

	@Operation(summary = "초대 토큰으로 가입 대상 사용자 조회", description = "초대 토큰을 검증해 가입 대상 사용자 ID와 이메일을 반환합니다.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "유효한 초대 대상 조회 성공"),
			@ApiResponse(responseCode = "400", description = "초대 토큰 누락·만료·사용 완료 또는 유효하지 않음")
	})
	@PostMapping("/invitations/resolve")
	public ResponseEntity<InvitationResolveResponse> resolveInvitation(
			@Valid @RequestBody InvitationResolveRequest request
	) {
		return ResponseEntity.ok(invitationResolveService.resolve(request));
	}

	@Operation(summary = "초대받은 매니저 회원가입", description = "매니저 초대 토큰과 필수 동의를 검증해 계정을 활성화합니다.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "매니저 계정 활성화 성공"),
			@ApiResponse(responseCode = "400", description = "요청·비밀번호·동의 또는 초대 토큰이 유효하지 않음"),
			@ApiResponse(responseCode = "409", description = "초대 또는 계정 상태가 동시에 변경됨")
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

	@Operation(summary = "초대받은 교육생 계정 활성화", description = "교육생 초대 토큰과 필수 동의를 검증해 계정·기수 소속을 활성화합니다.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "교육생 계정과 기수 소속 활성화 성공"),
			@ApiResponse(responseCode = "400", description = "요청·비밀번호·동의 또는 초대 토큰이 유효하지 않음"),
			@ApiResponse(responseCode = "409", description = "초대·계정 또는 기수 소속 상태가 동시에 변경됨")
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
