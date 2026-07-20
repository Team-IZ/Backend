package com.bigproject.backend.domain.auth.presentation;

import com.bigproject.backend.domain.auth.presentation.dto.ActivateAccountResponse;
import com.bigproject.backend.domain.auth.presentation.dto.LoginRequest;
import com.bigproject.backend.domain.auth.presentation.dto.LoginResponse;
import com.bigproject.backend.domain.auth.presentation.dto.ManagerSignupRequest;
import com.bigproject.backend.domain.auth.presentation.dto.RefreshTokenRequest;
import com.bigproject.backend.domain.auth.presentation.dto.RefreshTokenResponse;
import com.bigproject.backend.domain.auth.presentation.dto.TraineeActivationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "로그인, 토큰 재발급, 초대 계정 활성화 API")
@RestController
@RequestMapping("/auth")
public class AuthController {

	@Operation(summary = "로그인")
	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "액세스 토큰 재발급")
	@PostMapping("/refresh")
	public ResponseEntity<RefreshTokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
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
}
