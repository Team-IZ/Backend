package com.bigproject.backend.domain.auth.presentation;

import com.bigproject.backend.domain.auth.application.AuthService;
import com.bigproject.backend.domain.auth.application.LoginResult;
import com.bigproject.backend.domain.auth.application.LoginOriginResolver;
import com.bigproject.backend.domain.auth.domain.TokenRequestMetadata;
import com.bigproject.backend.domain.auth.presentation.dto.ActivateAccountResponse;
import com.bigproject.backend.domain.auth.presentation.dto.LoginRequest;
import com.bigproject.backend.domain.auth.presentation.dto.LoginResponse;
import com.bigproject.backend.domain.auth.presentation.dto.ManagerSignupRequest;
import com.bigproject.backend.domain.auth.presentation.dto.RefreshTokenResponse;
import com.bigproject.backend.domain.auth.presentation.dto.TraineeActivationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

@Tag(name = "Auth", description = "로그인, 토큰 재발급, 초대 계정 활성화 API")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
	public static final String LOGIN_ENTRY_PATH_HEADER = "X-Login-Entry-Path";
	public static final String SWAGGER_CLIENT_ORIGIN_HEADER = "X-Swagger-Client-Origin";

	private final AuthService authService;
	private final RefreshTokenCookieManager refreshTokenCookieManager;
	private final LoginOriginResolver loginOriginResolver;

	@Operation(summary = "로그인")
	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(
			@Valid @RequestBody LoginRequest request,
			@RequestHeader(LOGIN_ENTRY_PATH_HEADER) String loginEntryPath,
			@Parameter(
					description = "Swagger UI 테스트 시 실제 프론트엔드 Origin. 일반 프론트 요청에서는 생략합니다.",
					example = "http://localhost:5173"
			)
			@RequestHeader(value = SWAGGER_CLIENT_ORIGIN_HEADER, required = false) String swaggerClientOrigin,
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

	@Operation(summary = "액세스 토큰 재발급")
	@PostMapping("/refresh")
	public ResponseEntity<RefreshTokenResponse> refresh(
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

	@Operation(summary = "로그아웃")
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
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

	@Operation(summary = "초대받은 매니저 회원가입")
	@PostMapping("/manager-signup")
	public ResponseEntity<ActivateAccountResponse> signupManager(
			@Valid @RequestBody ManagerSignupRequest request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "초대받은 교육생 계정 활성화")
	@PostMapping("/trainee-activation")
	public ResponseEntity<ActivateAccountResponse> activateTrainee(
			@Valid @RequestBody TraineeActivationRequest request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
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
